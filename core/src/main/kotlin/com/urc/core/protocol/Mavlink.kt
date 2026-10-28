package com.urc.core.protocol

data class MavMessage(
    val version: Int,
    val messageId: Int,
    val system: Int,
    val component: Int,
    val payload: ByteArray,
    val crcValid: Boolean,
) {
    val isHeartbeat get() = messageId == Mavlink.MSG_HEARTBEAT
}

data class Heartbeat(val type: Int, val autopilot: Int, val baseMode: Int, val customMode: Long, val systemStatus: Int)

/** Minimal MAVLink v1/v2 codec: framing, CRC, heartbeat, RC_CHANNELS_OVERRIDE, MANUAL_CONTROL. */
object Mavlink {
    const val MSG_HEARTBEAT = 0
    const val MSG_PARAM_REQUEST_LIST = 21
    const val MSG_MANUAL_CONTROL = 69
    const val MSG_RC_CHANNELS_OVERRIDE = 70
    private const val STX_V1 = 0xFE
    private const val STX_V2 = 0xFD

    private val crcExtra = mapOf(
        MSG_HEARTBEAT to 50,
        MSG_PARAM_REQUEST_LIST to 159,
        MSG_MANUAL_CONTROL to 243,
        MSG_RC_CHANNELS_OVERRIDE to 124,
    )

    private fun crcAccumulate(b: Int, crc: Int): Int {
        var tmp = (b xor (crc and 0xFF)) and 0xFF
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    private fun crc(data: ByteArray, from: Int, to: Int, extra: Int): Int {
        var c = 0xFFFF
        for (i in from until to) c = crcAccumulate(data[i].toInt() and 0xFF, c)
        return crcAccumulate(extra, c)
    }

    /** Parse all complete frames found in [data] (resynchronises on garbage). */
    fun parse(data: ByteArray): List<MavMessage> {
        val out = mutableListOf<MavMessage>()
        var i = 0
        while (i < data.size) {
            val stx = data[i].toInt() and 0xFF
            val msg = when (stx) {
                STX_V1 -> parseV1(data, i)
                STX_V2 -> parseV2(data, i)
                else -> null
            }
            if (msg != null) {
                out += msg.first
                i += msg.second
            } else {
                i++
            }
        }
        return out
    }

    private fun parseV1(d: ByteArray, s: Int): Pair<MavMessage, Int>? {
        if (s + 8 > d.size) return null
        val len = d[s + 1].toInt() and 0xFF
        val total = 8 + len
        if (s + total > d.size) return null
        val id = d[s + 5].toInt() and 0xFF
        val extra = crcExtra[id]
        val got = (d[s + total - 2].toInt() and 0xFF) or ((d[s + total - 1].toInt() and 0xFF) shl 8)
        val ok = extra != null && crc(d, s + 1, s + 6 + len, extra) == got
        if (extra != null && !ok) return null
        // unknown message ids cannot be verified; require plausible header
        if (extra == null && (d[s + 3].toInt() == 0 || len > 255)) return null
        return MavMessage(1, id, d[s + 3].toInt() and 0xFF, d[s + 4].toInt() and 0xFF, d.copyOfRange(s + 6, s + 6 + len), ok) to total
    }

    private fun parseV2(d: ByteArray, s: Int): Pair<MavMessage, Int>? {
        if (s + 12 > d.size) return null
        val len = d[s + 1].toInt() and 0xFF
        val signed = (d[s + 2].toInt() and 0x01) != 0
        val total = 12 + len + if (signed) 13 else 0
        if (s + total > d.size) return null
        val id = (d[s + 7].toInt() and 0xFF) or ((d[s + 8].toInt() and 0xFF) shl 8) or ((d[s + 9].toInt() and 0xFF) shl 16)
        val extra = crcExtra[id]
        val crcPos = s + 10 + len
        val got = (d[crcPos].toInt() and 0xFF) or ((d[crcPos + 1].toInt() and 0xFF) shl 8)
        val ok = extra != null && crc(d, s + 1, crcPos, extra) == got
        if (extra != null && !ok) return null
        if (extra == null && d[s + 5].toInt() == 0) return null
        return MavMessage(2, id, d[s + 5].toInt() and 0xFF, d[s + 6].toInt() and 0xFF, d.copyOfRange(s + 10, crcPos), ok) to total
    }

    fun decodeHeartbeat(m: MavMessage): Heartbeat? {
        if (!m.isHeartbeat) return null
        val p = m.payload.copyOf(9)
        val custom = (0..3).fold(0L) { acc, k -> acc or ((p[k].toLong() and 0xFF) shl (8 * k)) }
        return Heartbeat(p[4].toInt() and 0xFF, p[5].toInt() and 0xFF, p[6].toInt() and 0xFF, custom, p[7].toInt() and 0xFF)
    }

    private fun heartbeatPayload(type: Int, autopilot: Int) =
        byteArrayOf(0, 0, 0, 0, type.toByte(), autopilot.toByte(), 0, 4, 3)

    fun heartbeatV1(system: Int = 255, component: Int = 190, type: Int = 6, autopilot: Int = 8, seq: Int = 0): ByteArray =
        frameV1(MSG_HEARTBEAT, system, component, seq, heartbeatPayload(type, autopilot))

    fun heartbeatV2(system: Int = 255, component: Int = 190, type: Int = 6, autopilot: Int = 8, seq: Int = 0): ByteArray =
        frameV2(MSG_HEARTBEAT, system, component, seq, heartbeatPayload(type, autopilot))

    fun paramRequestList(targetSystem: Int = 1, targetComponent: Int = 1): ByteArray =
        frameV2(MSG_PARAM_REQUEST_LIST, 255, 190, 0, byteArrayOf(targetSystem.toByte(), targetComponent.toByte()))

    /** [channels] values in microseconds (0 = ignore); up to 8 used. */
    fun rcChannelsOverride(targetSystem: Int, targetComponent: Int, channels: IntArray, seq: Int = 0): ByteArray {
        val p = ByteArray(18)
        for (k in 0 until 8) {
            val v = channels.getOrElse(k) { 0 }.coerceIn(0, 0xFFFF)
            p[2 * k] = v.toByte()
            p[2 * k + 1] = (v shr 8).toByte()
        }
        p[16] = targetSystem.toByte()
        p[17] = targetComponent.toByte()
        return frameV2(MSG_RC_CHANNELS_OVERRIDE, 255, 190, seq, p)
    }

    /** x,y,z,r in -1000..1000. */
    fun manualControl(target: Int, x: Int, y: Int, z: Int, r: Int, buttons: Int = 0, seq: Int = 0): ByteArray {
        val p = ByteArray(11)
        listOf(x, y, z, r, buttons).forEachIndexed { k, v ->
            p[2 * k] = v.toByte()
            p[2 * k + 1] = (v shr 8).toByte()
        }
        p[10] = target.toByte()
        return frameV2(MSG_MANUAL_CONTROL, 255, 190, seq, p)
    }

    fun frameV1(id: Int, sys: Int, comp: Int, seq: Int, payload: ByteArray): ByteArray {
        val f = ByteArray(8 + payload.size)
        f[0] = STX_V1.toByte()
        f[1] = payload.size.toByte()
        f[2] = seq.toByte()
        f[3] = sys.toByte()
        f[4] = comp.toByte()
        f[5] = id.toByte()
        payload.copyInto(f, 6)
        val c = crc(f, 1, 6 + payload.size, crcExtra.getValue(id))
        f[6 + payload.size] = c.toByte()
        f[7 + payload.size] = (c shr 8).toByte()
        return f
    }

    fun frameV2(id: Int, sys: Int, comp: Int, seq: Int, payloadFull: ByteArray): ByteArray {
        var len = payloadFull.size
        while (len > 1 && payloadFull[len - 1].toInt() == 0) len--
        val f = ByteArray(12 + len)
        f[0] = STX_V2.toByte()
        f[1] = len.toByte()
        f[4] = seq.toByte()
        f[5] = sys.toByte()
        f[6] = comp.toByte()
        f[7] = id.toByte()
        f[8] = (id shr 8).toByte()
        f[9] = (id shr 16).toByte()
        payloadFull.copyInto(f, 10, 0, len)
        val c = crc(f, 1, 10 + len, crcExtra.getValue(id))
        f[10 + len] = c.toByte()
        f[11 + len] = (c shr 8).toByte()
        return f
    }
}
