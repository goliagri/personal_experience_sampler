/** Backfill and clock scenarios (test plan §2, "Backfill and clock").
 * Mirrors `desktop/tests/scenarios/test_backfill_scenarios.py`. */
package pes.scenarios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.core.fmtUtc
import pes.core.optStr
import pes.core.parseUtc
import pes.core.str
import pes.store.LocalFolderStore

const val DAY1_0900 = "st|2026-08-24T16:00:00Z" // 09:00 local
const val DAY1_1500 = "st|2026-08-24T22:00:00Z" // 15:00 local

class BackfillScenarioTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock(T0)

    private fun mkdevice(name: String) =
        SimDevice(name, tmp, LocalFolderStore(File(tmp, "cloud")), clock)

    @Test
    fun `overnight off classified once`() {
        // All devices off overnight: on wake exactly one row per candidate,
        // watermark advances, a second run adds nothing.
        val dev = mkdevice("laptop-aaaa0001")
        dev.boot(baseConfig(listOf(fixedStream())))
        dev.engine.tick() // nothing due yet

        clock.advance(24 * 3600) // sleep through both of Monday's pings
        dev.engine.tick() // jump detected -> backfill

        for (sample in listOf(DAY1_0900, DAY1_1500)) {
            assertEquals(listOf("unobserved"), dev.sampleEvents(sample).map { it.str("ev") }, sample)
            assertEquals("unobserved", dev.db.sampleRow(sample)!!.str("status"))
        }

        // Idempotent: further runs emit nothing.
        assertTrue(dev.engine.backfillNow().isEmpty())
        dev.engine.tick()
        for (sample in listOf(DAY1_0900, DAY1_1500)) {
            assertEquals(1, dev.sampleEvents(sample).size)
        }
    }

    @Test
    fun `clock jump window covered once`() {
        val dev = mkdevice("laptop-aaaa0002")
        dev.boot(baseConfig(listOf(fixedStream())))
        dev.engine.tick()

        clock.advance(2 * 3600) // hibernate through the 09:00 ping + its expiry
        dev.engine.tick()
        assertEquals(listOf("unobserved"), dev.sampleEvents(DAY1_0900).map { it.str("ev") })

        clock.advance(2 * 3600) // second jump; already-covered window untouched
        dev.engine.tick()
        assertEquals(1, dev.sampleEvents(DAY1_0900).size)
    }

    @Test
    fun `open window still fires after short sleep`() {
        // A sample whose active window is still open when the device wakes is
        // fired late (fired.t lags scheduled), not written off as unobserved.
        val dev = mkdevice("laptop-aaaa0003")
        dev.boot(baseConfig(listOf(fixedStream())))
        dev.engine.tick()

        clock.set(parseUtc("2026-08-24T16:30:00Z")) // woke 30 min late; expiry 60
        dev.engine.tick()
        val events = dev.sampleEvents(DAY1_0900)
        assertEquals(listOf("fired"), events.map { it.str("ev") })
        assertEquals("2026-08-24T16:00:00Z", events[0].str("scheduled"))
        assertEquals("2026-08-24T16:30:00Z", events[0].str("t"))
        assertTrue(DAY1_0900 in dev.notifier.active())
    }

    @Test
    fun `quiet mode window backfills suppressed`() {
        val dev = mkdevice("laptop-aaaa0004")
        dev.boot(baseConfig(listOf(fixedStream())))
        dev.engine.setQuiet("indefinite")
        dev.engine.tick()

        clock.advance(24 * 3600)
        dev.engine.tick()
        for (sample in listOf(DAY1_0900, DAY1_1500)) {
            val events = dev.sampleEvents(sample)
            assertEquals(listOf("suppressed"), events.map { it.str("ev") }, sample)
            assertEquals("quiet_mode", events[0].str("reason"))
        }
    }

    @Test
    fun `quiet zone candidate suppressed with reason`() {
        val stream = fixedStream(
            extra = mapOf(
                "quiet_zones" to buildJsonArray {
                    add(
                        buildJsonObject {
                            put("days", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("mon")) })
                            put("from", "08:30")
                            put("to", "10:00") // covers 09:00 local
                        }
                    )
                }
            )
        )
        val dev = mkdevice("laptop-aaaa0005")
        dev.boot(baseConfig(listOf(stream)))
        dev.engine.tick()

        clock.advance(24 * 3600)
        dev.engine.tick()
        val events = dev.sampleEvents(DAY1_0900)
        assertEquals(listOf(Pair("suppressed", "quiet_zone")), events.map { Pair(it.str("ev"), it.optStr("reason")) })
        assertEquals(listOf("unobserved"), dev.sampleEvents(DAY1_1500).map { it.str("ev") })
    }

    @Test
    fun `watermark advances`() {
        val dev = mkdevice("laptop-aaaa0006")
        dev.boot(baseConfig(listOf(fixedStream())))
        clock.advance(24 * 3600)
        dev.engine.tick()
        // Held back exactly one expiry (60 min) behind now.
        assertEquals(
            fmtUtc(T0 + 24 * 3600 - 3600),
            dev.db.kvGet("sync_meta", "last_materialized_at"),
        )
    }
}
