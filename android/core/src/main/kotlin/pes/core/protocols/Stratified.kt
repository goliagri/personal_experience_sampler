/**
 * Stratified protocol (spec §6.3). Mirrors `pes/core/protocols/stratified.py`.
 *
 * Scope: per interval index within the local day (`interval:YYYY-MM-DD:k`).
 * Intervals of `interval_minutes` tile the actual local day (23/24/25 h) from
 * local midnight; a trailing partial interval gets
 * floor(pings_per_interval * fraction) pings. Offsets are drawn uniformly in
 * whole seconds within the interval (floor(u * intervalLen)), then sorted.
 */
package pes.core.protocols

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import pes.core.int
import pes.core.localDayBounds
import pes.core.rngFor

fun generateStratified(protocol: JsonObject, streamSeed: String, localDay: LocalDate, zone: ZoneId): List<Candidate> {
    val (start, end) = localDayBounds(localDay, zone)
    val intervalS = protocol.int("interval_minutes").toLong() * 60
    val pings = protocol.int("pings_per_interval")

    val out = mutableListOf<Candidate>()
    var k = 0
    var pos = start
    while (pos < end) {
        val length = minOf(intervalS, end - pos)
        // Same float-division-then-floor as the Python core (exact here:
        // all operands are far below 2^53).
        val n = if (length == intervalS) pings
                else Math.floor(pings.toDouble() * length.toDouble() / intervalS.toDouble()).toInt()
        if (n > 0) {
            val rng = rngFor(streamSeed, "interval:$localDay:$k")
            val offsets = (1..n).map { Math.floor(rng.uniform() * length.toDouble()).toLong() }.sorted()
            offsets.forEach { out.add(Candidate(pos + it)) }
        }
        k += 1
        pos += intervalS
    }
    return out
}
