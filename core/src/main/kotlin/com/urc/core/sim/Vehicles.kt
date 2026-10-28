package com.urc.core.sim

import com.urc.core.protocol.Mavlink
import com.urc.core.transport.Endpoint
import com.urc.core.transport.TransportType

/** Simulated vehicles for integration tests and demo mode. */
object Vehicles {
    const val UART_SERVICE_TX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val UART_SERVICE_RX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /** BLE UART car: answers `?`/`version`, accepts ASCII drive commands, streams a battery line. */
    fun bleUartCar() = FakeTransport(
        type = TransportType.BLE,
        endpoints = listOf(
            Endpoint(UART_SERVICE_TX, "gatt-characteristic", "Nordic UART TX", writable = true),
            Endpoint(UART_SERVICE_RX, "gatt-characteristic", "Nordic UART RX", notifiable = true),
            Endpoint("2a00", "gatt-characteristic", "Device Name", readable = true),
        ),
        reads = mapOf("2a00" to "RCCar-BLE".toByteArray()),
        chatter = listOf("batt:7.4,rssi:-50\n".toByteArray(), "batt:7.3,rssi:-52\n".toByteArray()),
        respond = { d, _ ->
            when (String(d).trim()) {
                "?", "help" -> listOf("RCCar v1.2 commands: T<n> S<n>\n".toByteArray())
                "version" -> listOf("RCCar v1.2\n".toByteArray())
                else -> emptyList()
            }
        },
    )

    /** HTTP robot exposing /status, /api. */
    fun httpRobot() = FakeTransport(
        type = TransportType.HTTP,
        endpoints = listOf(
            Endpoint("POST /api/drive", "http", "drive", writable = true),
            Endpoint("POST /api/light", "http", "light", writable = true),
        ),
        respond = { _, ep ->
            when (ep) {
                "GET /status" -> listOf("""{"battery":87,"speed":0.0,"rssi":-60}""".toByteArray())
                "GET /api" -> listOf("""{"endpoints":["/api/drive","/api/light"]}""".toByteArray())
                else -> emptyList()
            }
        },
    )

    /** MAVLink heartbeat source (v2 frames, 1 Hz style burst on connect). */
    fun mavlinkDrone(): FakeTransport {
        val hb = Mavlink.heartbeatV2(system = 1, component = 1, type = 2, autopilot = 3)
        return FakeTransport(type = TransportType.UDP, chatter = listOf(hb, hb), respond = { d, _ ->
            if (Mavlink.parse(d).any { it.messageId == 0 }) listOf(hb) else emptyList()
        })
    }
}
