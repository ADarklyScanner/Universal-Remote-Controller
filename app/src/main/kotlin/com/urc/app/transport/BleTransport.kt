package com.urc.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.urc.core.transport.Endpoint
import com.urc.core.transport.Transport
import com.urc.core.transport.TransportCapabilities
import com.urc.core.transport.TransportState
import com.urc.core.transport.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val BLE_TIMEOUT_MS = 8000L
private const val BLE_MTU = 185
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE GATT transport. Endpoints are characteristics (id = characteristic UUID). Notifiable characteristics are
 * subscribed on connect and delivered on [incoming]. [send] writes to `endpoint` or, if null, the first writable
 * characteristic (preferring the Nordic UART RX characteristic 6e400002).
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : Transport {
    override val type = TransportType.BLE
    override val capabilities = TransportCapabilities(TransportType.BLE, supportsEndpoints = true, isStream = false)
    private val st = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val state: Flow<TransportState> = st
    override val incoming: Flow<ByteArray> = rx

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var connected: CompletableDeferred<Unit>? = null
    private var pending: CompletableDeferred<Int>? = null
    private var pendingRead: CompletableDeferred<ByteArray?>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestMtu(BLE_MTU)
            } else {
                if (st.value == TransportState.Connected) st.value = TransportState.Error("link lost")
                connected?.completeExceptionally(java.io.IOException("GATT connect failed ($status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) connected?.complete(Unit)
            else connected?.completeExceptionally(java.io.IOException("service discovery failed ($status)"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { rx.tryEmit(it.copyOf()) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }
    }

    override suspend fun connect() {
        if (st.value == TransportState.Connected) return
        st.value = TransportState.Connecting
        val ready = CompletableDeferred<Unit>()
        connected = ready
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            withTimeout(BLE_TIMEOUT_MS) { ready.await() }
            subscribeAll()
            st.value = TransportState.Connected
        } catch (e: Exception) {
            runCatching { gatt?.close() }
            gatt = null
            st.value = TransportState.Error(e.message ?: "connect failed")
            throw e
        }
    }

    private suspend fun subscribeAll() {
        val g = checkNotNull(gatt)
        for (c in characteristics()) {
            if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) continue
            val cccd = c.getDescriptor(CCCD) ?: continue
            g.setCharacteristicNotification(c, true)
            opLock.withLock {
                val done = CompletableDeferred<Int>().also { pending = it }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (g.writeDescriptor(cccd)) runCatching { withTimeout(BLE_TIMEOUT_MS) { done.await() } }
            }
        }
    }

    private fun characteristics() = gatt?.services.orEmpty().flatMap { it.characteristics }

    override suspend fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        st.value = TransportState.Disconnected
    }

    override suspend fun enumerateEndpoints(): List<Endpoint> = characteristics().map { c ->
        val p = c.properties
        Endpoint(
            id = c.uuid.toString(),
            kind = "gatt",
            label = c.service?.uuid?.toString().orEmpty(),
            readable = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
            writable = p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
            notifiable = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
        )
    }

    override suspend fun read(endpointId: String): ByteArray? {
        val g = gatt ?: return null
        val c = characteristics().firstOrNull { it.uuid.toString().equals(endpointId, true) } ?: return null
        return opLock.withLock {
            val done = CompletableDeferred<ByteArray?>().also { pendingRead = it }
            if (!g.readCharacteristic(c)) null else runCatching { withTimeout(BLE_TIMEOUT_MS) { done.await() } }.getOrNull()
        }
    }

    override suspend fun send(data: ByteArray, endpoint: String?) {
        val g = checkNotNull(gatt) { "not connected" }
        val writable = characteristics().filter {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        val c = (
            if (endpoint != null) writable.firstOrNull { it.uuid.toString().equals(endpoint, true) }
            else writable.firstOrNull { it.uuid.toString().startsWith("6e400002", true) } ?: writable.firstOrNull()
            ) ?: error("no writable characteristic")
        opLock.withLock {
            val done = CompletableDeferred<Int>().also { pending = it }
            c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            c.value = data
            check(g.writeCharacteristic(c)) { "write rejected" }
            if (c.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                withTimeout(BLE_TIMEOUT_MS) { done.await() }
            }
        }
    }
}
