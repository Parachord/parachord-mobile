package com.parachord.shared.playlist

/**
 * Pure-Kotlin (commonMain) SHA-256 — the KMP-shared analogue of Android's
 * `java.security.MessageDigest`. Used as the change-detection token for hosted
 * XSPF polling: hash the raw body and compare to the stored `sourceContentHash`.
 * Byte-identical to Android's `MessageDigest.getInstance("SHA-256")` output.
 */
private val K = uintArrayOf(
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
)

private infix fun UInt.ror(bits: Int): UInt = (this shr bits) or (this shl (32 - bits))

/** Lowercase hex SHA-256 of [input]'s UTF-8 bytes. */
fun sha256Hex(input: String): String {
    val msg = input.encodeToByteArray()
    val bitLen = msg.size.toLong() * 8

    // Pad: 0x80, then zeros up to 56 mod 64, then 8-byte big-endian bit length.
    val padded = ByteArray(((msg.size + 8) / 64 + 1) * 64)
    msg.copyInto(padded)
    padded[msg.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
    }

    val h = uintArrayOf(
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    )
    val w = UIntArray(64)

    var block = 0
    while (block < padded.size) {
        for (t in 0 until 16) {
            val o = block + t * 4
            w[t] = ((padded[o].toUInt() and 0xffu) shl 24) or
                ((padded[o + 1].toUInt() and 0xffu) shl 16) or
                ((padded[o + 2].toUInt() and 0xffu) shl 8) or
                (padded[o + 3].toUInt() and 0xffu)
        }
        for (t in 16 until 64) {
            val s0 = (w[t - 15] ror 7) xor (w[t - 15] ror 18) xor (w[t - 15] shr 3)
            val s1 = (w[t - 2] ror 17) xor (w[t - 2] ror 19) xor (w[t - 2] shr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }

        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = (e ror 6) xor (e ror 11) xor (e ror 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[t] + w[t]
            val s0 = (a ror 2) xor (a ror 13) xor (a ror 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        block += 64
    }

    return buildString(64) {
        for (v in h) {
            for (i in 3 downTo 0) {
                val byte = ((v shr (8 * i)) and 0xffu).toInt()
                append("0123456789abcdef"[byte ushr 4])
                append("0123456789abcdef"[byte and 0xf])
            }
        }
    }
}
