package com.urc.core.mapping

import com.urc.core.protocol.Hex
import com.urc.core.protocol.Mavlink
import kotlin.math.roundToInt

class OutgoingCommand(val bindingId: String?, val endpoint: String?, val bytes: ByteArray)

class CommandEncoder(private val system: Int = 1, private val component: Int = 1) {
    private var seq = 0

    /** [values] holds raw input per binding id; missing bindings use their failsafe/neutral value. */
    fun encodeAll(map: ControllerMap, values: Map<String, Double>): List<OutgoingCommand> =
        encodeScaled(map, map.bindings.associate { it.id to scaled(it, values[it.id]) })

    /** Neutral output for every binding (failsafe). */
    fun neutral(map: ControllerMap): List<OutgoingCommand> =
        encodeScaled(map, map.bindings.associate { it.id to it.failsafe })

    private fun scaled(b: Binding, v: Double?) = if (v == null) b.failsafe else ValueScaler.scale(v, b.scaling)

    private fun encodeScaled(map: ControllerMap, out: Map<String, Double>): List<OutgoingCommand> {
        val cmds = mutableListOf<OutgoingCommand>()
        val rc = IntArray(8)
        val mc = IntArray(4)
        var rcTarget: Binding? = null
        var mcTarget: Binding? = null
        for (b in map.bindings) {
            val v = out.getValue(b.id)
            when (b.encoding.type) {
                EncodingType.MAVLINK_RC_OVERRIDE -> {
                    rc[(b.encoding.channel - 1).coerceIn(0, 7)] = v.roundToInt()
                    rcTarget = rcTarget ?: b
                }
                EncodingType.MAVLINK_MANUAL_CONTROL -> {
                    mc[(b.encoding.channel - 1).coerceIn(0, 3)] = v.roundToInt()
                    mcTarget = mcTarget ?: b
                }
                else -> cmds += OutgoingCommand(b.id, endpointFor(b, v), bytesFor(b, v, map.lineEnding))
            }
        }
        rcTarget?.let { cmds += OutgoingCommand(it.id, it.target.endpoint, Mavlink.rcChannelsOverride(system, component, rc, seq++)) }
        mcTarget?.let {
            cmds += OutgoingCommand(it.id, it.target.endpoint, Mavlink.manualControl(system, mc[0], mc[1], mc[2], mc[3], 0, seq++))
        }
        return cmds
    }

    private fun fill(t: String, v: Double, ending: String): String {
        val i = v.roundToInt()
        return t.replace("{value}", "%.3f".format(java.util.Locale.ROOT, v))
            .replace("{int}", i.toString())
            .replace("{hex}", "%02X".format(i and 0xFF))
            .replace("{ending}", ending)
    }

    private fun endpointFor(b: Binding, v: Double) = b.target.endpoint?.let { fill(it, v, "") }

    private fun bytesFor(b: Binding, v: Double, ending: String): ByteArray {
        val e = b.encoding
        return when (e.type) {
            EncodingType.HEX -> Hex.decode(fill(e.template, v, ending))
            EncodingType.RAW_BYTES -> byteArrayOf((v.roundToInt() and 0xFF).toByte())
            else -> fill(e.template, v, ending).toByteArray()
        }
    }
}
