package com.urc.core.mapping

import com.urc.core.transport.TransportType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SCHEMA_VERSION = 1

@Serializable
enum class InputKind { JOYSTICK, SLIDER, BUTTON, TOGGLE, DPAD, TILT, GAMEPAD, KEYBOARD, GESTURE }

@Serializable
enum class EncodingType { RAW_BYTES, HEX, ASCII, JSON, GATT_WRITE, HTTP_REQUEST, MQTT, MAVLINK_RC_OVERRIDE, MAVLINK_MANUAL_CONTROL }

@Serializable
enum class Role { THROTTLE, STEERING, PITCH, ROLL, YAW, LIGHTS, HORN, ARM, GENERIC }

/** Which physical/virtual control feeds a binding. [axis] e.g. "x", "y", "AXIS_LX", "KEYCODE_W", "pitch". */
@Serializable
data class InputSource(val kind: InputKind, val widgetId: String? = null, val axis: String = "value")

@Serializable
data class Target(val transport: TransportType, val endpoint: String? = null)

/**
 * Template placeholders: {value} scaled float, {int} rounded int, {hex} 2-digit hex of int&0xFF, {byte}(raw byte, RAW only),
 * {ending} line ending. For MAVLINK types only [channel] matters (1..8; MANUAL_CONTROL: 1=x 2=y 3=z 4=r).
 */
@Serializable
data class Encoding(val type: EncodingType, val template: String = "", val channel: Int = 1)

@Serializable
data class Scaling(
    val min: Double = -100.0,
    val max: Double = 100.0,
    val deadzone: Double = 0.05,
    val expo: Double = 0.0,
    val invert: Boolean = false,
    val neutral: Double = 0.0,
    /** Input range: -1..1 for axes, 0..1 for buttons. */
    val inputMin: Double = -1.0,
    val inputMax: Double = 1.0,
)

@Serializable
data class Binding(
    val id: String,
    val label: String,
    val role: Role = Role.GENERIC,
    val source: InputSource,
    val target: Target,
    val encoding: Encoding,
    val scaling: Scaling = Scaling(),
    val rateHz: Int = 20,
    val failsafe: Double = scaling.neutral,
    /** Telemetry field expected to change when this control moves (used by learn mode). */
    val telemetryField: String? = null,
)

@Serializable
data class Widget(
    val id: String,
    val type: InputKind,
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

@Serializable
data class TelemetryField(val name: String, val label: String, val unit: String = "")

@Serializable
data class ControllerMap(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val profileId: String? = null,
    val rateHz: Int = 20,
    val lineEnding: String = "\n",
    val widgets: List<Widget> = emptyList(),
    val bindings: List<Binding> = emptyList(),
    val telemetry: List<TelemetryField> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
        fun fromJson(s: String): ControllerMap {
            val m = json.decodeFromString<ControllerMap>(s)
            require(m.schemaVersion <= SCHEMA_VERSION) { "unsupported schema ${m.schemaVersion}" }
            return m
        }
    }
}
