package pes.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

private fun hexU64(s: String): ULong = s.removePrefix("0x").toULong(16)

class PrngConformanceTest {
    private val doc = loadSpec("prng_vectors.json")

    private fun cases(key: String) = (doc.getValue(key) as JsonArray).map { it as JsonObject }

    @Test
    fun splitmix64() {
        for (case in cases("splitmix64")) {
            var state = hexU64(case.str("seed"))
            for (expected in case.arr("outputs")) {
                val (next, out) = splitmix64Next(state)
                state = next
                assertEquals(hexU64((expected as JsonPrimitive).content), out)
            }
        }
    }

    @Test
    fun splitmix64PublishedReference() {
        var state = 0uL
        val outs = mutableListOf<ULong>()
        repeat(3) {
            val (next, out) = splitmix64Next(state)
            state = next
            outs.add(out)
        }
        assertEquals(listOf(0xE220A8397B1DCDAFuL, 0x6E789E6AA1B965F4uL, 0x06C45D188009454FuL), outs)
    }

    @Test
    fun xoshiro256starstar() {
        for (case in cases("xoshiro256starstar")) {
            val rng = Xoshiro256StarStar(hexU64(case.str("seed_u64")))
            for (expected in case.arr("outputs")) {
                assertEquals(hexU64((expected as JsonPrimitive).content), rng.nextU64())
            }
        }
    }

    @Test
    fun seedDerivation() {
        for (case in cases("seed_derivation")) {
            assertEquals(hexU64(case.str("seed_u64")), seedU64(case.str("stream_seed"), case.str("scope")))
        }
    }

    @Test
    fun uniformDoubleMapping() {
        for (case in cases("uniform_double")) {
            val got = uniformDouble(hexU64(case.str("u64")))
            assertEquals(hexU64(case.str("double_bits")).toLong(), java.lang.Double.doubleToRawLongBits(got))
            assertTrue(got >= 0.0 && got < 1.0)
        }
    }
}
