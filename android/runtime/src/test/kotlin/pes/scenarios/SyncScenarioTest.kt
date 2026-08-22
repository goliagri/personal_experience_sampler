/** Sync and merge scenarios (test plan §2, "Sync and merge"). Mirrors
 * `desktop/tests/scenarios/test_sync_scenarios.py` — the Milestone 4 gate
 * requires these to pass on the Kotlin side. */
package pes.scenarios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.core.int
import pes.core.str
import pes.store.LocalFolderStore
import pes.store.parseJson

private const val SAMPLE = "st|2026-08-24T16:00:00Z"

class SyncScenarioTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock(T0)
    private val cloud by lazy { RecordingStore(LocalFolderStore(File(tmp, "cloud"))) }

    private fun mkdevice(name: String) = SimDevice(name, tmp, cloud, clock)

    private fun tags(vararg values: String) = buildJsonObject {
        put("tags", JsonArray(values.map { JsonPrimitive(it) }))
    }

    @Test
    fun `snooze on phone answer on laptop merges`() {
        val phone = mkdevice("phone-cccc0001")
        val laptop = mkdevice("laptop-cccc0002")
        phone.boot(baseConfig(listOf(fixedStream())))
        laptop.boot(baseConfig(listOf(fixedStream())))

        tickedTo(listOf(phone, laptop), clock, "2026-08-24T16:00:00Z")
        assertTrue(SAMPLE in phone.notifier.active())
        assertNull(phone.engine.snooze(SAMPLE))

        phone.syncer.sync()
        laptop.syncer.sync() // imports phone's fired + snoozed
        var row = laptop.db.sampleRow(SAMPLE)!!
        assertEquals("pending", row.str("status"))
        assertEquals(1, row.int("snoozes"))

        laptop.engine.answer(SAMPLE, tags("merged"))
        laptop.syncer.sync()
        phone.syncer.sync() // imports the answer; cancels the local notification

        for (dev in listOf(phone, laptop)) {
            row = dev.db.sampleRow(SAMPLE)!!
            assertEquals("answered", row.str("status"))
            assertEquals(1, row.int("snoozes"))
            assertEquals("laptop-cccc0002", row.str("answered_on"))
            assertEquals(
                listOf("laptop-cccc0002", "phone-cccc0001"),
                (row.getValue("fired_on") as JsonArray).map { (it as JsonPrimitive).content }.sorted(),
            )
            assertEquals(0, row.int("latency_s")) // clock never advanced past 16:00
        }
        assertTrue(SAMPLE !in phone.notifier.active())
    }

    @Test
    fun `answered never downgraded`() {
        val phone = mkdevice("phone-cccc0003")
        val laptop = mkdevice("laptop-cccc0004")
        phone.boot(baseConfig(listOf(fixedStream())))
        laptop.boot(baseConfig(listOf(fixedStream())))

        tickedTo(listOf(phone, laptop), clock, "2026-08-24T16:05:00Z")
        laptop.engine.answer(SAMPLE, tags("done"))
        laptop.syncer.sync()

        // Phone, offline, expires the sample locally; then finally syncs.
        tickedTo(listOf(phone), clock, "2026-08-24T17:05:00Z")
        assertEquals("expired", phone.db.sampleRow(SAMPLE)!!.str("status"))
        phone.syncer.sync()
        assertEquals("answered", phone.db.sampleRow(SAMPLE)!!.str("status"))

        laptop.syncer.sync() // imports phone's expired; answer still wins
        assertEquals("answered", laptop.db.sampleRow(SAMPLE)!!.str("status"))
    }

    @Test
    fun `month upload only when unsynced and byte identical`() {
        val dev = mkdevice("laptop-cccc0005")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")

        dev.syncer.sync()
        val path = "events/laptop-cccc0005/2026-08.jsonl"
        val first = cloud.get(path)
        assertNotNull(first)
        assertTrue(first.decodeToString().endsWith("\n"))

        cloud.puts.clear()
        dev.syncer.sync() // nothing new -> month not re-uploaded
        assertTrue(cloud.puts.none { it.startsWith("events/") })
        assertTrue(cloud.get(path)!!.contentEquals(first))

        dev.engine.snooze(SAMPLE)
        cloud.puts.clear()
        dev.syncer.sync()
        assertEquals(listOf(path), cloud.puts.filter { it.startsWith("events/") })
        assertTrue(cloud.get(path)!!.decodeToString().startsWith(first.decodeToString())) // append-only growth
    }

    @Test
    fun `sync is noop against unchanged cloud`() {
        val dev = mkdevice("laptop-cccc0006")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")
        dev.engine.answer(SAMPLE, tags("x"))

        dev.syncer.sync()
        val snapshot = cloud.list("").associateWith { cloud.get(it)!!.decodeToString() }
        val eventsBefore = dev.db.allSampleEvents().size

        val result = dev.syncer.sync()
        assertEquals(snapshot, cloud.list("").associateWith { cloud.get(it)!!.decodeToString() })
        assertEquals(eventsBefore, dev.db.allSampleEvents().size)
        assertTrue(result.imported.isEmpty() && result.exported.isEmpty())
        assertEquals(0, result.backfilled)
    }

    @Test
    fun `export regenerated only on change`() {
        val dev = mkdevice("laptop-cccc0007")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")
        dev.engine.answer(
            SAMPLE,
            buildJsonObject {
                put("tags", JsonArray(listOf(JsonPrimitive("one"))))
                put("note", "first")
            },
        )

        var result = dev.syncer.sync()
        assertEquals(listOf("st"), result.exported)
        val csv1 = cloud.get("exports/st.csv")!!.decodeToString()
        assertTrue("one" in csv1)
        assertNotNull(cloud.get("exports/st.columns.json"))

        assertTrue(dev.syncer.sync().exported.isEmpty()) // unchanged rows -> no regen

        tickedTo(listOf(dev), clock, "2026-08-24T22:00:00Z") // second ping fires
        dev.engine.answer("st|2026-08-24T22:00:00Z", tags("two"))
        result = dev.syncer.sync()
        assertEquals(listOf("st"), result.exported)
        val csv2 = cloud.get("exports/st.csv")!!.decodeToString()
        assertTrue("one" in csv2 && "two" in csv2)
    }

    @Test
    fun `devices doc written with primary role`() {
        val laptop = mkdevice("laptop-cccc0008")
        val phone = mkdevice("phone-cccc0009")
        laptop.boot(baseConfig(listOf(fixedStream())))
        phone.boot(baseConfig(listOf(fixedStream())))

        laptop.syncer.sync()
        phone.syncer.sync()

        val laptopDoc = parseJson(cloud.get("devices/laptop-cccc0008.json")!!.decodeToString())
        val phoneDoc = parseJson(cloud.get("devices/phone-cccc0009.json")!!.decodeToString())
        assertEquals("primary", laptopDoc.str("role")) // first claimant keeps it
        assertEquals("", phoneDoc.str("role"))
    }
}
