package pes.core

/**
 * [H] Fold idempotence & order-invariance (test plan §3), mirroring the
 * Python hypothesis suite with seeded random generation: folding any event
 * multiset equals folding it shuffled, split across files differently, or
 * with any subset duplicated. Payloads are pure functions of the identity key
 * so no payload-conflicting duplicates are generated.
 */

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

private const val SAMPLE = "s|2026-08-20T15:00:00Z"
private val DEVICES = listOf("phone-a3f2c1d0", "laptop-9c11aa00", "old-11112222")
private val TIMES = listOf(0, 1, 3, 5, 12, 30, 59).map { "2026-08-20T15:%02d:00Z".format(it) }
private val KINDS = listOf(
    "fired", "snoozed", "skipped", "answered", "expired", "unobserved", "suppressed", "retracted",
)

private fun event(kind: String, dev: String, t: String, k: Int): JsonObject = buildJsonObject {
    val ti = TIMES.indexOf(t)
    put("ev", JsonPrimitive(kind))
    put("t", JsonPrimitive(t))
    put("dev", JsonPrimitive(dev))
    put("sample", JsonPrimitive(SAMPLE))
    put("stream", JsonPrimitive("s"))
    when (kind) {
        "fired" -> {
            put("config_v", JsonPrimitive(1))
            put("scheduled", JsonPrimitive("2026-08-20T15:00:00Z"))
            put("test", JsonPrimitive(false))
        }
        "snoozed" -> {
            put("n", JsonPrimitive(k % 3 + 1))
            put("until", JsonPrimitive("2026-08-20T16:00:00Z"))
        }
        "answered" -> {
            put("survey", buildJsonObject {
                put("id", JsonPrimitive("s"))
                put("version", JsonPrimitive(1))
            })
            put("answers", buildJsonObject {
                put("tags", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("tag-${dev.take(2)}-$ti"))))
            })
            if (ti % 3 == 0 && ti > 0) put("supersedes", JsonPrimitive(TIMES[ti - 1]))
        }
        "expired", "unobserved" -> put("config_v", JsonPrimitive(1))
        "suppressed" -> put("reason", JsonPrimitive(listOf("quiet_zone", "quiet_mode", "dst")[ti % 3]))
    }
}

private fun randomEvents(rng: Random): List<JsonObject> =
    (0 until rng.nextInt(1, 13)).map {
        event(KINDS.random(rng), DEVICES.random(rng), TIMES.random(rng), rng.nextInt(0, 12))
    }

private fun asFiles(events: List<JsonObject>, nFiles: Int, rng: Random): List<EventTriple> {
    val paths = (0 until nFiles).map { "events/d$it/2026-08.jsonl" }.sorted()
    val files = paths.associateWith { mutableListOf<JsonObject>() }
    for (ev in events) files.getValue(paths.random(rng)).add(ev)
    return paths.flatMap { p -> files.getValue(p).mapIndexed { i, e -> Triple(p, i, e) } }
}

class FoldPropertyTest {

    @Test
    fun orderSplitAndDuplicationInvariance() {
        val rng = Random(20260821)
        repeat(500) {
            val events = randomEvents(rng)
            val baseline = foldSample(events.mapIndexed { i, e -> Triple("events/a.jsonl", i, e) }, 60).first

            val shuffled = events.shuffled(rng)
            assertEquals(
                baseline,
                foldSample(shuffled.mapIndexed { i, e -> Triple("events/a.jsonl", i, e) }, 60).first,
            )
            assertEquals(baseline, foldSample(asFiles(shuffled, rng.nextInt(1, 4), rng), 60).first)

            val duplicated = (events + events.filter { rng.nextBoolean() }).shuffled(rng)
            assertEquals(baseline, foldSample(asFiles(duplicated, 2, rng), 60).first)
        }
    }

    @Test
    fun answeredNeverDowngraded() {
        val rng = Random(42)
        repeat(500) {
            val events = randomEvents(rng)
            val row = foldSample(events.mapIndexed { i, e -> Triple("f", i, e) }, 60).first
            val kinds = events.map { it.str("ev") }.toSet()
            val status = (row.getValue("status") as JsonPrimitive).content
            if ("retracted" in kinds) assertEquals("retracted", status)
            else if ("answered" in kinds) assertEquals("answered", status)
        }
    }
}
