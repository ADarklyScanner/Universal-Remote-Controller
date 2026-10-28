package com.urc.core.mapping

import kotlin.math.abs

/** Learn mode: user moves a control while we observe telemetry; the field that changed most is proposed for the binding. */
object LearnMode {
    fun detectChangedField(before: Map<String, Double>, after: Map<String, Double>, minDelta: Double = 0.01): String? =
        after.filterKeys { it in before }
            .mapValues { abs(it.value - before.getValue(it.key)) }
            .filter { it.value > minDelta }
            .maxByOrNull { it.value }?.key

    fun confirm(map: ControllerMap, bindingId: String, field: String): ControllerMap =
        map.copy(bindings = map.bindings.map { if (it.id == bindingId) it.copy(telemetryField = field) else it })
}
