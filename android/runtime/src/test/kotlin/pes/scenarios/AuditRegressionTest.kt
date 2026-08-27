/** Regressions from the 2026-08 code audit (Kotlin side): sync-procedure
 * edge cases the design requires but the original scenarios missed. Mirrors
 * `desktop/tests/scenarios/test_audit_regressions.py`. */
package pes.scenarios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.core.int
import pes.core.parseUtc
import pes.core.str
import pes.store.LocalFolderStore
import pes.store.dumpsLine
import pes.store.parseJson

private const val SAMPLE = "st|2026-08-24T16:00:00Z"

class AuditRegressionTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock(T0)
    private val cloud by lazy { LocalFolderStore(File(tmp, "cloud")) }

    private fun mkdevice(name: String) = SimDevice(name, tmp, cloud, clock)

    @Test
    fun `retroactive expiry after unobserved`() {
        // [H] fired on one device + unobserved on another folds to unobserved
        // (precedence), but §8.4 step 3 must still close it out as expired —
        // the decision is made on event types, not the folded status.
        val phone = mkdevice("phone-ee01")
        val laptop = mkdevice("laptop-ee02")
        phone.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(phone), clock, "2026-08-24T16:00:00Z") // phone fires, then dies
        assertEquals(listOf("fired"), phone.sampleEvents(SAMPLE).map { it.str("ev") })

        clock.set(parseUtc("2026-08-24T18:00:00Z"))
        laptop.boot(baseConfig(listOf(fixedStream()))) // laptop was off all along
        laptop.db.kvSet("sync_meta", "last_materialized_at", "2026-08-24T15:00:00Z")
        laptop.engine.backfillNow()
        assertEquals(listOf("unobserved"), laptop.sampleEvents(SAMPLE).map { it.str("ev") })

        phone.syncer.sync()
        laptop.syncer.sync() // imports the fired; window long past -> expired
        assertEquals("expired", laptop.db.sampleRow(SAMPLE)!!.str("status"))
    }

    @Test
    fun `lineage moved past local version is archived`() {
        // A local unsynced v2 overtaken by another device's v2->v3 chain must
        // be archived as a conflict and replaced by the cloud lineage —
        // never silently kept as a divergent history (§8.2).
        val a = mkdevice("laptop-ee05")
        val b = mkdevice("phone-ee06")
        a.boot(baseConfig(listOf(fixedStream())))
        b.boot(baseConfig(listOf(fixedStream())))
        a.syncer.sync()
        b.syncer.sync()
        val defaults = a.db.latestConfig()!!.getValue("defaults") as kotlinx.serialization.json.JsonObject
        fun stage(dev: SimDevice, time: String) = dev.engine.stageNewConfig(
            listOf(fixedStream(times = listOf(time))), defaults,
            "America/Los_Angeles", "2026-08-25T07:00:00Z",
        )
        assertTrue(stage(a, "10:00").isEmpty()) // a: v2, never uploaded
        assertTrue(stage(b, "11:00").isEmpty())
        b.syncer.sync() // cloud: b's v2
        assertTrue(stage(b, "12:00").isEmpty())
        b.syncer.sync() // cloud: v3

        val result = a.syncer.sync()
        assertTrue(result.conflicts.isNotEmpty() && result.warnings.isNotEmpty())
        assertTrue(cloud.list("config/conflicts").isNotEmpty())
        val history = a.db.configHistory().associate { it.int("version") to it.str("written_by") }
        assertEquals("phone-ee06", history[2]) // a's divergent v2 replaced, not kept
        assertEquals("phone-ee06", history[3])
    }

    @Test
    fun `malformed cloud file does not block sync`() {
        val dev = mkdevice("laptop-ee08")
        dev.boot(baseConfig(listOf(fixedStream())))
        val good = "{\"ev\":\"skipped\",\"t\":\"2026-08-24T16:00:00Z\",\"dev\":\"phone-zz1\"," +
            "\"sample\":\"st|2026-08-24T16:00:00Z\",\"stream\":\"st\"}"
        cloud.put("events/phone-zz1/2026-08.jsonl", "$good\n\n".toByteArray()) // trailing blank
        cloud.put("events/phone-zz0/2026-08.jsonl", "not json at all\n".toByteArray())

        var result = dev.syncer.sync()
        assertTrue(result.warnings.any { "phone-zz0" in it })
        assertTrue("events/phone-zz1/2026-08.jsonl" in result.imported)
        assertEquals("skipped", dev.db.sampleRow(SAMPLE)!!.str("status"))

        // The bad file is retried (etag not remembered), still without blocking.
        result = dev.syncer.sync()
        assertTrue(result.warnings.any { "phone-zz0" in it })
    }

    @Test
    fun `event lines are pure ascii`() {
        // Raw non-ASCII (e.g. U+2028) in a JSONL line would be split as a
        // newline by Python's str.splitlines; dumpsLine must escape it.
        val note = "a\u2028b\u00e9"
        val ev = parseJson(
            "{\"ev\":\"answered\",\"t\":\"2026-08-24T16:01:00Z\",\"dev\":\"p\"," +
                "\"sample\":\"$SAMPLE\",\"stream\":\"st\",\"answers\":{\"note\":\"$note\"}}"
        )
        val line = dumpsLine(ev)
        assertTrue(line.all { it.code < 0x80 }, line)
        assertTrue("\\u2028" in line && "\\u00e9" in line)
        assertEquals(ev, parseJson(line)) // round-trips to the same document
    }
}
