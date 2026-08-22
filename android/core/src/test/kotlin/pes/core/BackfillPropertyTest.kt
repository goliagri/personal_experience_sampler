package pes.core

/** Backfill idempotence and completeness (test plan §3), mirroring the
 * Python property suite. */

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

private const val SEED = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"

private val CONFIG = Json.parseToJsonElement(
    """
    {
      "version": 1,
      "effective_from": "2026-01-01T00:00:00Z",
      "timezone": "America/Los_Angeles",
      "streams": [{
        "id": "p", "name": "p", "enabled": true, "seed": "$SEED",
        "protocol": {"type": "poisson", "mean_gap_minutes": 90},
        "quiet_zones": [{"days": ["mon","tue","wed","thu","fri","sat","sun"],
                         "from": "23:00", "to": "07:30"}],
        "survey": {"id": "s", "version": 1}
      }]
    }
    """,
) as JsonObject

private fun resolvedWindow(days: List<LocalDate>): List<BackfillSample> =
    days.flatMap { day ->
        resolveDay(listOf(CONFIG), "p", day).map {
            BackfillSample("p|${fmtUtc(it.scheduledUtc)}", "p", fmtUtc(it.scheduledUtc), it.suppressedReason)
        }
    }

class BackfillPropertyTest {

    @Test
    fun idempotentAndComplete() {
        val days = (0 until 3L).map { LocalDate.of(2026, 8, 18).plusDays(it) }
        val samples = resolvedWindow(days)
        val now = parseUtc("2026-08-21T12:00:00Z")

        val fired = samples.filter { it.suppressedReason == null }.take(3).map { s ->
            kotlinx.serialization.json.buildJsonObject {
                put("ev", JsonPrimitive("fired"))
                put("t", JsonPrimitive(s.scheduledUtc))
                put("dev", JsonPrimitive("phone-a3f2c1d0"))
                put("sample", JsonPrimitive(s.sample))
                put("stream", JsonPrimitive("p"))
                put("config_v", JsonPrimitive(1))
                put("scheduled", JsonPrimitive(s.scheduledUtc))
            }
        }
        val quiet = listOf(QuietChange("2026-08-19T00:00:00Z", "2026-08-19T04:00:00Z"))

        val first = backfill(samples, fired, quiet, now, "laptop-9c11aa00", 1, mapOf("p" to 60))

        // Completeness: every past sample folds to a terminal/unobserved state.
        val known = fired + first
        val bySample = known.groupBy { it.str("sample") }
        for (s in samples) {
            if (parseUtc(s.scheduledUtc) >= now) continue
            val (row, _) = foldSample(bySample.getValue(s.sample).mapIndexed { i, e -> Triple("f", i, e) }, 60)
            val status = (row.getValue("status") as JsonPrimitive).content
            assertTrue(status in setOf("expired", "unobserved", "suppressed"), s.sample)
            if (s.suppressedReason != null) assertEquals("suppressed", status)
        }

        // Idempotence: a second run over the same window emits nothing.
        assertEquals(emptyList(), backfill(samples, known, quiet, now, "laptop-9c11aa00", 1, mapOf("p" to 60)))

        // Determinism across devices: another device's backfill folds identically.
        val other = backfill(samples, fired, quiet, now, "phone-a3f2c1d0", 1, mapOf("p" to 60))
        assertEquals(first.size, other.size)
        for ((l, p) in first.zip(other)) {
            assertEquals(
                Triple(l.str("sample"), l.str("ev"), l.optStr("reason")),
                Triple(p.str("sample"), p.str("ev"), p.optStr("reason")),
            )
        }
    }
}
