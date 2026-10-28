package com.urc.core.mapping

import com.urc.core.transport.Transport

/**
 * Sends neutral output on connection loss, backgrounding, or emergency stop. Pure logic; the Android
 * service calls [onBackgrounded]/[onLinkLost]; the watchdog calls [check] periodically.
 */
class FailsafeController(
    private val map: ControllerMap,
    private val transport: Transport,
    private val encoder: CommandEncoder = CommandEncoder(),
    private val timeoutMs: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var engaged = false
        private set
    private var lastInput = now()

    fun onInput() {
        lastInput = now()
        engaged = false
    }

    suspend fun emergencyStop() = sendNeutral()
    suspend fun onBackgrounded() = sendNeutral()
    suspend fun onLinkLost() = sendNeutral()

    /** Engage neutral output if no input received within the timeout. Returns true if newly engaged. */
    suspend fun check(): Boolean {
        if (!engaged && now() - lastInput > timeoutMs) {
            sendNeutral()
            return true
        }
        return false
    }

    private suspend fun sendNeutral() {
        engaged = true
        for (c in encoder.neutral(map)) {
            runCatching { transport.send(c.bytes, c.endpoint) } // link may already be gone
        }
    }
}
