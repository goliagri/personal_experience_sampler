/** Milestone 5 scenarios (test plan §2 / §5.5): restore procedure (§8.6),
 * weekly/monthly snapshots with retention and the primary-role handoff (§9),
 * and Drive recovery from a deleted sub-folder. Mirrors
 * `desktop/tests/scenarios/test_sync_hardening.py`. */
package pes.scenarios

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.Syncer
import pes.core.fmtUtc
import pes.core.parseUtc
import pes.core.str
import pes.lastSunday0300
import pes.store.Db
import pes.store.DriveStore
import pes.store.FOLDER_MIME
import pes.store.LocalFolderStore
import pes.store.parseJson

private const val SAMPLE = "st|2026-08-24T16:00:00Z"

class SyncHardeningTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock(T0)
    private val cloudDir by lazy { File(tmp, "cloud") }
    private val cloud by lazy { LocalFolderStore(cloudDir) }

    private fun mkdevice(name: String) = SimDevice(name, tmp, cloud, clock)

    private fun tags(vararg values: String) = buildJsonObject {
        put("tags", kotlinx.serialization.json.buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private fun wipe() {
        cloudDir.deleteRecursively()
        cloudDir.mkdirs()
    }

    private fun zipNames(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) out.add((z.nextEntry ?: break).name)
        }
        return out
    }

    // -- restore (§8.6) ---------------------------------------------------

    @Test
    fun `restore rebuilds lost folder from cache`() {
        val laptop = mkdevice("laptop-ff01")
        val phone = mkdevice("phone-ff02")
        laptop.boot(baseConfig(listOf(fixedStream())))
        phone.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(laptop, phone), clock, "2026-08-24T16:00:00Z")
        phone.engine.answer(SAMPLE, tags("a"))
        phone.syncer.sync()
        laptop.syncer.sync()
        assertEquals("answered", laptop.db.sampleRow(SAMPLE)!!.str("status"))

        wipe()
        val result = laptop.syncer.restore()
        assertEquals(
            setOf("events/laptop-ff01/2026-08.jsonl", "events/phone-ff02/2026-08.jsonl"),
            result.uploaded.toSet(),
        )
        assertTrue(result.restored.isEmpty())
        assertTrue("config/current.json" in result.docs && "surveys/s1/v1.json" in result.docs)
        assertTrue(cloud.get("config/history/config_v0001.json") != null)
        assertTrue(cloud.get("manifest.json") != null)
        assertTrue("\"answered\"" in cloud.get("events/phone-ff02/2026-08.jsonl")!!.decodeToString())

        val tablet = mkdevice("tablet-ff03")
        tablet.boot()
        tablet.syncer.sync()
        assertEquals("answered", tablet.db.sampleRow(SAMPLE)!!.str("status"))
        val again = laptop.syncer.restore()
        assertTrue(again.uploaded.isEmpty() && again.docs.isEmpty())
    }

    @Test
    fun `restore never overwrites other devices files`() {
        val laptop = mkdevice("laptop-ff04")
        val phone = mkdevice("phone-ff05")
        laptop.boot(baseConfig(listOf(fixedStream())))
        phone.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(laptop, phone), clock, "2026-08-24T16:00:00Z")
        phone.syncer.sync()
        val older = cloud.get("events/phone-ff05/2026-08.jsonl")!!
        phone.engine.answer(SAMPLE, tags("a"))
        phone.syncer.sync()
        laptop.syncer.sync()

        cloud.put("events/phone-ff05/2026-08.jsonl", older) // cloud rolled back
        val result = laptop.syncer.restore()
        assertTrue(cloud.get("events/phone-ff05/2026-08.jsonl")!!.contentEquals(older))
        assertEquals(listOf("restored/laptop-ff04/phone-ff05/2026-08.jsonl"), result.restored)
        val fragment = cloud.get("restored/laptop-ff04/phone-ff05/2026-08.jsonl")!!.decodeToString()
        assertTrue(fragment.count { it == '\n' } == 1 && "\"answered\"" in fragment)

        val tablet = mkdevice("tablet-ff06")
        tablet.boot()
        tablet.syncer.sync()
        assertEquals("answered", tablet.db.sampleRow(SAMPLE)!!.str("status"))
    }

    @Test
    fun `restore reuploads own log when cloud copy is stale`() {
        val dev = mkdevice("laptop-ff07")
        dev.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(dev), clock, "2026-08-24T16:00:00Z")
        dev.syncer.sync()
        cloud.put("events/${dev.name}/2026-08.jsonl", ByteArray(0))
        val result = dev.syncer.restore()
        assertEquals(listOf("events/${dev.name}/2026-08.jsonl"), result.uploaded)
        assertTrue("\"fired\"" in cloud.get("events/${dev.name}/2026-08.jsonl")!!.decodeToString())
    }

    // -- snapshots (§9) ---------------------------------------------------

    @Test
    fun `last sunday 0300`() {
        val tz = "America/Los_Angeles"
        assertEquals("2026-08-23", lastSunday0300(parseUtc("2026-08-24T15:00:00Z"), tz))
        assertEquals("2026-08-16", lastSunday0300(parseUtc("2026-08-23T09:59:00Z"), tz))
        assertEquals("2026-08-23", lastSunday0300(parseUtc("2026-08-23T10:00:00Z"), tz))
        assertEquals("2026-08-23", lastSunday0300(parseUtc("2026-08-29T23:00:00Z"), tz))
    }

    @Test
    fun `weekly snapshot promotion and retention`() {
        val laptop = mkdevice("laptop-ff08")
        val phone = mkdevice("phone-ff09")
        laptop.boot(baseConfig(listOf(fixedStream())))
        phone.boot(baseConfig(listOf(fixedStream())))
        tickedTo(listOf(laptop, phone), clock, "2026-08-24T16:00:00Z")
        val r1 = laptop.syncer.sync()
        assertEquals("primary", r1.role)
        assertEquals("snapshots/weekly/2026-08-23.zip", r1.snapshot)
        assertEquals("snapshots/monthly/2026-08.zip", r1.snapshotMonthly)
        val names = zipNames(cloud.get("snapshots/weekly/2026-08-23.zip")!!)
        assertTrue("events/laptop-ff08/2026-08.jsonl" in names && "config/current.json" in names)
        assertTrue(names.none { it.startsWith("snapshots/") })

        clock.advance(3600)
        assertNull(laptop.syncer.sync().snapshot)
        assertNull(phone.syncer.sync().snapshot)
        assertEquals(
            listOf("snapshots/monthly/2026-08.zip", "snapshots/weekly/2026-08-23.zip"),
            cloud.list("snapshots"),
        )

        clock.set(parseUtc("2026-08-30T10:00:00Z"))
        val r2 = laptop.syncer.sync()
        assertEquals("snapshots/weekly/2026-08-30.zip", r2.snapshot)
        assertNull(r2.snapshotMonthly)
        clock.set(parseUtc("2026-09-06T10:00:00Z"))
        assertEquals("snapshots/monthly/2026-09.zip", laptop.syncer.sync().snapshotMonthly)

        for (i in 0 until 14) {
            cloud.put("snapshots/weekly/2025-%02d-%02d.zip".format((i % 12) + 1, (i / 12) + 1), "old".toByteArray())
        }
        laptop.syncer.sync()
        val weekly = cloud.list("snapshots/weekly")
        assertEquals(12, weekly.size)
        assertTrue("snapshots/weekly/2026-09-06.zip" in weekly)
        assertFalse("snapshots/weekly/2025-01-01.zip" in weekly)
    }

    // -- primary role handoff (§9) ---------------------------------------

    @Test
    fun `primary tie break lower device id keeps`() {
        val a = mkdevice("laptop-ff10")
        val b = mkdevice("phone-ff11")
        a.boot(baseConfig(listOf(fixedStream())))
        b.boot(baseConfig(listOf(fixedStream())))
        a.db.kvSet("device", "role", "primary")
        b.db.kvSet("device", "role", "primary")
        assertEquals("primary", b.syncer.sync().role)
        assertEquals("primary", a.syncer.sync().role)
        assertEquals("", b.syncer.sync().role)
        assertEquals("", parseJson(cloud.get("devices/phone-ff11.json")!!.decodeToString()).str("role"))
        assertEquals("primary", parseJson(cloud.get("devices/laptop-ff10.json")!!.decodeToString()).str("role"))
    }

    @Test
    fun `secondary claims primary after 14 silent days`() {
        val laptop = mkdevice("laptop-ff12")
        val phone = mkdevice("phone-ff13")
        laptop.boot(baseConfig(listOf(fixedStream())))
        phone.boot(baseConfig(listOf(fixedStream())))
        laptop.syncer = Syncer(laptop.engine, cloud, platform = "desktop")
        assertEquals("primary", laptop.syncer.sync().role)
        assertEquals("", phone.syncer.sync().role)
        clock.advance(13 * 86400)
        assertEquals("", phone.syncer.sync().role)
        clock.advance(2 * 86400)
        assertEquals("primary", phone.syncer.sync().role)
        assertTrue(cloud.list("snapshots/weekly").isNotEmpty())
        assertEquals("primary", laptop.syncer.sync().role) // laptop-ff12 < phone-ff13
        assertEquals("", phone.syncer.sync().role)
    }

    @Test
    fun `device doc records platform`() {
        val dev = mkdevice("phone-ff14")
        dev.boot(baseConfig(listOf(fixedStream())))
        dev.syncer.sync()
        val doc = parseJson(cloud.get("devices/phone-ff14.json")!!.decodeToString())
        assertEquals("android", doc.str("platform"))
        assertEquals(fmtUtc(clock.now()), doc.str("last_sync"))
    }

    // -- Drive: deleted sub-folder recovery ------------------------------

    @Test
    fun `drive recovers from deleted subfolder`() {
        val fake = FakeDrive()
        val db = Db(File(tmp, "d.sqlite").path)
        val store = DriveStore(fake, db)
        store.put("events/dev-a/2026-08.jsonl", "one\n".toByteArray())
        val folderId = fake.byName("dev-a").first { it.mimeType == FOLDER_MIME }.id
        fake.trash(folderId)

        val fresh = DriveStore(fake, db)
        assertNull(fresh.get("events/dev-a/2026-08.jsonl"))
        fresh.put("events/dev-a/2026-08.jsonl", "two\n".toByteArray())
        assertEquals("two\n", fresh.get("events/dev-a/2026-08.jsonl")!!.decodeToString())
        assertEquals(1, fake.byName("dev-a").count { it.mimeType == FOLDER_MIME })
        assertEquals(listOf("events/dev-a/2026-08.jsonl"), fresh.list("events"))
        fresh.delete("events/dev-a/2026-08.jsonl")
        assertTrue(fresh.list("events").isEmpty())
        assertNull(fresh.metadata("events/dev-a/2026-08.jsonl"))
    }
}
