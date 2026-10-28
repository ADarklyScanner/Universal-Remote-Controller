package com.urc.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@Serializable
enum class ProtocolKind { UNKNOWN, ASCII_LINES, JSON, KEY_VALUE, NMEA, MAVLINK, BINARY_FIXED, BINARY }

data class Detection(val kind: ProtocolKind, val confidence: Double, val detail: String = "")

/** Heuristics classifying captured traffic. */
object FrameClassifier {
    private val nmea = Regex("^\\$[A-Z]{2}[A-Z0-9]{3},.*")
    private val keyValue = Regex("([A-Za-z_][\\w]*)\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)")

    fun classify(samples: List<ByteArray>): Detection {
        val nonEmpty = samples.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Detection(ProtocolKind.UNKNOWN, 0.0)
        val mav = maxOf(nonEmpty.count { Mavlink.parse(it).isNotEmpty() }, Mavlink.parse(join(nonEmpty)).size)
        if (mav > 0) return Detection(ProtocolKind.MAVLINK, (0.6 + 0.2 * mav).coerceAtMost(0.99), "MAVLink frames with valid CRC")
        val text = nonEmpty.map { String(it, Charsets.ISO_8859_1) }
        val printable = text.all { s -> s.all { it == '\n' || it == '\r' || it == '\t' || it in ' '..'~' } }
        if (printable) {
            val lines = textLines(nonEmpty)
            if (lines.isNotEmpty()) {
                val jsonCount = lines.count { parseJson(it) != null }
                if (jsonCount == lines.size) return Detection(ProtocolKind.JSON, 0.9, "JSON objects")
                if (lines.all { nmea.matches(it) }) return Detection(ProtocolKind.NMEA, 0.9, "NMEA-like sentences")
                if (lines.count { keyValue.containsMatchIn(it) } * 2 >= lines.size) {
                    return Detection(ProtocolKind.KEY_VALUE, 0.8, "key:value telemetry")
                }
                val terminated = text.any { it.contains('\n') }
                return Detection(ProtocolKind.ASCII_LINES, if (terminated) 0.7 else 0.4, "ASCII text")
            }
        }
        val sizes = nonEmpty.map { it.size }.toSet()
        if (nonEmpty.size >= 2 && sizes.size == 1) {
            return Detection(ProtocolKind.BINARY_FIXED, 0.6, "fixed length ${sizes.first()} bytes")
        }
        return Detection(ProtocolKind.BINARY, 0.3)
    }

    /**
     * Lines from captured chunks. A chunk that does not end in a line break and is not itself a complete JSON object is
     * treated as continued by the next chunk (serial/BLE reads often split lines).
     */
    private fun textLines(chunks: List<ByteArray>): List<String> {
        val lines = mutableListOf<String>()
        val pending = StringBuilder()
        fun flush() {
            pending.toString().split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.let { lines += it }
            pending.clear()
        }
        for (c in chunks) {
            pending.append(String(c, Charsets.ISO_8859_1))
            val whole = pending.toString()
            if (whole.endsWith('\n') || whole.endsWith('\r') || parseJson(whole.trim()) != null) flush()
        }
        flush()
        return lines
    }

    private fun join(chunks: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Fraction of [data] covered by valid MAVLink frames, 0.0..1.0. Used to detect baud on binary links. */
    fun mavlinkScore(data: ByteArray): Double =
        if (data.isEmpty()) 0.0 else (Mavlink.parse(data).size * MIN_MAV_FRAME).toDouble().div(data.size).coerceAtMost(1.0)

    private const val MIN_MAV_FRAME = 12

    fun parseJson(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()

    /** Extract numeric fields from JSON or key:value lines. */
    fun extractFields(samples: List<ByteArray>): Map<String, List<Double>> {
        val acc = linkedMapOf<String, MutableList<Double>>()
        run {
            for (line in textLines(samples)) {
                val obj = parseJson(line)
                if (obj != null) {
                    flatten("", obj, acc)
                } else {
                    keyValue.findAll(line).forEach { m ->
                        acc.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].toDouble())
                    }
                }
            }
        }
        return acc
    }

    private fun flatten(prefix: String, obj: JsonObject, acc: MutableMap<String, MutableList<Double>>) {
        for ((k, v) in obj) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            when (v) {
                is JsonObject -> flatten(key, v, acc)
                is JsonPrimitive -> v.doubleOrNull?.let { acc.getOrPut(key) { mutableListOf() }.add(it) }
                else -> Unit
            }
        }
    }

    /** Guess line ending from a capture. */
    fun lineEnding(samples: List<ByteArray>): String {
        val t = samples.joinToString("") { String(it, Charsets.ISO_8859_1) }
        return when {
            t.contains("\r\n") -> "\r\n"
            t.contains('\n') -> "\n"
            t.contains('\r') -> "\r"
            else -> "\n"
        }
    }

    fun printableRatio(data: ByteArray): Double =
        if (data.isEmpty()) 0.0 else data.count { val c = it.toInt() and 0xFF; c == 10 || c == 13 || c in 32..126 }.toDouble() / data.size
}
