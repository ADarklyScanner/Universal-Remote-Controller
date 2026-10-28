package com.urc.core.probe

import com.urc.core.protocol.Mavlink
import com.urc.core.transport.TransportType
import kotlinx.serialization.Serializable

/** Lower = less intrusive. Anything above [READ_ONLY] needs explicit user confirmation. */
@Serializable
enum class Intrusiveness { PASSIVE, READ_ONLY, WRITE, MAY_MOVE }

class Probe(
    val id: String,
    val description: String,
    val intrusiveness: Intrusiveness,
    val transports: Set<TransportType>?,
    val endpoint: String? = null,
    val payload: ByteArray,
) {
    val readOnly get() = intrusiveness <= Intrusiveness.READ_ONLY
}

object ProbeLibrary {
    private val streams = setOf(
        TransportType.BLUETOOTH_SPP, TransportType.BLE, TransportType.TCP, TransportType.UDP,
        TransportType.USB_SERIAL, TransportType.WEBSOCKET, TransportType.SIMULATED,
    )

    private fun text(id: String, s: String, endings: String = "\n") =
        Probe("text:$id", "Send \"$s\"", Intrusiveness.READ_ONLY, streams, null, (s + endings).toByteArray())

    fun httpPaths() = listOf("/", "/status", "/api", "/info").map {
        Probe("http:GET $it", "HTTP GET $it", Intrusiveness.READ_ONLY, setOf(TransportType.HTTP), "GET $it", ByteArray(0))
    } + Probe("http:OPTIONS /", "HTTP OPTIONS /", Intrusiveness.READ_ONLY, setOf(TransportType.HTTP), "OPTIONS /", ByteArray(0))

    fun mqtt() = Probe("mqtt:#", "Subscribe to #", Intrusiveness.PASSIVE, setOf(TransportType.MQTT), "SUBSCRIBE #", ByteArray(0))

    fun mavlink() = listOf(
        Probe("mav:heartbeat", "MAVLink heartbeat", Intrusiveness.READ_ONLY, streams, null, Mavlink.heartbeatV2()),
        Probe("mav:params", "MAVLink param request list", Intrusiveness.READ_ONLY, streams, null, Mavlink.paramRequestList()),
    )

    /** Ordered from least to most intrusive. */
    fun defaults(endings: String = "\n"): List<Probe> =
        httpPaths() + mqtt() + listOf("?", "help", "version", "AT").map { text(it, it, endings) } + mavlink()

    /** Example of a probe that is NOT read-only and therefore gated. */
    fun movementProbe(payload: ByteArray, endpoint: String? = null) =
        Probe("move:custom", "User-supplied control frame (may move vehicle)", Intrusiveness.MAY_MOVE, null, endpoint, payload)
}

/** Safety gate: read-only probes pass; anything else requires explicit confirmation. */
fun interface SafetyGate {
    suspend fun confirm(probe: Probe): Boolean

    companion object {
        val DENY_ALL = SafetyGate { false }
    }
}
