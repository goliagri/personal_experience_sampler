/**
 * Sample-state fold (spec §5.2). Mirrors `pes/core/fold.py`.
 *
 * foldSample reduces all events for one sample_id (across all devices and
 * files) to a single row, returned as a JsonObject shaped exactly like the
 * Python fold's dict (which is what `spec/fold_vectors.json` pins).
 * answered_at / latency_s come from the winning chain's root (original
 * submission); answer content comes from the chain's latest (effective) event.
 */
package pes.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** (sourceFilePath, lineIndex, event) — any order. */
typealias EventTriple = Triple<String, Int, JsonObject>

/** Highest wins; `pending` / `scheduled` are derived, not event types. */
val STATUS_PRECEDENCE = listOf("retracted", "answered", "skipped", "expired", "suppressed", "unobserved")

/** Dedup key (§5.2 rule 0): (dev, sample, ev, t, n?, supersedes?). */
fun identityKey(ev: JsonObject): List<String?> {
    val key = mutableListOf(ev.optStr("dev"), ev.optStr("sample"), ev.optStr("ev"), ev.optStr("t"))
    if (ev.str("ev") == "snoozed") key.add((ev["n"] as? JsonPrimitive)?.content)
    if (ev.str("ev") == "answered") key.add(ev.optStr("supersedes"))
    return key
}

/**
 * Deduplicate by identity key; first by (file path, line) order wins.
 * Returns (kept events, warning count). Same key with a different payload is
 * a data error: first kept, warning recorded.
 */
fun dedupe(events: List<EventTriple>): Pair<List<JsonObject>, Int> {
    val ordered = events.sortedWith(compareBy({ it.first }, { it.second }))
    val seen = mutableMapOf<List<String?>, JsonObject>()
    val kept = mutableListOf<JsonObject>()
    var warnings = 0
    for ((_, _, ev) in ordered) {
        val key = identityKey(ev)
        val existing = seen[key]
        if (existing != null) {
            if (existing != ev) warnings += 1
            continue
        }
        seen[key] = ev
        kept.add(ev)
    }
    return Pair(kept, warnings)
}

/**
 * Resolve supersedes chains (§5.2 rule 2). Returns (winning chain root,
 * winning chain effective answer, other chains' effective answers).
 */
private fun answerChains(answered: List<JsonObject>): Triple<JsonObject?, JsonObject?, List<JsonObject>> {
    if (answered.isEmpty()) return Triple(null, null, emptyList())
    val existingTs = answered.map { it.str("t") }.toSet()
    val roots = answered.filter { it.optStr("supersedes")?.let { s -> s !in existingTs } ?: true }

    fun effective(root: JsonObject): JsonObject {
        var current = root
        val visited = mutableSetOf(System.identityHashCode(root))
        while (true) {
            val nexts = answered.filter {
                it.optStr("supersedes") == current.str("t") && System.identityHashCode(it) !in visited
            }
            if (nexts.isEmpty()) return current
            current = nexts.maxWith(compareBy({ it.str("t") }, { it.str("dev") }))
            visited.add(System.identityHashCode(current))
        }
    }

    val sortedRoots = roots.sortedWith(compareBy({ it.str("t") }, { it.str("dev") }))
    val winnerRoot = sortedRoots.first()
    return Triple(winnerRoot, effective(winnerRoot), sortedRoots.drop(1).map { effective(it) })
}

private fun firstConfigV(deduped: List<JsonObject>): Int? =
    deduped.filter { "config_v" in it }
        .minWithOrNull(compareBy({ it.str("t") }, { it.str("dev") }))
        ?.int("config_v")

/**
 * Fold one sample's events into a row. `expiryMinutes` is the effective
 * expiry at scheduled_utc, used only for `late`; null disables it.
 * Returns (row, warningCount).
 */
fun foldSample(events: List<EventTriple>, expiryMinutes: Int? = null): Pair<JsonObject, Int> {
    val (deduped, warnings) = dedupe(events)
    if (deduped.isEmpty()) {
        return Pair(JsonObject(mapOf("status" to JsonPrimitive("scheduled"))), warnings)
    }

    val sampleId = deduped.first().str("sample")
    val streamId = sampleId.substringBefore("|")
    val scheduledUtc = sampleId.substringAfter("|")
    val scheduledEpoch = parseUtc(scheduledUtc)

    val byType = deduped.groupBy { it.str("ev") }

    fun statusIgnoringRetraction(): String {
        for (status in STATUS_PRECEDENCE.drop(1)) {
            if (status in byType) return status
        }
        return if ("fired" in byType || "snoozed" in byType) "pending" else "scheduled"
    }

    val status: String
    val priorStatus: String?
    if ("retracted" in byType) {
        status = "retracted"
        priorStatus = statusIgnoringRetraction()
    } else {
        status = statusIgnoringRetraction()
        priorStatus = null
    }

    val (root, winner, duplicates) = answerChains(byType["answered"] ?: emptyList())
    val fired = byType["fired"] ?: emptyList()

    val latency = root?.let { parseUtc(it.str("t")) - scheduledEpoch }
    val late = if (winner != null && expiryMinutes != null) latency!! > expiryMinutes * 60L else false
    val suppressedReason = byType["suppressed"]
        ?.minWith(compareBy({ it.str("t") }, { it.str("dev") }))
        ?.optStr("reason")

    val row = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "sample" to JsonPrimitive(sampleId),
        "stream" to JsonPrimitive(streamId),
        "scheduled_utc" to JsonPrimitive(scheduledUtc),
        "status" to JsonPrimitive(status),
        "prior_status" to (priorStatus?.let { JsonPrimitive(it) } ?: JsonNull),
        "observed" to JsonPrimitive(fired.isNotEmpty()),
        "test" to JsonPrimitive(fired.any { it.bool("test", false) }),
        "snoozes" to JsonPrimitive(byType["snoozed"]?.size ?: 0),
        "fired_on" to JsonArray(fired.map { it.str("dev") }.distinct().sorted().map { JsonPrimitive(it) }),
        "answered_on" to (winner?.let { JsonPrimitive(it.str("dev")) } ?: JsonNull),
        "answered_at" to (root?.let { JsonPrimitive(it.str("t")) } ?: JsonNull),
        "latency_s" to (latency?.let { JsonPrimitive(it) } ?: JsonNull),
        "late" to JsonPrimitive(late),
        "partial" to JsonPrimitive(winner?.bool("partial", false) ?: false),
        "survey_id" to (winner?.let { JsonPrimitive(it.obj("survey").str("id")) } ?: JsonNull),
        "survey_version" to (winner?.let { JsonPrimitive(it.obj("survey").int("version")) } ?: JsonNull),
        "answers" to (winner?.optObj("answers") ?: JsonObject(emptyMap())),
        "loc" to (winner?.get("loc") ?: JsonNull),
        "suppressed_reason" to (suppressedReason?.let { JsonPrimitive(it) } ?: JsonNull),
        "duplicate_answers" to JsonPrimitive(duplicates.size),
        "config_version" to (firstConfigV(deduped)?.let { JsonPrimitive(it) } ?: JsonNull),
    )
    return Pair(JsonObject(row), warnings)
}
