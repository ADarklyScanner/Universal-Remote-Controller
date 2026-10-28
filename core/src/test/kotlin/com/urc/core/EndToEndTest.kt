package com.urc.core

import com.urc.core.mapping.CommandEncoder
import com.urc.core.mapping.EncodingType
import com.urc.core.mapping.MapGenerator
import com.urc.core.mapping.Role
import com.urc.core.probe.Probe
import com.urc.core.probe.ProbeEngine
import com.urc.core.probe.ProbeLibrary
import com.urc.core.probe.ProbeOptions
import com.urc.core.probe.SafetyGate
import com.urc.core.protocol.Mavlink
import com.urc.core.protocol.ProtocolKind
import com.urc.core.sim.FakeTransport
import com.urc.core.sim.Vehicles
import com.urc.core.transport.TransportType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndToEndTest {
    private val fast = ProbeOptions(passiveMs = 50, probeTimeoutMs = 20, minIntervalMs = 1)

    private suspend fun probe(t: FakeTransport, gate: SafetyGate = SafetyGate.DENY_ALL, opts: ProbeOptions = fast) =
        withContext(Dispatchers.Default) {
            t.connect()
            ProbeEngine(gate, opts).run(t, "sim")
        }

    @Test fun bleUartCar() = runTest {
        val car = Vehicles.bleUartCar()
        val report = probe(car)
        assertEquals(ProtocolKind.KEY_VALUE, report.protocol)
        assertTrue(report.fields.any { it.name == "batt" })
        assertTrue(report.endpoints.any { it.value == "RCCar-BLE" })
        assertTrue(report.samples.any { it.ascii.contains("RCCar v1.2") })

        val map = MapGenerator().generate(report)
        assertEquals("ble-uart-car", map.profileId)
        val throttle = map.bindings.first { it.role == Role.THROTTLE }
        assertEquals(EncodingType.GATT_WRITE, throttle.encoding.type)
        assertEquals(Vehicles.UART_SERVICE_TX, throttle.target.endpoint)

        car.sent.clear()
        val cmds = CommandEncoder().encodeAll(map, mapOf(throttle.id to 1.0))
        cmds.forEach { car.send(it.bytes, it.endpoint) }
        assertTrue(car.sent.any { String(it.second) == "T100\n" && it.first == Vehicles.UART_SERVICE_TX })
    }

    @Test fun httpRobot() = runTest {
        val report = probe(Vehicles.httpRobot())
        assertTrue(report.fields.any { it.name == "battery" })
        val map = MapGenerator().generate(report)
        assertEquals("http-robot", map.profileId)
        assertEquals("POST /api/drive", map.bindings.first { it.role == Role.THROTTLE }.target.endpoint)
    }

    @Test fun mavlinkDrone() = runTest {
        val drone = Vehicles.mavlinkDrone()
        val report = probe(drone)
        assertEquals(ProtocolKind.MAVLINK, report.protocol)
        val map = MapGenerator().generate(report)
        assertEquals("mavlink", map.profileId)
        val cmds = CommandEncoder().encodeAll(map, emptyMap())
        assertEquals(70, Mavlink.parse(cmds.single().bytes).single().messageId)
    }

    @Test fun unsafeProbesAreGated() = runTest {
        val unsafe = ProbeLibrary.movementProbe("GO\n".toByteArray())
        val opts = fast.copy(probes = listOf(unsafe))
        val denied = Vehicles.bleUartCar()
        val r1 = probe(denied, SafetyGate.DENY_ALL, opts)
        assertEquals(listOf("move:custom"), r1.skippedProbes)
        assertTrue(denied.sent.isEmpty())
        val allowed = Vehicles.bleUartCar()
        var asked: Probe? = null
        val r2 = probe(allowed, { asked = it; true }, opts)
        assertEquals("move:custom", asked?.id)
        assertEquals(listOf("move:custom"), r2.sentProbes)
    }

    @Test fun baudDetection() = runTest {
        val t = FakeTransport(type = TransportType.USB_SERIAL, capabilities = com.urc.core.transport.TransportCapabilities(
            TransportType.USB_SERIAL, supportsBaudRate = true))
        val sink = t
        sink.respond = { _, _ -> emptyList() }
        val report = withContext(Dispatchers.Default) {
            t.connect()
            val engine = ProbeEngine(options = fast.copy(baudCandidates = listOf(9600, 115200), probes = emptyList()))
            val job = launch {
                repeat(40) {
                    delay(10)
                    // only readable text at 115200
                    t.emitIncoming(if (t.baud == 115200) "hello world\n".toByteArray() else byteArrayOf(0x80.toByte(), 0x01, 0xF0.toByte(), 0x03, 0x99.toByte()))
                }
            }
            engine.run(t).also { job.cancel() }
        }
        assertEquals(115200, report.baudRate)
    }
}
