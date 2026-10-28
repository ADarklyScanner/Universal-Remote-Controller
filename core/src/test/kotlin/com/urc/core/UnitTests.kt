package com.urc.core

import com.urc.core.mapping.Binding
import com.urc.core.mapping.CommandEncoder
import com.urc.core.mapping.ControllerMap
import com.urc.core.mapping.Encoding
import com.urc.core.mapping.EncodingType
import com.urc.core.mapping.FailsafeController
import com.urc.core.mapping.InputKind
import com.urc.core.mapping.InputSource
import com.urc.core.mapping.LearnMode
import com.urc.core.mapping.Scaling
import com.urc.core.mapping.Target
import com.urc.core.mapping.ValueScaler
import com.urc.core.probe.ProbeReport
import com.urc.core.protocol.FrameClassifier
import com.urc.core.protocol.Hex
import com.urc.core.protocol.Mavlink
import com.urc.core.protocol.ProtocolKind
import com.urc.core.sim.FakeTransport
import com.urc.core.transport.TransportType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolTest {
    @Test fun mavlinkV1AndV2Heartbeat() {
        val v1 = Mavlink.parse(Mavlink.heartbeatV1(system = 7, type = 2, autopilot = 3)).single()
        val v2 = Mavlink.parse(Mavlink.heartbeatV2(system = 9, type = 10)).single()
        assertTrue(v1.isHeartbeat && v1.crcValid && v1.system == 7)
        assertEquals(2, Mavlink.decodeHeartbeat(v1)!!.type)
        assertTrue(v2.isHeartbeat && v2.crcValid && v2.system == 9)
        assertEquals(10, Mavlink.decodeHeartbeat(v2)!!.type)
    }

    @Test fun mavlinkRejectsCorruptAndResyncs() {
        val good = Mavlink.heartbeatV2()
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(Mavlink.parse(bad).isEmpty())
        assertEquals(1, Mavlink.parse(byteArrayOf(1, 2, 3) + good).size)
    }

    @Test fun classifier() {
        assertEquals(ProtocolKind.MAVLINK, FrameClassifier.classify(listOf(Mavlink.heartbeatV2())).kind)
        assertEquals(ProtocolKind.JSON, FrameClassifier.classify(listOf("""{"a":1}""".toByteArray())).kind)
        assertEquals(ProtocolKind.KEY_VALUE, FrameClassifier.classify(listOf("batt:7.4,rssi:-50\n".toByteArray())).kind)
        assertEquals(ProtocolKind.NMEA, FrameClassifier.classify(listOf("\$GPGGA,1,2,3\r\n".toByteArray())).kind)
        assertEquals(ProtocolKind.ASCII_LINES, FrameClassifier.classify(listOf("hello\n".toByteArray())).kind)
        assertEquals(ProtocolKind.BINARY_FIXED, FrameClassifier.classify(listOf(byteArrayOf(1, 2, 0x90.toByte()), byteArrayOf(4, 5, 0x91.toByte()))).kind)
        assertEquals("\r\n", FrameClassifier.lineEnding(listOf("a\r\n".toByteArray())))
    }

    @Test fun fieldsAndHex() {
        val f = FrameClassifier.extractFields(listOf("batt:7.4\n".toByteArray(), """{"x":{"y":2}}""".toByteArray()))
        assertEquals(listOf(7.4), f["batt"])
        assertEquals(listOf(2.0), f["x.y"])
        assertEquals("0A FF", Hex.encode(Hex.decode("0aff")))
    }
}

class ScalingTest {
    private val s = Scaling(min = -100.0, max = 100.0, deadzone = 0.1)

    @Test fun deadzoneEndpointsInvert() {
        assertEquals(0.0, ValueScaler.scale(0.05, s))
        assertEquals(100.0, ValueScaler.scale(1.0, s), 1e-9)
        assertEquals(-100.0, ValueScaler.scale(-1.0, s), 1e-9)
        assertEquals(-100.0, ValueScaler.scale(1.0, s.copy(invert = true)), 1e-9)
    }

    @Test fun expoReducesMidRange() {
        assertTrue(ValueScaler.scale(0.5, s.copy(expo = 1.0)) < ValueScaler.scale(0.5, s))
    }

    @Test fun clampsInputAndButtonRange() {
        assertEquals(100.0, ValueScaler.scale(5.0, s), 1e-9)
        val b = Scaling(0.0, 1.0, 0.0, 0.0, false, 0.0, 0.0, 1.0)
        assertEquals(1.0, ValueScaler.scale(1.0, b), 1e-9)
        assertEquals(0.0, ValueScaler.scale(0.0, b), 1e-9)
    }
}

class EncoderFailsafeTest {
    private fun map() = ControllerMap(
        id = "m", name = "m",
        bindings = listOf(
            Binding("t", "Throttle", source = InputSource(InputKind.JOYSTICK, "j", "y"), target = Target(TransportType.SIMULATED),
                encoding = Encoding(EncodingType.ASCII, "T{int}{ending}"), scaling = Scaling(-100.0, 100.0, 0.0)),
        ),
    )

    @Test fun encodesAscii() {
        val c = CommandEncoder().encodeAll(map(), mapOf("t" to 0.5)).single()
        assertEquals("T50\n", String(c.bytes))
    }

    @Test fun mavlinkChannelsAggregate() {
        val m = ControllerMap(
            id = "m", name = "m",
            bindings = (1..2).map {
                Binding("c$it", "c$it", source = InputSource(InputKind.SLIDER), target = Target(TransportType.UDP),
                    encoding = Encoding(EncodingType.MAVLINK_RC_OVERRIDE, channel = it), scaling = Scaling(1000.0, 2000.0, 0.0, neutral = 1500.0))
            },
        )
        val cmds = CommandEncoder().encodeAll(m, mapOf("c1" to 1.0, "c2" to -1.0))
        assertEquals(1, cmds.size)
        val msg = Mavlink.parse(cmds[0].bytes).single()
        assertEquals(70, msg.messageId)
        assertEquals(2000, (msg.payload[0].toInt() and 0xFF) or ((msg.payload[1].toInt() and 0xFF) shl 8))
        assertEquals(1000, (msg.payload[2].toInt() and 0xFF) or ((msg.payload[3].toInt() and 0xFF) shl 8))
    }

    @Test fun failsafeOnTimeoutAndEvents() = runTest {
        var t = 0L
        val tr = FakeTransport()
        tr.connect()
        val fs = FailsafeController(map(), tr, timeoutMs = 500, now = { t })
        assertFalse(fs.check())
        t = 1000
        assertTrue(fs.check())
        assertEquals("T0\n", String(tr.sent.last().second))
        assertFalse(fs.check())
        fs.onInput()
        assertFalse(fs.engaged)
        fs.emergencyStop()
        assertTrue(fs.engaged)
        assertEquals(2, tr.sent.size)
    }

    @Test fun jsonRoundTripAndLearn() {
        val m = map()
        assertEquals(m, ControllerMap.fromJson(m.toJson()))
        assertEquals("speed", LearnMode.detectChangedField(mapOf("speed" to 0.0, "batt" to 7.0), mapOf("speed" to 3.0, "batt" to 7.001)))
        assertEquals("speed", LearnMode.confirm(m, "t", "speed").bindings[0].telemetryField)
        val r = ProbeReport(TransportType.BLE)
        assertEquals(r, ProbeReport.fromJson(r.toJson()))
    }
}
