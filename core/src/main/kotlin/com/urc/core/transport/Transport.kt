package com.urc.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class TransportType {
    BLUETOOTH_SPP, BLE, TCP, UDP, HTTP, WEBSOCKET, MQTT, USB_SERIAL, INFRARED, AUDIO, NFC, SIMULATED
}

sealed interface TransportState {
    data object Disconnected : TransportState
    data object Connecting : TransportState
    data object Connected : TransportState
    data class Error(val message: String) : TransportState
}

/** An addressable thing on the remote side: GATT characteristic, HTTP path, MQTT topic, ... */
@Serializable
data class Endpoint(
    val id: String,
    val kind: String,
    val label: String = "",
    val readable: Boolean = false,
    val writable: Boolean = false,
    val notifiable: Boolean = false,
    val value: String? = null,
)

data class TransportCapabilities(
    val type: TransportType,
    val canReceive: Boolean = true,
    val supportsEndpoints: Boolean = false,
    val supportsBaudRate: Boolean = false,
    val isStream: Boolean = true,
)

/** Address in `endpoint` is transport specific (GATT char UUID, "GET /status", MQTT topic, ...). */
interface Transport {
    val type: TransportType
    val capabilities: TransportCapabilities
    val state: Flow<TransportState>
    val incoming: Flow<ByteArray>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(data: ByteArray, endpoint: String? = null)

    /** Enumerate remote endpoints (GATT services, MQTT topics seen, ...). */
    suspend fun enumerateEndpoints(): List<Endpoint> = emptyList()

    /** Read a value from an endpoint if supported. */
    suspend fun read(endpointId: String): ByteArray? = null

    /** Change serial parameters; return false if unsupported. */
    suspend fun setBaudRate(baud: Int): Boolean = false
}
