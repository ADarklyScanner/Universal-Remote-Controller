package com.urc.core.probe

import com.urc.core.protocol.FrameClassifier
import com.urc.core.protocol.Hex
import com.urc.core.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

data class ProbeOptions(
    val passiveMs: Long = 1500,
    val probeTimeoutMs: Long = 800,
    val minIntervalMs: Long = 200,
    val baudCandidates: List<Int> = listOf(115200, 9600, 57600, 38400, 19200, 230400),
    val probes: List<Probe>? = null,
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * Interrogates a connected [Transport]: passive listen -> endpoint enumeration/reads -> rate-limited active probes
 * (least to most intrusive) -> analysis into a [ProbeReport].
 */
class ProbeEngine(
    private val gate: SafetyGate = SafetyGate.DENY_ALL,
    private val options: ProbeOptions = ProbeOptions(),
    private val onProgress: (String) -> Unit = {},
) {
    private class Capture(val source: String, val data: ByteArray)

    suspend fun run(transport: Transport, deviceName: String = ""): ProbeReport {
        val captured = CopyOnWriteArrayList<Capture>()
        var phase = "passive"
        val job = collector(transport, captured) { phase }
        try {
            var baud: Int? = null
            if (transport.capabilities.supportsBaudRate) baud = detectBaud(transport, captured)

            onProgress("Passive listening")
            delay(options.passiveMs)

            phase = "endpoints"
            var endpoints = emptyList<com.urc.core.transport.Endpoint>()
            if (transport.capabilities.supportsEndpoints) {
                onProgress("Enumerating endpoints")
                endpoints = transport.enumerateEndpoints().map { ep ->
                    if (ep.readable) {
                        val v = runCatching { transport.read(ep.id) }.getOrNull()
                        if (v != null) {
                            captured += Capture("read:${ep.id}", v)
                            ep.copy(value = String(v, Charsets.ISO_8859_1).filter { it in ' '..'~' })
                        } else {
                            ep
                        }
                    } else {
                        ep
                    }
                }
            }

            val ending = FrameClassifier.lineEnding(captured.map { it.data })
            val sent = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val probes = (options.probes ?: ProbeLibrary.defaults(ending))
                .filter { it.transports == null || transport.type in it.transports }
                .sortedBy { it.intrusiveness }
            for (p in probes) {
                if (!p.readOnly && !gate.confirm(p)) {
                    skipped += p.id
                    continue
                }
                onProgress(p.description)
                phase = "probe:${p.id}"
                val failure = runCatching { transport.send(p.payload, p.endpoint) }.exceptionOrNull()
                if (failure != null) {
                    skipped += "${p.id} (${failure.message})"
                    continue
                }
                sent += p.id
                delay(options.probeTimeoutMs)
                delay(options.minIntervalMs)
            }
            phase = "done"
            return analyse(transport, deviceName, endpoints, captured, baud, ending, sent, skipped)
        } finally {
            job.cancel()
        }
    }

    private fun collector(transport: Transport, into: MutableList<Capture>, phase: () -> String): Job =
        CoroutineScope(Dispatchers.Unconfined).launch {
            transport.incoming.collect { into += Capture(phase(), it) }
        }

    private suspend fun detectBaud(transport: Transport, captured: MutableList<Capture>): Int? {
        var best: Int? = null
        var bestScore = 0.0
        for (b in options.baudCandidates) {
            if (!transport.setBaudRate(b)) return null
            val before = captured.size
            delay(options.passiveMs / 2)
            val data = captured.drop(before).flatMap { it.data.toList() }.toByteArray()
            val score = if (data.size >= 4) FrameClassifier.printableRatio(data) else 0.0
            if (score > bestScore) {
                bestScore = score
                best = b
            }
            if (score > 0.95) break
        }
        best?.let { transport.setBaudRate(it) }
        return best
    }

    private fun analyse(
        transport: Transport,
        name: String,
        endpoints: List<com.urc.core.transport.Endpoint>,
        captured: List<Capture>,
        baud: Int?,
        ending: String,
        sent: List<String>,
        skipped: List<String>,
    ): ProbeReport {
        val datas = captured.map { it.data }
        val passive = captured.filter { it.source == "passive" }.map { it.data }
        val det = FrameClassifier.classify(passive.ifEmpty { datas })
        val fields = FrameClassifier.extractFields(datas).map { (k, v) -> FieldRange(k, v.min(), v.max(), v.size) }
        val samples = captured.take(50).map { SampleMessage(Hex.encode(it.data), Hex.ascii(it.data), it.source) }
        return ProbeReport(
            transport = transport.type, deviceName = name, endpoints = endpoints, protocol = det.kind,
            protocolConfidence = det.confidence, protocolDetail = det.detail, baudRate = baud, lineEnding = ending,
            samples = samples, fields = fields, skippedProbes = skipped, sentProbes = sent, createdAt = options.clock(),
        )
    }
}
