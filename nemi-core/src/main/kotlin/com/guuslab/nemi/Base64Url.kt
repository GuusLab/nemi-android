package com.guuslab.nemi

/**
 * Unpadded base64url (RFC 4648 section 5). Written by hand because
 * `java.util.Base64` only exists from Android API 26 and this module supports 23.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        // Accept standard base64 input as well.
        table['+'.code] = 62
        table['/'.code] = 63
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (bytes[i].toInt() and 0xff shl 16) or
                (bytes[i + 1].toInt() and 0xff shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xff shl 16
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                    .append(ALPHABET[n ushr 6 and 63])
            }
        }
        return out.toString()
    }

    fun decode(input: String): ByteArray {
        val s = input.trimEnd('=')
        require(s.length % 4 != 1) { "Invalid base64url length" }
        val out = ByteArray(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var o = 0
        for (c in s) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            require(v >= 0) { "Invalid base64url character" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (buffer shr bits and 0xff).toByte()
            }
        }
        return out
    }
}
