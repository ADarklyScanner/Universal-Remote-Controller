package com.urc.core.mapping

import com.urc.core.probe.ProbeReport
import com.urc.core.protocol.ProtocolKind
import com.urc.core.transport.Endpoint
import com.urc.core.transport.TransportType

/** Control description before it is placed on a widget. */
data class ControlSpec(
    val role: Role,
    val label: String,
    val encoding: Encoding,
    val scaling: Scaling,
    val endpoint: String? = null,
    val telemetryField: String? = null,
)

interface ProfileTemplate {
    val id: String
    val name: String
    fun score(report: ProbeReport): Double
    fun controls(report: ProbeReport): List<ControlSpec>
}

object RoleHeuristics {
    private val rules = listOf(
        Role.THROTTLE to Regex("throttle|speed|motor|thr|accel|drive|power"),
        Role.STEERING to Regex("steer|turn|direction|servo|rudder|wheel"),
        Role.PITCH to Regex("pitch"),
        Role.ROLL to Regex("roll"),
        Role.YAW to Regex("yaw"),
        Role.LIGHTS to Regex("light|led|lamp|headlight"),
        Role.HORN to Regex("horn|buzz|beep|siren"),
        Role.ARM to Regex("arm"),
    )

    fun guess(name: String): Role {
        val n = name.lowercase()
        return rules.firstOrNull { it.second.containsMatchIn(n) }?.first ?: Role.GENERIC
    }
}

object ProfileTemplates {
    private val axis = Scaling(min = -100.0, max = 100.0)
    private val button = Scaling(min = 0.0, max = 1.0, deadzone = 0.0, neutral = 0.0, inputMin = 0.0, inputMax = 1.0)
    private val pwm = Scaling(min = 1000.0, max = 2000.0, neutral = 1500.0)
    private val streamTypes = setOf(
        TransportType.BLE, TransportType.BLUETOOTH_SPP, TransportType.USB_SERIAL, TransportType.TCP,
        TransportType.UDP, TransportType.WEBSOCKET, TransportType.SIMULATED,
    )

    private fun primaryWritable(r: ProbeReport): Endpoint? =
        r.endpoints.firstOrNull { it.writable && it.id.lowercase().startsWith("6e400002") }
            ?: r.endpoints.firstOrNull { it.writable }

    private fun asciiCar(r: ProbeReport, ep: String?, type: EncodingType = EncodingType.ASCII) = listOf(
        ControlSpec(Role.THROTTLE, "Throttle", Encoding(type, "T{int}{ending}"), axis, ep, r.fields.firstOrNull { it.name.contains("speed") }?.name),
        ControlSpec(Role.STEERING, "Steering", Encoding(type, "S{int}{ending}"), axis, ep),
        ControlSpec(Role.LIGHTS, "Lights", Encoding(type, "L{int}{ending}"), button, ep),
        ControlSpec(Role.HORN, "Horn", Encoding(type, "H{int}{ending}"), button, ep),
    )

    val bleUartCar = object : ProfileTemplate {
        override val id = "ble-uart-car"
        override val name = "Generic RC car over BLE UART"
        override fun score(report: ProbeReport) =
            if (report.transport != TransportType.BLE) 0.0
            else if (report.endpoints.any { it.id.lowercase().startsWith("6e400002") }) 0.9
            else if (report.endpoints.any { it.writable }) 0.5 else 0.0
        override fun controls(report: ProbeReport): List<ControlSpec> {
            val ep = primaryWritable(report)?.id
            return asciiCar(report, ep, EncodingType.GATT_WRITE).map { it.copy(encoding = it.encoding.copy(type = EncodingType.GATT_WRITE)) }
        }
    }

    val serialCar = object : ProfileTemplate {
        override val id = "serial-car"
        override val name = "ESP32/Arduino serial car"
        override fun score(report: ProbeReport) =
            if (report.transport in streamTypes && report.transport != TransportType.BLE &&
                report.protocol in setOf(ProtocolKind.ASCII_LINES, ProtocolKind.KEY_VALUE, ProtocolKind.UNKNOWN)
            ) 0.4 + if (report.protocol == ProtocolKind.UNKNOWN) 0.0 else 0.1 else 0.0
        override fun controls(report: ProbeReport) = asciiCar(report, null)
    }

    val httpRobot = object : ProfileTemplate {
        override val id = "http-robot"
        override val name = "HTTP-controlled robot"
        override fun score(report: ProbeReport) = if (report.transport == TransportType.HTTP) 0.7 else 0.0
        override fun controls(report: ProbeReport): List<ControlSpec> {
            val writes = report.endpoints.filter { it.writable }
            val drive = writes.firstOrNull { RoleHeuristics.guess(it.label + it.id) == Role.THROTTLE }?.id ?: writes.firstOrNull()?.id ?: "POST /api/drive"
            val light = writes.firstOrNull { RoleHeuristics.guess(it.label + it.id) == Role.LIGHTS }?.id ?: "POST /api/light"
            return listOf(
                ControlSpec(Role.THROTTLE, "Throttle", Encoding(EncodingType.HTTP_REQUEST, "{\"throttle\":{int}}"), axis, drive),
                ControlSpec(Role.STEERING, "Steering", Encoding(EncodingType.HTTP_REQUEST, "{\"steering\":{int}}"), axis, drive),
                ControlSpec(Role.LIGHTS, "Lights", Encoding(EncodingType.HTTP_REQUEST, "{\"on\":{int}}"), button, light),
            )
        }
    }

    val mavlink = object : ProfileTemplate {
        override val id = "mavlink"
        override val name = "MAVLink drone/rover"
        override fun score(report: ProbeReport) = if (report.protocol == ProtocolKind.MAVLINK) 0.95 else 0.0
        override fun controls(report: ProbeReport): List<ControlSpec> {
            fun rc(role: Role, label: String, ch: Int) =
                ControlSpec(role, label, Encoding(EncodingType.MAVLINK_RC_OVERRIDE, channel = ch), pwm)
            return listOf(rc(Role.ROLL, "Roll", 1), rc(Role.PITCH, "Pitch", 2), rc(Role.THROTTLE, "Throttle", 3), rc(Role.YAW, "Yaw", 4))
        }
    }

    val mqttVehicle = object : ProfileTemplate {
        override val id = "mqtt-vehicle"
        override val name = "MQTT vehicle"
        override fun score(report: ProbeReport) = if (report.transport == TransportType.MQTT) 0.7 else 0.0
        override fun controls(report: ProbeReport): List<ControlSpec> {
            val topics = report.endpoints.filter { it.writable }
            fun topic(role: Role, fallback: String) =
                topics.firstOrNull { RoleHeuristics.guess(it.id) == role }?.id ?: fallback
            return listOf(
                ControlSpec(Role.THROTTLE, "Throttle", Encoding(EncodingType.MQTT, "{int}"), axis, topic(Role.THROTTLE, "vehicle/throttle")),
                ControlSpec(Role.STEERING, "Steering", Encoding(EncodingType.MQTT, "{int}"), axis, topic(Role.STEERING, "vehicle/steering")),
                ControlSpec(Role.LIGHTS, "Lights", Encoding(EncodingType.MQTT, "{int}"), button, topic(Role.LIGHTS, "vehicle/lights")),
            )
        }
    }

    val all: List<ProfileTemplate> = listOf(mavlink, bleUartCar, httpRobot, mqttVehicle, serialCar)
}

class MapGenerator(private val templates: List<ProfileTemplate> = ProfileTemplates.all) {

    fun generate(report: ProbeReport, id: String = "map-" + report.createdAt): ControllerMap {
        val best = templates.map { it to it.score(report) }.filter { it.second > 0.0 }.maxByOrNull { it.second }
        val specs = best?.first?.controls(report).orEmpty().toMutableList()
        val usedEndpoints = specs.mapNotNull { it.endpoint }.toSet()
        var n = 1
        for (ep in report.endpoints.filter { it.writable && it.id !in usedEndpoints }) {
            specs += genericSpec(report.transport, ep, n++)
        }
        if (specs.isEmpty()) {
            specs += ControlSpec(Role.GENERIC, "CH1", Encoding(EncodingType.ASCII, "CH1={int}{ending}"), Scaling(-100.0, 100.0))
        }
        return build(id, best?.first, report, specs)
    }

    private fun genericSpec(t: TransportType, ep: Endpoint, n: Int): ControlSpec {
        val role = RoleHeuristics.guess(ep.label + " " + ep.id)
        val label = if (role == Role.GENERIC) "CH$n" else role.name.lowercase().replaceFirstChar { it.uppercase() }
        val type = when (t) {
            TransportType.BLE -> EncodingType.GATT_WRITE
            TransportType.HTTP -> EncodingType.HTTP_REQUEST
            TransportType.MQTT -> EncodingType.MQTT
            else -> EncodingType.HEX
        }
        val tpl = when (type) {
            EncodingType.HTTP_REQUEST -> "{\"value\":{int}}"
            EncodingType.MQTT -> "{int}"
            else -> "{hex}"
        }
        val isButton = role in setOf(Role.LIGHTS, Role.HORN, Role.ARM)
        val scaling = if (isButton) Scaling(0.0, 1.0, 0.0, 0.0, false, 0.0, 0.0, 1.0) else Scaling(-100.0, 100.0)
        return ControlSpec(role, label, Encoding(type, tpl), scaling, ep.id)
    }

    private fun build(id: String, tpl: ProfileTemplate?, report: ProbeReport, specs: List<ControlSpec>): ControllerMap {
        val widgets = linkedMapOf<String, Widget>()
        fun widget(id: String, type: InputKind, label: String, x: Float, y: Float, w: Float, h: Float) =
            widgets.getOrPut(id) { Widget(id, type, label, x, y, w, h) }

        val bindings = specs.mapIndexed { i, s ->
            val src = when (s.role) {
                Role.THROTTLE -> {
                    widget("joy-left", InputKind.JOYSTICK, "Left stick", 0.02f, 0.45f, 0.4f, 0.5f)
                    InputSource(InputKind.JOYSTICK, "joy-left", "y")
                }
                Role.STEERING, Role.YAW -> {
                    widget("joy-left", InputKind.JOYSTICK, "Left stick", 0.02f, 0.45f, 0.4f, 0.5f)
                    InputSource(InputKind.JOYSTICK, "joy-left", "x")
                }
                Role.PITCH, Role.ROLL -> {
                    widget("joy-right", InputKind.JOYSTICK, "Right stick", 0.58f, 0.45f, 0.4f, 0.5f)
                    InputSource(InputKind.JOYSTICK, "joy-right", if (s.role == Role.PITCH) "y" else "x")
                }
                Role.LIGHTS, Role.ARM -> {
                    widget("w$i", InputKind.TOGGLE, s.label, 0.45f, 0.05f + 0.15f * i.coerceAtMost(4), 0.1f, 0.12f)
                    InputSource(InputKind.TOGGLE, "w$i")
                }
                Role.HORN -> {
                    widget("w$i", InputKind.BUTTON, s.label, 0.45f, 0.05f + 0.15f * i.coerceAtMost(4), 0.1f, 0.12f)
                    InputSource(InputKind.BUTTON, "w$i")
                }
                Role.GENERIC -> {
                    widget("w$i", InputKind.SLIDER, s.label, 0.05f + 0.1f * (i % 8), 0.05f, 0.08f, 0.35f)
                    InputSource(InputKind.SLIDER, "w$i")
                }
            }
            Binding(
                id = "b$i", label = s.label, role = s.role, source = src,
                target = Target(report.transport, s.endpoint), encoding = s.encoding, scaling = s.scaling,
                telemetryField = s.telemetryField,
            )
        }
        val telemetry = report.fields.map { TelemetryField(it.name, it.name.replaceFirstChar { c -> c.uppercase() }) }
        return ControllerMap(
            id = id, name = "Auto: ${tpl?.name ?: "Generic"}", profileId = tpl?.id, lineEnding = report.lineEnding,
            widgets = widgets.values.toList(), bindings = bindings, telemetry = telemetry,
        )
    }
}
