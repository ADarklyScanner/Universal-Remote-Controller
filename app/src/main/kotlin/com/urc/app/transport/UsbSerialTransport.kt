package com.urc.app.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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

private const val USB_TIMEOUT_MS = 200
private const val CDC_SET_LINE_CODING = 0x20
private const val CDC_SET_CONTROL_LINE_STATE = 0x22
private const val CLASS_CDC_CONTROL = 2
private const val CLASS_CDC_DATA = 10

/**
 * USB serial over the standard CDC-ACM class (Arduino Uno R3/Leonardo/Due, ESP32-S2/S3/C3 native USB, STM32, RP2040,
 * most flight controllers). FTDI/CH340/CP210x need vendor drivers and are not supported by this transport yet.
 * USB permission must already be granted for [device].
 */
class UsbSerialTransport(private val manager: UsbManager, private val device: UsbDevice) : Transport {
    override val type = TransportType.USB_SERIAL
    override val capabilities = TransportCapabilities(TransportType.USB_SERIAL, supportsBaudRate = true)
    private val st = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val rx = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val state: Flow<TransportState> = st
    override val incoming: Flow<ByteArray> = rx
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var conn: UsbDeviceConnection? = null
    private var control: UsbInterface? = null
    private var data: UsbInterface? = null
    private var inEp: UsbEndpoint? = null
    private var outEp: UsbEndpoint? = null
    private var reader: Job? = null

    override suspend fun connect() {
        if (st.value == TransportState.Connected) return
        st.value = TransportState.Connecting
        try {
            withContext(Dispatchers.IO) { open() }
            st.value = TransportState.Connected
            setBaudRate(DEFAULT_BAUD)
            reader = scope.launch { readLoop() }
        } catch (e: IOException) {
            st.value = TransportState.Error(e.message ?: "usb open failed")
            throw e
        }
    }

    private fun open() {
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        val dataIf = interfaces.firstOrNull { it.interfaceClass == CLASS_CDC_DATA }
            ?: interfaces.firstOrNull { itf -> (0 until itf.endpointCount).any { itf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK } }
            ?: throw IOException("No CDC-ACM/bulk interface (FTDI/CH340/CP210x not supported yet)")
        val c = manager.openDevice(device) ?: throw IOException("USB permission not granted")
        val ctl = interfaces.firstOrNull { it.interfaceClass == CLASS_CDC_CONTROL }
        ctl?.let { c.claimInterface(it, true) }
        if (!c.claimInterface(dataIf, true)) {
            c.close()
            throw IOException("Could not claim USB interface")
        }
        val eps = (0 until dataIf.endpointCount).map { dataIf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        inEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        outEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        if (inEp == null || outEp == null) {
            c.close()
            throw IOException("USB bulk endpoints missing")
        }
        conn = c
        control = ctl
        data = dataIf
        // assert DTR+RTS so devices that wait for a host (Arduino native USB, ESP32-S3) start talking
        ctl?.let { c.controlTransfer(0x21, CDC_SET_CONTROL_LINE_STATE, 0x03, it.id, null, 0, USB_TIMEOUT_MS) }
    }

    private fun readLoop() {
        val c = conn ?: return
        val ep = inEp ?: return
        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
        while (scope.isActive && conn === c) {
            val n = c.bulkTransfer(ep, buf, buf.size, USB_TIMEOUT_MS)
            when {
                n > 0 -> rx.tryEmit(buf.copyOf(n))
                n < 0 && !manager.deviceList.containsValue(device) -> {
                    if (st.value == TransportState.Connected) st.value = TransportState.Error("USB device removed")
                    return
                }
            }
        }
    }

    override suspend fun setBaudRate(baud: Int): Boolean {
        val c = conn ?: return false
        val ctl = control ?: return false
        val line = byteArrayOf(
            baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(),
            0, 0, 8, // 1 stop bit, no parity, 8 data bits
        )
        return withContext(Dispatchers.IO) {
            c.controlTransfer(0x21, CDC_SET_LINE_CODING, 0, ctl.id, line, line.size, USB_TIMEOUT_MS) >= 0
        }
    }

    override suspend fun send(data: ByteArray, endpoint: String?) {
        val c = checkNotNull(conn) { "not connected" }
        val ep = checkNotNull(outEp)
        withContext(Dispatchers.IO) {
            val n = c.bulkTransfer(ep, data, data.size, USB_TIMEOUT_MS * 5)
            if (n < 0) throw IOException("USB write failed")
        }
    }

    override suspend fun disconnect() {
        reader?.cancel()
        val c = conn
        conn = null
        withContext(Dispatchers.IO) {
            control?.let { runCatching { c?.releaseInterface(it) } }
            data?.let { runCatching { c?.releaseInterface(it) } }
            runCatching { c?.close() }
        }
        st.value = TransportState.Disconnected
    }

    private companion object {
        const val DEFAULT_BAUD = 115200
    }
}
