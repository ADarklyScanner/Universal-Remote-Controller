package com.urc.core.mapping

import com.urc.core.transport.Transport
import com.urc.core.transport.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class LatencyStats {
    var count = 0L
        private set
    var lastMs = 0L
        private set
    var maxMs = 0L
        private set
    private var total = 0L
    val avgMs get() = if (count == 0L) 0.0 else total.toDouble() / count

    fun record(ms: Long) {
        count++
        lastMs = ms
        total += ms
        if (ms > maxMs) maxMs = ms
    }
}

/**
 * Periodically encodes current inputs and sends them. Runs until cancelled. On link failure it sends neutral via
 * [failsafe] and attempts reconnect with [reconnectDelayMs].
 */
class ControlLoop(
    private val map: ControllerMap,
    private val transport: Transport,
    private val failsafe: FailsafeController,
    private val encoder: CommandEncoder = CommandEncoder(),
    private val inputs: () -> Map<String, Double>,
    private val now: () -> Long = System::currentTimeMillis,
    private val reconnectDelayMs: Long = 2000,
    private val onTraffic: (OutgoingCommand) -> Unit = {},
) {
    val stats = LatencyStats()

    suspend fun run() {
        val period = 1000L / map.rateHz.coerceIn(1, 100)
        while (coroutineContext.isActive) {
            if (transport.state.first() != TransportState.Connected) {
                failsafe.onLinkLost()
                runCatching { transport.connect() }
                delay(reconnectDelayMs)
                continue
            }
            val t0 = now()
            failsafe.onInput()
            val ok = runCatching {
                for (c in encoder.encodeAll(map, inputs())) {
                    transport.send(c.bytes, c.endpoint)
                    onTraffic(c)
                }
            }.isSuccess
            if (!ok) failsafe.onLinkLost()
            stats.record(now() - t0)
            delay(period)
        }
    }
}
