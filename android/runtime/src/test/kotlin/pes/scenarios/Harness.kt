/** Harness for scenario tests (test plan §2): multiple simulated devices
 * sharing one local-folder cloud, driven by a single fake clock. Mirrors
 * `desktop/tests/scenarios/conftest.py`. */
package pes.scenarios

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pes.Engine
import pes.FakeClock
import pes.RecordingNotifier
import pes.Syncer
import pes.core.parseUtc
import pes.core.str
import pes.store.CloudMeta
import pes.store.CloudStore
import pes.store.Db

/** Pass-through store that records every put path (for idempotence checks). */
class RecordingStore(private val inner: CloudStore) : CloudStore {
    val puts = mutableListOf<String>()

    override fun get(path: String): ByteArray? = inner.get(path)

    override fun put(path: String, data: ByteArray) {
        puts.add(path)
        inner.put(path, data)
    }

    override fun putIfAbsent(path: String, data: ByteArray): Boolean {
        val written = inner.putIfAbsent(path, data)
        if (written) puts.add(path)
        return written
    }

    override fun list(prefix: String): List<String> = inner.list(prefix)

    override fun metadata(path: String): CloudMeta? = inner.metadata(path)

    override fun delete(path: String) = inner.delete(path)
}

const val SEED = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"
const val TZ = "America/Los_Angeles"
val T0 = parseUtc("2026-08-24T15:00:00Z") // Monday 08:00 in America/Los_Angeles

val SURVEY: JsonObject = buildJsonObject {
    put("id", "s1")
    put("version", 1)
    put("title", "Test survey")
    put(
        "fields",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "tags")
                    put("type", "tags")
                    put("label", "Tags")
                    put("quick", true)
                }
            )
            add(
                buildJsonObject {
                    put("id", "note")
                    put("type", "text")
                    put("label", "Note")
                    put("multiline", false)
                }
            )
        },
    )
}

fun fixedStream(
    times: List<String> = listOf("09:00", "15:00"),
    sid: String = "st",
    extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
): JsonObject = buildJsonObject {
    put("id", sid)
    put("name", "Test stream")
    put("enabled", true)
    put("seed", SEED)
    put(
        "protocol",
        buildJsonObject {
            put("type", "fixed_times")
            put("times_local", buildJsonArray { times.forEach { add(JsonPrimitive(it)) } })
        },
    )
    put("quiet_zones", buildJsonArray {})
    put(
        "survey",
        buildJsonObject {
            put("id", "s1")
            put("version", 1)
        },
    )
    for ((k, v) in extra) put(k, v)
}

fun baseConfig(
    streams: List<JsonObject>,
    version: Int = 1,
    baseVersion: Int = 0,
    effectiveFrom: String = "2026-08-01T00:00:00Z",
    writtenAt: String = "2026-08-01T00:00:00Z",
    writtenBy: String = "test",
    timezone: String = TZ,
    defaults: JsonObject? = null,
): JsonObject = buildJsonObject {
    put("version", version)
    put("base_version", baseVersion)
    put("written_by", writtenBy)
    put("written_at", writtenAt)
    put("effective_from", effectiveFrom)
    put("timezone", timezone)
    put(
        "defaults",
        defaults ?: buildJsonObject {
            put("snooze_minutes", 10)
            put("max_snoozes", 3)
            put("expiry_minutes", 60)
            put("backlog_hours", 12)
            put("location", "off")
        },
    )
    put("streams", buildJsonArray { streams.forEach { add(it) } })
}

class SimDevice(val name: String, tmpDir: File, cloud: CloudStore, clock: FakeClock) {
    val db = Db(File(tmpDir, "$name.sqlite").path)
    val notifier = RecordingNotifier()
    val engine = Engine(db, name, notifier, clock)
    var syncer = Syncer(engine, cloud)

    init {
        db.kvSet("device", "device_id", name)
    }

    fun boot(config: JsonObject? = null) {
        if (config != null) engine.applyConfig(config)
        db.upsertSurvey(SURVEY)
        engine.start()
    }

    fun sampleEvents(sampleId: String): List<JsonObject> =
        db.eventsForSample(sampleId).map { it.third }

    fun ownEvents(evType: String? = null): List<JsonObject> =
        db.allSampleEvents().filter { it.str("dev") == name }
            .filter { evType == null || it.str("ev") == evType }
}

fun tickedTo(devices: List<SimDevice>, clock: FakeClock, iso: String) {
    val target = parseUtc(iso)
    while (clock.now() < target) {
        clock.advance(minOf(240, target - clock.now()))
        for (dev in devices) dev.engine.tick()
    }
}
