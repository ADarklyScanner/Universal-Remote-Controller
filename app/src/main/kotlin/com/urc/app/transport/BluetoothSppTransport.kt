package com.urc.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.urc.core.transport.Transport
import com.urc.core.transport.TransportCapabilities
import com.urc.core.transport.TransportState
import com.urc.core.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Bluetooth Classic serial (RFCOMM/SPP, e.g. HC-05/HC-06, ESP32 BluetoothSerial). Needs BLUETOOTH_CONNECT granted. */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(private val device: BluetoothDevice) : Transport {
    override val type = TransportType.BLUETOOTH_SPP
    override val capabilities = TransportCapabilities(TransportType.BLUETOOTH_SPP)
    private val st = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val state: Flow<TransportState> = st
    override val incoming: Flow<ByteArray> = rx
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null

    override suspend fun connect() {
        if (st.value == TransportState.Connected) return
        st.value = TransportState.Connecting
        try {
            val s = withContext(Dispatchers.IO) {
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            }
            socket = s
            st.value = TransportState.Connected
            reader = scope.launch {
                val buf = ByteArray(1024)
                try {
                    while (isActive) {
                        val n = s.inputStream.read(buf)
                        if (n < 0) break
                        rx.emit(buf.copyOf(n))
                    }
                    if (st.value == TransportState.Connected) st.value = TransportState.Disconnected
                } catch (e: IOException) {
                    if (st.value == TransportState.Connected) st.value = TransportState.Error(e.message ?: "io")
                }
            }
        } catch (e: IOException) {
            st.value = TransportState.Error(e.message ?: "connect failed")
            throw e
        }
    }

    override suspend fun disconnect() {
        reader?.cancel()
        withContext(Dispatchers.IO) { runCatching { socket?.close() } }
        socket = null
        st.value = TransportState.Disconnected
    }

    override suspend fun send(data: ByteArray, endpoint: String?) {
        val s = checkNotNull(socket) { "not connected" }
        withContext(Dispatchers.IO) { s.outputStream.apply { write(data); flush() } }
    }
}
