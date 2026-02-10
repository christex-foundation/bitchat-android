package com.bitchat.android.solana

/**
 * Base58 encoding/decoding (Bitcoin/Solana alphabet, no 0OIl).
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var count = 0
        for (b in input) {
            if (b.toInt() and 0xFF == 0) count++ else break
        }
        val buf = input.copyOf()
        val result = StringBuilder()
        while (true) {
            var zeroCount = 0
            var carry: Int
            var i = 0
            while (i < buf.size) {
                carry = (buf[i].toInt() and 0xFF) + (zeroCount * 256)
                buf[i] = (carry % 58).toByte()
                zeroCount = carry / 58
                i++
            }
            if (zeroCount == 0) break
            var j = 0
            while (j < buf.size && buf[j].toInt() and 0xFF == 0) {
                j++
            }
            if (j == buf.size) break
        }
        var started = false
        for (b in buf) {
            val v = b.toInt() and 0xFF
            if (v != 0) started = true
            if (started) result.append(ALPHABET[v])
        }
        repeat(count) { result.insert(0, ALPHABET[0]) }
        return result.toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var count = 0
        for (c in input) {
            if (c == '1') count++ else break
        }
        val indices = IntArray(input.length) { i ->
            val idx = ALPHABET.indexOf(input[i])
            if (idx < 0) throw IllegalArgumentException("Invalid Base58 character: ${input[i]}")
            idx
        }
        var size = 1
        for (idx in indices) {
            var carry = idx
            var j = 0
            while (j < size || carry != 0) {
                carry += (indices.getOrElse(j) { 0 } * 58)
                indices[j] = carry % 256
                carry /= 256
                j++
                if (j > size) size = j
            }
        }
        val result = ByteArray(count + size)
        var outIdx = count
        var i = size - 1
        while (i >= 0) {
            result[outIdx++] = indices[i].toByte()
            i--
        }
        return result
    }
}
