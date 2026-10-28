package com.urc.core.mapping

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

object ValueScaler {
    /** Maps a raw input value to the output range applying invert, deadzone, expo. Inside the deadzone returns neutral. */
    fun scale(input: Double, s: Scaling): Double {
        val lo = s.inputMin
        val hi = s.inputMax
        val x = input.coerceIn(lo, hi)
        val mid = (lo + hi) / 2
        val half = (hi - lo) / 2
        var n = (x - mid) / half // -1..1
        if (s.invert) n = -n
        if (abs(n) < s.deadzone) return s.neutral.coerceIn(minOf(s.min, s.max), maxOf(s.min, s.max))
        // rescale so output starts at 0 just outside the deadzone
        val adj = sign(n) * (abs(n) - s.deadzone) / (1 - s.deadzone)
        val e = s.expo.coerceIn(0.0, 1.0)
        val curved = (1 - e) * adj + e * adj.pow(3)
        val outMid = (s.min + s.max) / 2
        val outHalf = (s.max - s.min) / 2
        return outMid + curved * outHalf
    }
}
