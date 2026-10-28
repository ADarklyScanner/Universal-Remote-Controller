package com.urc.core.probe

import com.urc.core.protocol.ProtocolKind
import com.urc.core.transport.Endpoint
import com.urc.core.transport.TransportType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FieldRange(val name: String, val min: Double, val max: Double, val samples: Int)

@Serializable
data class SampleMessage(val hex: String, val ascii: String, val source: String)

@Serializable
data class ProbeReport(
    val transport: TransportType,
    val deviceName: String = "",
    val endpoints: List<Endpoint> = emptyList(),
    val protocol: ProtocolKind = ProtocolKind.UNKNOWN,
    val protocolConfidence: Double = 0.0,
    val protocolDetail: String = "",
    val baudRate: Int? = null,
    val lineEnding: String = "\n",
    val samples: List<SampleMessage> = emptyList(),
    val fields: List<FieldRange> = emptyList(),
    val skippedProbes: List<String> = emptyList(),
    val sentProbes: List<String> = emptyList(),
    val createdAt: Long = 0,
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
        fun fromJson(s: String): ProbeReport = json.decodeFromString(s)
    }
}
