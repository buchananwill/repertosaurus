package dev.repertosaurus.core

/**
 * SHA-1, per RFC 3174.
 *
 * Kotlin/Common has no digest in the standard library and UUIDv5 (decision 2) is defined
 * over SHA-1, so the shared core carries its own. This is a hashing primitive for
 * identity derivation only. It is not a security control and must never be used as one.
 */
internal fun sha1(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() * 8L

    // Pad to a multiple of 64 bytes: 0x80, then zeros, then the length as 8 big-endian bytes.
    val padding = ((56 - (message.size + 1) % 64) + 64) % 64
    val total = message.size + 1 + padding + 8
    val buffer = ByteArray(total)
    message.copyInto(buffer)
    buffer[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        buffer[total - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
    }

    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    val w = IntArray(80)
    var chunk = 0
    while (chunk < total) {
        for (i in 0 until 16) {
            val j = chunk + i * 4
            w[i] = ((buffer[j].toInt() and 0xFF) shl 24) or
                ((buffer[j + 1].toInt() and 0xFF) shl 16) or
                ((buffer[j + 2].toInt() and 0xFF) shl 8) or
                (buffer[j + 3].toInt() and 0xFF)
        }
        for (i in 16 until 80) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        for (i in 0 until 80) {
            val f: Int
            val k: Int
            when {
                i < 20 -> {
                    f = (b and c) or (b.inv() and d)
                    k = 0x5A827999
                }
                i < 40 -> {
                    f = b xor c xor d
                    k = 0x6ED9EBA1
                }
                i < 60 -> {
                    f = (b and c) or (b and d) or (c and d)
                    k = 0x8F1BBCDC.toInt()
                }
                else -> {
                    f = b xor c xor d
                    k = 0xCA62C1D6.toInt()
                }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        chunk += 64
    }

    val digest = ByteArray(20)
    val words = intArrayOf(h0, h1, h2, h3, h4)
    for (i in words.indices) {
        for (j in 0 until 4) {
            digest[i * 4 + j] = ((words[i] ushr (24 - 8 * j)) and 0xFF).toByte()
        }
    }
    return digest
}
