/** Config-change scenarios (test plan §2, "Config") — Kotlin side. */
package pes.scenarios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** Tier 3 charter C5 F2/F6: a config naming a protocol this build cannot
     * compute must not stop the scheduler — the other streams keep pinging and
     * the client can say which one it cannot honour. */
    @Test
    fun `unknown protocol disables only that stream`() {
        val future = JsonObject(
            fixedStream() + mapOf(
                "id" to JsonPrimitive("future"),
                "name" to JsonPrimitive("From a newer client"),
                "protocol" to buildJsonObject {
                    put("type", "quantum_poisson")
                    put("rate", 3)
                },
            )
        )
        val dev = mkdevice("laptop-cccc0009")
        dev.boot(baseConfig(listOf(fixedStream(), future)))

        clock.advance(24 * 3600)
        dev.engine.tick()

        assertTrue(dev.db.sampleRows(stream = "st").isNotEmpty())
        assertEquals(emptyList(), dev.db.sampleRows(stream = "future"))
        assertEquals(
            listOf("From a newer client (quantum_poisson)"),
            dev.engine.unknownProtocolStreams(dev.engine.clock.now()),
        )
    }

    /** Tier 3 charter C5 F6: `validateConfig` guards the apply path, but a
     * config can reach `config_cache` another way. Re-check what we actually
     * run so the client can take what it understands and say what it cannot. */
    @Test
    fun `config issues report what this build cannot run`() {
        val dev = mkdevice("laptop-cccc0010")
        dev.boot(baseConfig(listOf(fixedStream())))
        assertEquals(emptyList(), dev.engine.configIssues(dev.engine.clock.now()))

        val broken = JsonObject(
            fixedStream() + mapOf(
                "id" to JsonPrimitive("x"),
                "name" to JsonPrimitive("X"),
                "protocol" to buildJsonObject { put("type", "wat") },
            )
        )
        val config = JsonObject(
            dev.db.latestConfig()!! + mapOf(
                "version" to JsonPrimitive(999),
                "streams" to JsonArray(listOf(broken)),
            )
        )
        dev.db.upsertConfig(config)

        val issues = dev.engine.configIssues(dev.engine.clock.now())
        assertTrue("X (wat): this client cannot compute that protocol" in issues, "$issues")
        assertTrue(issues.any { "bad_protocol_type" in it }, "$issues")
        // ...and the engine still runs: a future version number is not fatal.
        dev.engine.tick()
    }
}
