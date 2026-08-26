/** Config-change scenarios (test plan §2, "Config") — Kotlin side. */
package pes.scenarios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.io.TempDir
import pes.FakeClock
import pes.core.bool
import pes.core.obj
import pes.core.str
import pes.store.LocalFolderStore

class ConfigScenarioTest {
    @TempDir
    lateinit var tmp: File

    private val clock = FakeClock(T0)

    private fun mkdevice(name: String) =
        SimDevice(name, tmp, LocalFolderStore(File(tmp, "cloud")), clock)

    @Test
    fun `test ping works before new stream effective`() {
        // A just-staged stream (future effective_from) is usable for test
        // pings: streamConfig falls back to the latest config, so the sample
        // can be fired, notified with the right name, and answered.
        val dev = mkdevice("phone-dddd0004")
        dev.boot(baseConfig(listOf(fixedStream())))
        val errors = dev.engine.stageNewConfig(
            streams = listOf(
                fixedStream(),
                fixedStream(sid = "st2", extra = mapOf("name" to JsonPrimitive("New stream"))),
            ),
            defaults = dev.db.latestConfig()!!.obj("defaults"),
            timezone = "America/Los_Angeles",
            effectiveFrom = "2026-08-25T07:00:00Z", // tomorrow
        )
        assertTrue(errors.isEmpty())

        val sample = dev.engine.fireTestPing("st2")
        assertEquals("New stream", dev.notifier.shown.last().second)
        val row = dev.engine.answer(
            sample,
            buildJsonObject { put("tags", JsonArray(listOf(JsonPrimitive("works")))) },
        )
        assertEquals("answered", row.str("status"))
        assertTrue(row.bool("test", false))
        // Real scheduling still gated: nothing for st2 before its effective_from.
        assertTrue(
            dev.db.dueSchedule("2026-08-25T07:00:00Z").none { it.stream == "st2" }
        )
    }
}
