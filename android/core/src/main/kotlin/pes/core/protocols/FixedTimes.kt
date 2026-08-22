/**
 * Fixed-times protocol (spec §6.3). Mirrors
 * `pes/core/protocols/fixed_times.py`.
 *
 * Domain is exactly the listed local wall-clock times on matching local days,
 * resolved per §4 (nonexistent -> suppressed(dst) at the pre-transition-offset
 * instant; duplicated -> first occurrence). Generation order is `times_local`
 * order, which the collision rule relies on.
 */
package pes.core.protocols

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import pes.core.optStringList
import pes.core.parseHhmm
import pes.core.resolveLocal
import pes.core.weekdayName

@Suppress("UNUSED_PARAMETER")
fun generateFixedTimes(protocol: JsonObject, streamSeed: String, localDay: LocalDate, zone: ZoneId): List<Candidate> {
    val days = protocol.optStringList("days")
    if (days != null && weekdayName(localDay) !in days) return emptyList()
    return protocol.optStringList("times_local")!!.map { hhmm ->
        val (hh, mm) = parseHhmm(hhmm)
        val (utc, gap) = resolveLocal(localDay, hh, mm, zone)
        Candidate(utc, dstGap = gap)
    }
}
