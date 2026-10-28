package com.urc.core.protocol

object Hex {
    fun encode(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun decode(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() && it != ',' }.removePrefix("0x")
        require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid hex" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun ascii(b: ByteArray): String = String(b.map { if (it in 32..126) it.toInt().toChar() else '.' }.toCharArray())
}
