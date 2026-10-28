package com.urc.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper

private const val SCAN_MS = 5000L
private const val USB_PERMISSION = "com.urc.app.USB_PERMISSION"

/** Runtime permissions needed for Bluetooth on this Android version. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun Activity.hasPermissions(perms: Array<String>) =
    perms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

private fun Activity.pick(title: String, labels: List<String>, onPick: (Int) -> Unit) {
    if (labels.isEmpty()) {
        AlertDialog.Builder(this).setTitle(title).setMessage("Nothing found").setPositiveButton("OK", null).show()
        return
    }
    AlertDialog.Builder(this).setTitle(title)
        .setItems(labels.toTypedArray()) { _, i -> onPick(i) }
        .setNegativeButton("Cancel", null).show()
}

@SuppressLint("MissingPermission")
private fun Activity.adapter() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

/** Paired Bluetooth Classic devices (pair in system settings first). */
@SuppressLint("MissingPermission")
fun Activity.pickPairedBluetooth(onPick: (BluetoothDevice) -> Unit) {
    val devices = adapter()?.bondedDevices.orEmpty().toList()
    pick("Paired Bluetooth devices", devices.map { "${it.name ?: "?"}  ${it.address}" }) { onPick(devices[it]) }
}

/** Scans for BLE devices for a few seconds, then lets the user choose. */
@SuppressLint("MissingPermission")
fun Activity.pickBleDevice(onPick: (BluetoothDevice) -> Unit) {
    val scanner = adapter()?.bluetoothLeScanner ?: return pick("BLE", emptyList()) {}
    val found = linkedMapOf<String, BluetoothDevice>()
    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            found[result.device.address] = result.device
        }
    }
    scanner.startScan(cb)
    Handler(Looper.getMainLooper()).postDelayed({
        scanner.stopScan(cb)
        val devices = found.values.toList()
        pick("BLE devices", devices.map { "${it.name ?: "(unnamed)"}  ${it.address}" }) { onPick(devices[it]) }
    }, SCAN_MS)
}

/** Lists attached USB devices and asks Android for permission to the chosen one. */
fun Activity.pickUsbDevice(onGranted: (UsbManager, UsbDevice) -> Unit) {
    val manager = getSystemService(Context.USB_SERVICE) as UsbManager
    val devices = manager.deviceList.values.toList()
    pick("USB devices", devices.map { "${it.productName ?: it.deviceName}  ${"%04x:%04x".format(it.vendorId, it.productId)}" }) { i ->
        val dev = devices[i]
        if (manager.hasPermission(dev)) {
            onGranted(manager, dev)
            return@pick
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) onGranted(manager, dev)
            }
        }
        val filter = IntentFilter(USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(USB_PERMISSION).setPackage(packageName), flags))
    }
}
