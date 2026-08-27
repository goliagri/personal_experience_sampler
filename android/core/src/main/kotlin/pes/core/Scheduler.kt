/**
 * Scheduler core (spec §6.1–6.2). Mirrors `pes/core/scheduler.py`, including
 * its pinned piecewise semantics: for each config version v effective during
 * the day, the day is generated in full under v (protocol, timezone, seed)
 * and candidates are kept iff they fall inside the intersection of v's
 * effective interval with the local-day window computed under v's timezone.
 * Generation order for the collision rule is segment order, then the
 * protocol's own emission order. The returned list is sorted by scheduled
 * time; each sample's position is the 0-based quick/full index (§7).
 */
package pes.core

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import pes.core.protocols.getProtocol
import pes.core.protocols.registerAll

data class ResolvedSample(
    val scheduledUtc: Long,
    val configV: Int,
    val suppressedReason: String?, // "quiet_zone" | "dst" | null
    val index: Int,
)

private fun streamIn(config: JsonObject, streamId: String): JsonObject? =
    config.objList("streams").firstOrNull { it.str("id") == streamId }

fun resolveDay(configHistory: List<JsonObject>, streamId: String, localDay: LocalDate): List<ResolvedSample> {
    registerAll()
    val history = configHistory.sortedBy { it.int("version") }
    val effectiveFrom = history.map { parseUtc(it.str("effective_from")) }

    // Effective intervals per version (§6.1 step 1): at any instant the
    // version in effect is the *highest* one whose effective_from has passed.
    // effective_from need not rise with version — a later edit may take
    // effect before a still-pending one, which then never becomes effective;
    // assuming monotonicity here would generate the same instant under two
    // versions and mint a phantom +1 s ping via the collision rule. Nothing
    // is generated before the earliest effective_from.
    val points = effectiveFrom.toSortedSet().toList()
    val intervals = mutableMapOf<Int, MutableList<Pair<Long, Long?>>>()
    for ((k, start) in points.withIndex()) {
        val end = points.getOrNull(k + 1)
        val version = history.indices
            .filter { effectiveFrom[it] <= start }
            .maxOf { history[it].int("version") }
        intervals.getOrPut(version) { mutableListOf() }.add(Pair(start, end))
    }

    // Generate per effective version, in version order.
    data class Raw(val utc: Long, val dstGap: Boolean, val configV: Int)
    val raw = mutableListOf<Raw>()
    for (config in history) {
        val segments = intervals[config.int("version")] ?: continue
        val stream = streamIn(config, streamId) ?: continue
        if (!stream.bool("enabled", true)) continue
        val zone = ZoneId.of(config.str("timezone"))
        val (dayStart, dayEnd) = localDayBounds(localDay, zone)
        val clipped = segments
            .map { (lo, hi) -> Pair(maxOf(dayStart, lo), if (hi == null) dayEnd else minOf(dayEnd, hi)) }
            .filter { (lo, hi) -> lo < hi }
        if (clipped.isEmpty()) continue
        val generate = getProtocol(stream.obj("protocol").str("type"))
        for (cand in generate(stream.obj("protocol"), stream.str("seed"), localDay, zone)) {
            if (clipped.any { (lo, hi) -> cand.utc in lo until hi }) {
                raw.add(Raw(cand.utc, cand.dstGap, config.int("version")))
            }
        }
    }

    // Collision rule (§6.2): in generation order, shift +1 s until unique.
    val byVersion = history.associateBy { it.int("version") }
    val occupied = mutableSetOf<Long>()
    val resolved = mutableListOf<Triple<Long, Int, String?>>()
    for (r in raw) {
        var utc = r.utc
        while (utc in occupied) utc += 1
        occupied.add(utc)
        // Suppression: spec order is quiet zones (step 4) then DST (step 5);
        // the first applicable reason sticks. Evaluated under the config
        // version that generated the candidate.
        val config = byVersion.getValue(r.configV)
        val stream = streamIn(config, streamId)!!
        val zone = ZoneId.of(config.str("timezone"))
        val reason = when {
            inQuietZone(utc, stream.objList("quiet_zones"), zone) -> "quiet_zone"
            r.dstGap -> "dst"
            else -> null
        }
        resolved.add(Triple(utc, r.configV, reason))
    }

    return resolved
        .sortedBy { it.first }
        .mapIndexed { i, (utc, v, reason) -> ResolvedSample(utc, v, reason, i) }
}
