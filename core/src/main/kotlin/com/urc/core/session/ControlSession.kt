package com.urc.core.session

import com.urc.core.mapping.ControlLoop
import com.urc.core.mapping.ControllerMap
import com.urc.core.mapping.FailsafeController
import com.urc.core.probe.ProbeEngine
import com.urc.core.probe.ProbeReport
import com.urc.core.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Idle : SessionState
    data object Connecting : SessionState
    data object Probing : SessionState
    /** Probed and mapped, outputs are NOT being sent. Operator must call [ControlSession.arm]. */
    data class Ready(val report: ProbeReport) : SessionState
    data class Armed(val report: ProbeReport) : SessionState
    data class Error(val message: String) : SessionState
}

/**
 * Operator-facing session: connect -> probe -> Ready -> (explicit) arm -> Armed. Nothing is sent to the vehicle
 * while not Armed. Disarm / emergency stop always send neutral output first. The Android service and UI drive this.
 */
class ControlSession(
    private val transport: Transport,
    private val probeEngine: ProbeEngine,
    private val mapFor: (ProbeReport) -> ControllerMap?,
    private val inputs: () -> Map<String, Double>,
    private val scope: CoroutineScope,
) {
    private val st = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = st

    private var map: ControllerMap? = null
    private var failsafe: FailsafeController? = null
    private var loop: Job? = null

    @Suppress("TooGenericExceptionCaught") // transports may throw anything; surface it as an Error state
    suspend fun connectAndProbe(deviceName: String = "") {
        if (st.value != SessionState.Idle && st.value !is SessionState.Error) return
        st.value = SessionState.Connecting
        try {
            transport.connect()
            st.value = SessionState.Probing
            val report = probeEngine.run(transport, deviceName)
            val m = mapFor(report)
            if (m == null) {
                st.value = SessionState.Error("No safe control map for detected protocol")
                return
            }
            map = m
            failsafe = FailsafeController(m, transport)
            st.value = SessionState.Ready(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            st.value = SessionState.Error(e.message ?: "connect/probe failed")
        }
    }

    /** Only valid from Ready; returns false otherwise. */
    fun arm(): Boolean {
        val ready = st.value as? SessionState.Ready ?: return false
        val m = map ?: return false
        val fs = failsafe ?: return false
        loop = scope.launch { ControlLoop(m, transport, fs, inputs = inputs).run() }
        st.value = SessionState.Armed(ready.report)
        return true
    }

    /** Stop sending and push neutral. Returns to Ready. Safe to call in any state. */
    suspend fun disarm() {
        val armed = st.value as? SessionState.Armed
        loop?.cancel()
        loop = null
        failsafe?.emergencyStop()
        if (armed != null) st.value = SessionState.Ready(armed.report)
    }

    suspend fun emergencyStop() = disarm()

    suspend fun close() {
        disarm()
        runCatching { transport.disconnect() }
        map = null
        failsafe = null
        st.value = SessionState.Idle
    }
}
