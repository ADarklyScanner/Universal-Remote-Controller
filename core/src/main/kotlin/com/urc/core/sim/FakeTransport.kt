package com.urc.core.sim

import com.urc.core.transport.Endpoint
import com.urc.core.transport.Transport
import com.urc.core.transport.TransportCapabilities
import com.urc.core.transport.TransportState
import com.urc.core.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * In-memory transport driven by a [respond] lambda emulating a vehicle. Used by tests and the in-app simulator.
 * [respond] receives (data, endpoint) and returns reply chunks delivered on [incoming].
 */
open class FakeTransport(
    override val type: TransportType = TransportType.SIMULATED,
    var endpoints: List<Endpoint> = emptyList(),
    var reads: Map<String, ByteArray> = emptyMap(),
    var chatter: List<ByteArray> = emptyList(),
    var respond: (ByteArray, String?) -> List<ByteArray> = { _, _ -> emptyList() },
    override val capabilities: TransportCapabilities = TransportCapabilities(type, supportsEndpoints = endpoints.isNotEmpty()),
) : Transport {
    private val st = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val state: Flow<TransportState> = st
    private var chatterSent = false

    /** Unsolicited [chatter] is delivered to the first subscriber after connect. */
    override val incoming: Flow<ByteArray> = rx.onSubscription {
        if (st.value == TransportState.Connected && !chatterSent) {
            chatterSent = true
            chatter.forEach { emit(it) }
        }
    }

    val sent = mutableListOf<Pair<String?, ByteArray>>()
    var baud: Int = 115200
    var dropped = false

    override suspend fun connect() {
        st.value = TransportState.Connected
        dropped = false
        chatterSent = false
    }

    override suspend fun disconnect() {
        st.value = TransportState.Disconnected
    }

    /** Simulate loss of link. */
    fun dropConnection() {
        dropped = true
        st.value = TransportState.Error("link lost")
    }

    override suspend fun send(data: ByteArray, endpoint: String?) {
        check(st.value == TransportState.Connected) { "not connected" }
        sent += endpoint to data
        respond(data, endpoint).forEach { rx.emit(it) }
    }

    override suspend fun enumerateEndpoints(): List<Endpoint> = endpoints
    override suspend fun read(endpointId: String): ByteArray? = reads[endpointId]
    override suspend fun setBaudRate(baud: Int): Boolean {
        this.baud = baud
        return capabilities.supportsBaudRate
    }

    suspend fun emitIncoming(data: ByteArray) = rx.emit(data)
}
