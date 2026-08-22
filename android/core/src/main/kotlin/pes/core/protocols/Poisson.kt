/**
 * Poisson protocol (spec §6.3). Mirrors `pes/core/protocols/poisson.py`.
 *
 * Scope: per UTC day (`day:YYYY-MM-DD`). For a requested local day, every
 * overlapping UTC day is generated and candidates are filtered to the
 * local-day bounds. Starting at 00:00Z, exponential gaps with the configured
 * mean are drawn until the UTC day ends. `min_gap_minutes` is enforced only
 * within a UTC day, never across 00:00Z.
 */
package pes.core.protocols

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import pes.core.double
import pes.core.fdlibmLog
import pes.core.localDayBounds
import pes.core.optDouble
import pes.core.rngFor
import pes.core.utcDayOf
import pes.core.utcMidnight

private const val DAY_S = 86400L

fun generatePoisson(protocol: JsonObject, streamSeed: String, localDay: LocalDate, zone: ZoneId): List<Candidate> {
    val (start, end) = localDayBounds(localDay, zone)
    val meanS = protocol.double("mean_gap_minutes") * 60.0
    val minS: Long? = protocol.optDouble("min_gap_minutes")?.let { (it * 60).toLong() }

    val out = mutableListOf<Candidate>()
    var utcDay = utcDayOf(start)
    val lastDay = utcDayOf(end - 1)
    while (utcDay <= lastDay) {
        val dayStart = utcMidnight(utcDay)
        val dayEnd = dayStart + DAY_S
        val rng = rngFor(streamSeed, "day:$utcDay")
        var cursor = dayStart
        var prev: Long? = null
        while (true) {
            val u = rng.uniform()
            val gap = Math.floor(-meanS * fdlibmLog(1.0 - u)).toLong()
            cursor += gap
            if (prev != null && minS != null && cursor - prev < minS) {
                cursor = prev + minS
            }
            if (cursor >= dayEnd) break
            if (cursor in start until end) out.add(Candidate(cursor))
            prev = cursor
        }
        utcDay = utcDay.plusDays(1)
    }
    return out
}
