/**
 * Backfill (spec §6.4). Mirrors `pes/core/backfill.py`: a pure function that
 * classifies past samples in a window; running it twice over the same inputs
 * emits nothing the second time, and all devices produce fold-equivalent
 * events.
 */
package pes.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

val TERMINAL_EVENTS = setOf("answered", "skipped", "expired", "retracted", "suppressed")

data class BackfillSample(
    val sample: String,
    val stream: String,
    val scheduledUtc: String,
    val suppressedReason: String?,
)

/** {"t": iso, "quietUntil": iso | "indefinite" | null} */
data class QuietChange(val t: String, val quietUntil: String?)

/** Whether quiet mode covered `instant`: the latest change at or before it. */
fun quietModeActive(instant: Long, quietHistory: List<QuietChange>): Boolean {
    var active: String? = null
    var seen = false
    for (change in quietHistory.sortedBy { it.t }) {
        if (parseUtc(change.t) <= instant) {
            active = change.quietUntil
            seen = true
        }
    }
    if (!seen || active == null) return false
    if (active == "indefinite") return true
    return instant < parseUtc(active)
}

fun backfill(
    resolvedSamples: List<BackfillSample>,
    knownEvents: List<JsonObject>,
    quietHistory: List<QuietChange>,
    now: Long,
    deviceId: String,
    configV: Int,
    expiryMinutesFor: Map<String, Int>,
): List<JsonObject> {
    val bySample = knownEvents.filter { it.optStr("sample") != null }.groupBy { it.str("sample") }

    val out = mutableListOf<JsonObject>()
    val nowIso = fmtUtc(now)
    for (sample in resolvedSamples.sortedBy { it.scheduledUtc }) {
        val scheduled = parseUtc(sample.scheduledUtc)
        if (scheduled >= now) continue
        val events = bySample[sample.sample] ?: emptyList()
        val types = events.map { it.str("ev") }.toSet()

        fun event(ev: String, extra: Map<String, String> = emptyMap(), withConfigV: Boolean = false) =
            buildJsonObject {
                put("ev", JsonPrimitive(ev))
                for ((k, v) in extra) put(k, JsonPrimitive(v))
                if (withConfigV) put("config_v", JsonPrimitive(configV))
                put("t", JsonPrimitive(nowIso))
                put("dev", JsonPrimitive(deviceId))
                put("sample", JsonPrimitive(sample.sample))
                put("stream", JsonPrimitive(sample.stream))
            }

        // 1. Terminal event from any known device -> nothing.
        if (types.intersect(TERMINAL_EVENTS).isNotEmpty()) continue
        // 2. Quiet zone / DST gap (from generation) or quiet mode (from
        //    imported history) -> suppressed with the reason.
        if (sample.suppressedReason != null) {
            out.add(event("suppressed", mapOf("reason" to sample.suppressedReason)))
            continue
        }
        if (quietModeActive(scheduled, quietHistory)) {
            out.add(event("suppressed", mapOf("reason" to "quiet_mode")))
            continue
        }
        // 3. Fired somewhere and past expiry -> retroactive expired.
        val expiryS = expiryMinutesFor.getValue(sample.stream) * 60L
        if ("fired" in types) {
            if (scheduled + expiryS < now) out.add(event("expired", withConfigV = true))
            continue
        }
        // 4. Never fired anywhere -> unobserved, but only once the active
        //    window has closed: a still-open sample can legitimately fire
        //    late (fired.t lags scheduled, §5.1), so the live scheduler owns
        //    it and the caller must hold its watermark back to revisit it.
        //    Guarded on "not already present" to keep backfill idempotent
        //    when another device already logged it.
        if (scheduled + expiryS <= now && "unobserved" !in types) {
            out.add(event("unobserved", withConfigV = true))
        }
    }
    return out
}
