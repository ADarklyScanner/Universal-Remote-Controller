package com.urc.core

import com.urc.core.mapping.MapGenerator
import com.urc.core.probe.ProbeEngine
import com.urc.core.probe.ProbeOptions
import com.urc.core.session.ControlSession
import com.urc.core.session.SessionState
import com.urc.core.sim.Vehicles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest {
    private val fast = ProbeOptions(passiveMs = 50, probeTimeoutMs = 20, minIntervalMs = 1)

    private fun session(scope: CoroutineScope, mapOk: Boolean = true) = Vehicles.bleUartCar().let { car ->
        car to ControlSession(
            car, ProbeEngine(options = fast),
            mapFor = { if (mapOk) MapGenerator().generate(it) else null },
            inputs = { mapOf("throttle" to 0.5) }, scope = scope,
        )
    }

    @Test fun probesThenWaitsForExplicitArm() = runBlocking {
        val (car, s) = session(CoroutineScope(Dispatchers.Default))
        s.connectAndProbe()
        assertTrue(s.state.value is SessionState.Ready)
        val before = car.sent.size
        delay(100)
        assertEquals(before, car.sent.size, "nothing sent while not armed")
        s.close()
    }

    @Test fun armOnlyFromReadyAndDisarmSendsNeutral() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val (car, s) = session(scope)
        assertFalse(s.arm(), "cannot arm before probe")
        s.connectAndProbe()
        assertTrue(s.arm())
        assertTrue(s.state.value is SessionState.Armed)
        delay(200)
        s.emergencyStop()
        assertTrue(s.state.value is SessionState.Ready)
        val after = car.sent.size
        delay(200)
        assertEquals(after, car.sent.size, "loop stopped after disarm")
        s.close()
        assertEquals(SessionState.Idle, s.state.value)
    }

    @Test fun noMapIsError() = runBlocking {
        val (_, s) = session(CoroutineScope(Dispatchers.Default), mapOk = false)
        s.connectAndProbe()
        assertTrue(s.state.value is SessionState.Error)
        assertFalse(s.arm())
    }
}

class SessionAutoTest {
    @Test fun defaultsAutoMapAnyTransport() = runBlocking {
        val car = Vehicles.mavlinkDrone()
        val s = ControlSession(
            car, ProbeEngine(options = ProbeOptions(passiveMs = 50, probeTimeoutMs = 20, minIntervalMs = 1)),
            inputs = { emptyMap() }, scope = CoroutineScope(Dispatchers.Default),
        )
        s.connectAndProbe()
        assertTrue(s.state.value is SessionState.Ready)
        s.close()
    }

    @Test fun probeTimeoutIsBounded() = runBlocking {
        val s = ControlSession(
            Vehicles.bleUartCar(), ProbeEngine(options = ProbeOptions(passiveMs = 5000)),
            inputs = { emptyMap() }, scope = CoroutineScope(Dispatchers.Default), probeTimeoutMs = 100,
        )
        s.connectAndProbe()
        assertTrue(s.state.value is SessionState.Error)
    }
}

class NetTransportTest {
    @Test fun tcpRoundTrip() = runBlocking {
        val server = java.net.ServerSocket(0)
        val srv = Thread {
            server.accept().use { c -> val b = c.getInputStream().readNBytes(3); c.getOutputStream().write(b.reversedArray()) }
        }.also { it.start() }
        val t = com.urc.core.transport.TcpTransport("127.0.0.1", server.localPort)
        val got = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) {
            t.incoming.collect { got.complete(it) }
        }
        t.connect()
        t.send(byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(3, 2, 1), kotlinx.coroutines.withTimeout(3000) { got.await() }.toList())
        job.cancel(); t.disconnect(); srv.join(); server.close()
    }

    @Test fun udpSend() = runBlocking {
        val sock = java.net.DatagramSocket(0)
        val t = com.urc.core.transport.UdpTransport("127.0.0.1", sock.localPort)
        t.connect()
        t.send(byteArrayOf(9, 8))
        val p = java.net.DatagramPacket(ByteArray(8), 8)
        sock.soTimeout = 3000
        sock.receive(p)
        assertEquals(2, p.length)
        t.disconnect(); sock.close()
    }
}

class ProbeGapsTest {
    @Test fun mavlinkSplitAcrossChunksIsDetected() {
        val hb = com.urc.core.protocol.Mavlink.heartbeatV2()
        val d = com.urc.core.protocol.FrameClassifier.classify(listOf(hb.copyOfRange(0, 5), hb.copyOfRange(5, hb.size)))
        assertEquals(com.urc.core.protocol.ProtocolKind.MAVLINK, d.kind)
    }

    @Test fun mavlinkScoreRewardsBinaryFrames() {
        val hb = com.urc.core.protocol.Mavlink.heartbeatV2()
        assertTrue(com.urc.core.protocol.FrameClassifier.mavlinkScore(hb + hb) > 0.3)
        assertEquals(0.0, com.urc.core.protocol.FrameClassifier.mavlinkScore(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun keyValueLinesSplitAcrossChunksAreExtracted() {
        val f = com.urc.core.protocol.FrameClassifier.extractFields(listOf("batt=7".toByteArray(), ".4\nspeed=3\n".toByteArray()))
        assertEquals(listOf(7.4), f["batt"])
        assertEquals(listOf(3.0), f["speed"])
    }

    @Test fun unknownProtocolIsFlaggedAsGuessed() = runBlocking {
        val silent = com.urc.core.sim.FakeTransport()
        val s = ControlSession(
            silent, ProbeEngine(options = ProbeOptions(passiveMs = 20, probeTimeoutMs = 10, minIntervalMs = 1)),
            inputs = { emptyMap() }, scope = CoroutineScope(Dispatchers.Default),
        )
        s.connectAndProbe()
        val st = s.state.value
        assertTrue(st is SessionState.Ready && st.guessed)
        s.close()
    }
}
