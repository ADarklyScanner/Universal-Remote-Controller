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
