/**
 * Deterministic PRNG for schedule generation (spec §6.2).
 *
 * xoshiro256** seeded via SplitMix64 from the first 8 bytes (big-endian) of
 * SHA-256(stream_seed || ":" || scope). Mirrors `pes/core/prng.py`.
 */
package pes.core

import java.security.MessageDigest

/** Advance a SplitMix64 state; returns (newState, output). */
fun splitmix64Next(state: ULong): Pair<ULong, ULong> {
    val s = state + 0x9E3779B97F4A7C15uL
    var z = s
    z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
    z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
    return Pair(s, z xor (z shr 31))
}

private fun rotl(x: ULong, k: Int): ULong = (x shl k) or (x shr (64 - k))

/** xoshiro256** with state initialized from SplitMix64(seedU64). */
class Xoshiro256StarStar(seedU64: ULong) {
    private val s = ULongArray(4)

    init {
        var state = seedU64
        for (i in 0 until 4) {
            val (next, out) = splitmix64Next(state)
            state = next
            s[i] = out
        }
    }

    fun nextU64(): ULong {
        val result = rotl(s[1] * 5uL, 7) * 9uL
        val t = s[1] shl 17
        s[2] = s[2] xor s[0]
        s[3] = s[3] xor s[1]
        s[1] = s[1] xor s[2]
        s[0] = s[0] xor s[3]
        s[2] = s[2] xor t
        s[3] = rotl(s[3], 45)
        return result
    }

    /** 53-bit uniform double in [0, 1). */
    fun uniform(): Double = uniformDouble(nextU64())
}

private const val TWO_POW_MINUS_53: Double = 1.0 / (1L shl 53)

fun uniformDouble(u64: ULong): Double = (u64 shr 11).toLong().toDouble() * TWO_POW_MINUS_53

/** First 8 bytes of SHA-256(streamSeed || ":" || scope), big-endian. */
fun seedU64(streamSeed: String, scope: String): ULong {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$streamSeed:$scope".toByteArray(Charsets.US_ASCII))
    var v = 0uL
    for (i in 0 until 8) v = (v shl 8) or digest[i].toUByte().toULong()
    return v
}

fun rngFor(streamSeed: String, scope: String) = Xoshiro256StarStar(seedU64(streamSeed, scope))
