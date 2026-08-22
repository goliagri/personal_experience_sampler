package pes.core

/**
 * The [H] bit-identity layer: StrictMath.log must reproduce every ln vector
 * entry bit-for-bit, proving the Python fdlibm port and the JVM agree.
 */

import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

private fun bitsToDouble(s: String): Double =
    java.lang.Double.longBitsToDouble(s.removePrefix("0x").toULong(16).toLong())

private fun doubleToBits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

class LogConformanceTest {
    private val doc = loadSpec("log_vectors.json")

    @Test
    fun lnBitPatterns() {
        for (case in (doc.getValue("ln") as JsonArray).map { it as JsonObject }) {
            val x = bitsToDouble(case.str("x_bits"))
            val expected = case.str("x_bits") to case.str("ln_bits")
            assertEquals(
                expected.second.removePrefix("0x").toULong(16).toLong(),
                doubleToBits(fdlibmLog(x)),
                "ln(${expected.first})",
            )
        }
    }

    @Test
    fun endToEndDraws() {
        for (case in (doc.getValue("draws") as JsonArray).map { it as JsonObject }) {
            val u = bitsToDouble(case.str("u_bits"))
            val gap = Math.floor(-case.double("mean_seconds") * fdlibmLog(1.0 - u)).toLong()
            assertEquals(case.int("gap_seconds").toLong(), gap)
        }
    }
}
