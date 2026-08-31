/** Expiry, snooze, and backlog scenarios (test plan §2). Mirrors
 * `desktop/tests/scenarios/test_expiry_snooze.py`. */
package pes.scenarios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.core.bool
import pes.core.int
import pes.core.str
import pes.store.LocalFolderStore

private const val SAMPLE = "st|2026-08-24T16:00:00Z" // 09:00 local

class ExpirySnoozeScenarioTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock(T0)

    private fun mkdevice(name: String) =
        SimDevice(name, tmp, LocalFolderStore(File(tmp, "cloud")), clock)

    private fun tags(vararg values: String) = buildJsonObject {
        put("tags", JsonArray(values.map { JsonPrimitive(it) }))
    }

    @Test
    fun `fire expire backlog`() {
        val dev = mkdevice("laptop-bbbb0001")
        dev.boot(baseConfig(listOf(fixedStream())))
        dev.engine.tick()

        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")
        val events = dev.sampleEvents(SAMPLE)
        assertEquals(listOf("fired"), events.map { it.str("ev") })
        assertFalse(events[0].bool("test", false))
        assertTrue(SAMPLE in dev.notifier.active())
        assertEquals(listOf(SAMPLE), dev.engine.activeSamples().map { it.str("sample") })
        assertTrue(dev.engine.backlog().isEmpty())

        // At the expiry instant: expired logged, notification cancelled,
        // sample moves to backlog.
        tickedTo(listOf(dev), clock, "2026-08-24T17:00:00Z")
        assertEquals("expired", dev.db.sampleRow(SAMPLE)!!.str("status"))
        assertTrue(SAMPLE !in dev.notifier.active())
        assertTrue(dev.engine.activeSamples().isEmpty())
        assertEquals(listOf(SAMPLE), dev.engine.backlog().map { it.str("sample") })

        // Backlog excludes samples older than backlog_hours (12 h default).
        tickedTo(listOf(dev), clock, "2026-08-25T05:00:00Z")
        assertTrue(SAMPLE !in dev.engine.backlog().map { it.str("sample") })
    }

    @Test
    fun `snooze refires and counts`() {
        val dev = mkdevice("laptop-bbbb0002")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")

        assertNull(dev.engine.snooze(SAMPLE))
        assertTrue(SAMPLE !in dev.notifier.active())

        // Re-fires 10 minutes later with a second fired event.
        tickedTo(listOf(dev), clock, "2026-08-24T16:10:00Z")
        assertEquals(listOf("fired", "snoozed", "fired"), dev.sampleEvents(SAMPLE).map { it.str("ev") })
        assertTrue(SAMPLE in dev.notifier.active())
        assertTrue("snoozed x1" in dev.notifier.shown.last().second)

        val row = dev.db.sampleRow(SAMPLE)!!
        assertEquals("pending", row.str("status"))
        assertEquals(1, row.int("snoozes"))
    }

    @Test
    fun `snooze refused near expiry`() {
        val dev = mkdevice("laptop-bbbb0003")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:52:00Z") // 8 min left, snooze is 10

        assertEquals("near_expiry", dev.engine.snooze(SAMPLE))
        assertEquals(listOf("fired"), dev.sampleEvents(SAMPLE).map { it.str("ev") })
    }

    /** Past the window the sample is dead, not merely un-snoozeable (C2 F4). */
    @Test
    fun `snooze after expiry is refused as expired`() {
        val dev = mkdevice("laptop-bbbb0005")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T17:30:00Z") // expiry was 17:00

        assertEquals("expired", dev.engine.snooze(SAMPLE))
        assertEquals(listOf("fired", "expired"), dev.sampleEvents(SAMPLE).map { it.str("ev") })
    }

    @Test
    fun `snooze refused at max`() {
        val dev = mkdevice("laptop-bbbb0004")
        val stream = fixedStream(
            extra = mapOf("overrides" to buildJsonObject { put("expiry_minutes", 120) })
        )
        dev.boot(baseConfig(listOf(stream)))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")

        repeat(3) { // max_snoozes default 3
            assertNull(dev.engine.snooze(SAMPLE))
            clock.advance(60)
            dev.engine.tick()
        }
        assertEquals("max_snoozes", dev.engine.snooze(SAMPLE))
    }

    @Test
    fun `answer measures latency from original time`() {
        val dev = mkdevice("laptop-bbbb0005")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")
        assertNull(dev.engine.snooze(SAMPLE))
        tickedTo(listOf(dev), clock, "2026-08-24T16:12:00Z")

        val row = dev.engine.answer(
            SAMPLE,
            buildJsonObject {
                put("tags", JsonArray(listOf(JsonPrimitive("work.writing"))))
                put("note", "hi")
            },
        )
        assertEquals("answered", row.str("status"))
        assertEquals(12 * 60, row.int("latency_s")) // from the original scheduled time
        assertFalse(row.bool("late", true))
        assertEquals(1, row.int("snoozes"))
        assertTrue(SAMPLE !in dev.notifier.active())
        // Tag vocabulary picked up for autocomplete.
        assertEquals(listOf("work.writing"), dev.db.suggestTags("s1.tags", "work"))
    }

    @Test
    fun `late answer from backlog`() {
        val dev = mkdevice("laptop-bbbb0006")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T17:30:00Z") // fired at 16:00, expired 17:00

        assertEquals("expired", dev.db.sampleRow(SAMPLE)!!.str("status"))
        val row = dev.engine.answer(SAMPLE, tags("late"))
        assertEquals("answered", row.str("status")) // answered outranks expired
        assertTrue(row.bool("late", false))
        assertEquals(90 * 60, row.int("latency_s"))
    }
}
