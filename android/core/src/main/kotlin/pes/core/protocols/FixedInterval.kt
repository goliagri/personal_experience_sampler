/**
 * Fixed-interval protocol (spec §6.3). Mirrors
 * `pes/core/protocols/fixed_interval.py`.
 *
 * Deterministic: resolve `anchor_local` ("HH:MM") to a UTC instant for the
 * local day (DST rules §4), then emit every `every_minutes` in UTC until the
 * end of the local day. Only the anchor itself can be DST-gap-flagged.
 */
package pes.core.protocols

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import pes.core.int
import pes.core.localDayBounds
import pes.core.parseHhmm
import pes.core.resolveLocal
import pes.core.str

@Suppress("UNUSED_PARAMETER")
fun generateFixedInterval(protocol: JsonObject, streamSeed: String, localDay: LocalDate, zone: ZoneId): List<Candidate> {
    val (_, end) = localDayBounds(localDay, zone)
    val (hh, mm) = parseHhmm(protocol.str("anchor_local"))
    val (anchor, gap) = resolveLocal(localDay, hh, mm, zone)
    val step = protocol.int("every_minutes").toLong() * 60

    val out = mutableListOf<Candidate>()
    var t = anchor
    while (t < end) {
        out.add(Candidate(t, dstGap = gap && t == anchor))
        t += step
    }
    return out
}
