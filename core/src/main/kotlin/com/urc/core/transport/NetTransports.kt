package com.urc.core.transport

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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private const val CONNECT_TIMEOUT_MS = 3000
private const val READ_BUFFER = 2048

/** Raw TCP stream transport (Wi-Fi robots/cars, ESP bridges, MAVLink TCP). */
class TcpTransport(private val host: String, private val port: Int) : Transport {
    override val type = TransportType.TCP
    override val capabilities = TransportCapabilities(TransportType.TCP)
    private val st = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val state: Flow<TransportState> = st
    override val incoming: Flow<ByteArray> = rx
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var reader: Job? = null

    override suspend fun connect() {
        if (st.value == TransportState.Connected) return
        st.value = TransportState.Connecting
        try {
            val s = withContext(Dispatchers.IO) {
                Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            }
            socket = s
            st.value = TransportState.Connected
            reader = scope.launch {
                val buf = ByteArray(READ_BUFFER)
                try {
                    while (isActive) {
                        val n = s.getInputStream().read(buf)
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
        withContext(Dispatchers.IO) {
            s.getOutputStream().apply { write(data); flush() }
        }
    }
}

/** UDP transport to a single remote host:port. */
class UdpTransport(private val host: String, private val port: Int) : Transport {
    override val type = TransportType.UDP
    override val capabilities = TransportCapabilities(TransportType.UDP, isStream = false)
    private val st = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val state: Flow<TransportState> = st
    override val incoming: Flow<ByteArray> = rx
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var reader: Job? = null
    private var target: InetAddress? = null

    override suspend fun connect() {
        if (st.value == TransportState.Connected) return
        st.value = TransportState.Connecting
        try {
            val s = withContext(Dispatchers.IO) {
                target = InetAddress.getByName(host)
                DatagramSocket()
            }
            socket = s
            st.value = TransportState.Connected
            reader = scope.launch {
                val buf = ByteArray(READ_BUFFER)
                try {
                    while (isActive) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        rx.emit(buf.copyOf(p.length))
                    }
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
        socket?.close()
        socket = null
        st.value = TransportState.Disconnected
    }

    override suspend fun send(data: ByteArray, endpoint: String?) {
        val s = checkNotNull(socket) { "not connected" }
        val t = checkNotNull(target)
        withContext(Dispatchers.IO) { s.send(DatagramPacket(data, data.size, t, port)) }
    }
}
