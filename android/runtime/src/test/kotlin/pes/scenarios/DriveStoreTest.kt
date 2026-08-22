/** Drive-store tests (test plan §2): CloudStore contract over the fake HTTP
 * layer, root-folder lifecycle, modifiedTime gating, interrupted uploads, and
 * a full cross-device sync scenario running on Drive. Mirrors
 * `desktop/tests/scenarios/test_drive_store.py`. */
package pes.scenarios

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.Syncer
import pes.core.int
import pes.core.str
import pes.store.Db
import pes.store.DriveStore
import pes.store.FOLDER_MIME
import pes.store.MultipleRootsError
import pes.store.ROOT_FOLDER_NAME

class DriveStoreTest {
    @TempDir
    lateinit var tmp: File

    private val fake = FakeDrive()
    private val clock = FakeClock(T0)

    private fun mkstore(name: String = "store", db: Db? = null): DriveStore =
        DriveStore(fake, db ?: Db(File(tmp, "$name.sqlite").path))

    private fun driveDevice(name: String): SimDevice {
        val device = SimDevice(name, tmp, pes.store.LocalFolderStore(File(tmp, "unused")), clock)
        device.syncer = Syncer(device.engine, DriveStore(fake, device.db))
        return device
    }

    // -- CloudStore contract ----------------------------------------------

    @Test
    fun `roundtrip contract`() {
        val store = mkstore()
        assertNull(store.get("a/b/c.txt"))
        assertNull(store.metadata("a/b/c.txt"))
        assertTrue(store.list("a").isEmpty())

        store.put("a/b/c.txt", "one".toByteArray())
        assertContentEquals("one".toByteArray(), store.get("a/b/c.txt"))
        val meta1 = store.metadata("a/b/c.txt")!!
        assertEquals(3, meta1.size)

        store.put("a/b/c.txt", "two!".toByteArray()) // overwrite updates in place
        assertContentEquals("two!".toByteArray(), store.get("a/b/c.txt"))
        val meta2 = store.metadata("a/b/c.txt")!!
        assertNotEquals(meta1.etag, meta2.etag)
        assertEquals(4, meta2.size)
        assertEquals(1, fake.byName("c.txt").size)

        assertFalse(store.putIfAbsent("a/b/c.txt", "three".toByteArray()))
        assertContentEquals("two!".toByteArray(), store.get("a/b/c.txt"))
        assertTrue(store.putIfAbsent("a/d.txt", "new".toByteArray()))

        store.put("a/b/e.txt", "x".toByteArray())
        assertEquals(listOf("a/b/c.txt", "a/b/e.txt", "a/d.txt"), store.list("a"))
        assertEquals(listOf("a/b/c.txt", "a/b/e.txt"), store.list("a/b"))
        assertTrue(store.list("missing").isEmpty())

        assertFailsWith<IllegalArgumentException> { store.put("../escape.txt", "no".toByteArray()) }
    }

    // -- root folder lifecycle (§8.5) --------------------------------------

    @Test
    fun `root created once then adopted by id`() {
        val db = Db(File(tmp, "dev1.sqlite").path)
        val store = mkstore(db = db)
        store.put("config/current.json", "{}".toByteArray())
        assertEquals(1, fake.byName(ROOT_FOLDER_NAME).size)
        val rootId = fake.byName(ROOT_FOLDER_NAME)[0].id

        // Rename in Drive is harmless: the store addresses the root by stored ID.
        fake.files.getValue(rootId).name = "Renamed by hand"
        val store2 = DriveStore(fake, db) // fresh instance, same local cache
        assertContentEquals("{}".toByteArray(), store2.get("config/current.json"))
        store2.put("state.json", "{}".toByteArray())
        assertEquals(0, fake.byName(ROOT_FOLDER_NAME).size) // no duplicate root created
    }

    @Test
    fun `second device adopts existing root`() {
        mkstore("dev1").put("config/current.json", "{}".toByteArray())
        val store2 = mkstore("dev2")
        assertContentEquals("{}".toByteArray(), store2.get("config/current.json"))
        assertEquals(1, fake.byName(ROOT_FOLDER_NAME).size)
    }

    @Test
    fun `two roots refuses`() {
        fake.add(ROOT_FOLDER_NAME, FOLDER_MIME, emptyList())
        fake.add(ROOT_FOLDER_NAME, FOLDER_MIME, emptyList())
        assertFailsWith<MultipleRootsError> { mkstore().get("config/current.json") }
    }

    @Test
    fun `root recreated after deletion clears stale ids`() {
        val db = Db(File(tmp, "store.sqlite").path)
        val store = mkstore(db = db)
        store.put("a/b.txt", "x".toByteArray())
        for (record in fake.files.values) record.trashed = true
        val store2 = DriveStore(fake, db)
        assertNull(store2.get("a/b.txt"))
        store2.put("a/b.txt", "y".toByteArray()) // rebuilt from a fresh root
        assertContentEquals("y".toByteArray(), store2.get("a/b.txt"))
        assertEquals(1, fake.byName(ROOT_FOLDER_NAME).size)
    }

    // -- sync over Drive ---------------------------------------------------

    @Test
    fun `cross device answer merges over drive`() {
        val config = baseConfig(listOf(fixedStream()))
        val phone = driveDevice("phone")
        val laptop = driveDevice("laptop")
        phone.boot(config)
        phone.syncer.sync()
        laptop.boot()
        laptop.syncer.sync()
        assertEquals(1, laptop.db.latestConfig()!!.int("version"))

        clock.set(T0 + 3600) // 09:00 local: the fixed ping fires on both
        phone.engine.tick()
        laptop.engine.tick()
        val sampleId = phone.engine.activeSamples()[0].str("sample")
        laptop.engine.answer(
            sampleId,
            buildJsonObject { put("tags", JsonArray(listOf(JsonPrimitive("work")))) },
        )
        laptop.syncer.sync()
        phone.syncer.sync()

        assertEquals("answered", phone.db.sampleRow(sampleId)!!.str("status"))
        assertTrue(Pair("cancel", sampleId) in phone.notifier.log)
    }

    @Test
    fun `modified time gates downloads`() {
        val config = baseConfig(listOf(fixedStream()))
        val phone = driveDevice("phone")
        val laptop = driveDevice("laptop")
        phone.boot(config)
        clock.set(T0 + 3600)
        phone.engine.tick()
        phone.syncer.sync()
        laptop.boot()
        laptop.syncer.sync() // imports phone's events

        fake.calls.clear()
        laptop.syncer.sync() // nothing changed in the cloud
        val phoneMonth = fake.files.values.filter { it.name.endsWith(".jsonl") }.map { it.id }
        val downloaded = fake.mediaDownloads()
        assertTrue(phoneMonth.none { it in downloaded })
    }

    @Test
    fun `interrupted upload retried without corruption`() {
        val config = baseConfig(listOf(fixedStream()))
        val phone = driveDevice("phone")
        phone.boot(config)
        clock.set(T0 + 3600)
        phone.engine.tick()

        fake.failAfter = fake.calls.size + 3 // dies partway through the sync
        assertFailsWith<IOException> { phone.syncer.sync() }
        fake.failAfter = null
        phone.syncer.sync() // retried on the next trigger (§8.4 idempotence)

        assertTrue(phone.db.unsyncedMonths("phone").isEmpty()) // upload completed
        val stored = fake.files.values.filter { it.name.endsWith(".jsonl") }
        assertEquals(1, stored.size)
        val lines = stored[0].content.decodeToString().split("\n").filter { it.isNotEmpty() }
        val expected = phone.db.monthLines("phone", stored[0].name.removeSuffix(".jsonl"))
        assertEquals(expected, lines)
    }
}
