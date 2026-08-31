package pes.app

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pes.FakeClock
import pes.core.str
import pes.store.Db

/**
 * Compose-test fixture: an [EngineHost] over a temp DB seeded with a small
 * fixed-times config and a survey exercising every field type, driven by a
 * [FakeClock]. Mirrors `android/tools/emu_seed.py` so the same scenario
 * (Tue 11:55 America/Los_Angeles, ping at 12:00, expiry 60, snooze 10 ×3)
 * is used by the on-device pytest suite and these screen tests.
 */
object Seeds {
    const val NOW = 1788288900L // 2026-09-01T18:55:00Z
    const val PING = "fixed|2026-09-01T19:00:00Z"
    const val PING_EPOCH = 1788289200L
    const val EVENING = "fixed|2026-09-02T03:00:00Z" // 20:00 local, index 1 of the day
    const val TZ = "America/Los_Angeles"

    fun survey(
        requiredMood: Boolean = false,
        curatedTags: Boolean = false,
        slider: Boolean = false,
        whereDisplay: String? = null,
        withDisplay: String? = null,
    ): String = """
        {"id":"dev","version":1,"title":"Dev survey","fields":[
          {"id":"tags","type":"tags","label":"What are you doing?","quick":true
             ${if (curatedTags) ""","curated":["work","rest","play"]""" else ""}},
          {"id":"mood","type":"number","label":"Mood (1-7)","min":1,"max":7,"integer":true,"required":$requiredMood
             ${if (slider) ""","display":"slider","end_labels":["awful","great"]""" else ""}},
          {"id":"where","type":"choice","label":"Where","cardinality":"single"
             ${whereDisplay?.let { ""","display":"$it"""" } ?: ""},
             "options":["home","work","out",{"value":"other","label":"Somewhere else"}]},
          {"id":"with","type":"choice","label":"With","cardinality":"multi"
             ${withDisplay?.let { ""","display":"$it"""" } ?: ""},
             "options":["alone","partner","friends","coworkers"]},
          {"id":"note","type":"text","label":"Note","multiline":true,"help":"Anything else?"}
        ]}
    """

    fun config(
        fixed: List<String> = listOf("12:00", "20:00"),
        fullEveryN: Int? = null,
        streams: Boolean = true,
    ): String {
        val times = fixed.joinToString(",") { "\"$it\"" }
        val every = fullEveryN?.let { ""","full_survey_every_n":$it""" } ?: ""
        val streamList = if (!streams) "[]" else """[
            {"id":"fixed","name":"Fixed times","enabled":true,"seed":"8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f01",
             "protocol":{"type":"fixed_times","times_local":[$times]},"quiet_zones":[],
             "survey":{"id":"dev","version":1}$every},
            {"id":"off","name":"Disabled stream","enabled":false,"seed":"8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f02",
             "protocol":{"type":"fixed_interval","interval_minutes":60},"quiet_zones":[],
             "survey":{"id":"dev","version":1}}
        ]"""
        return """
        {"version":2,"base_version":1,"written_by":"desktop-seed","written_at":"2026-09-01T18:55:00Z",
         "effective_from":"2026-09-01T18:55:00Z","timezone":"$TZ",
         "defaults":{"snooze_minutes":10,"max_snoozes":3,"expiry_minutes":60,"backlog_hours":12,"location":"off"},
         "streams":$streamList}
        """
    }

    fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
}

class TestHost(
    survey: String = Seeds.survey(),
    config: String = Seeds.config(),
    startEpoch: Long = Seeds.NOW,
) {
    val app: Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    val clock = FakeClock(startEpoch)
    val dbPath: String = File.createTempFile("pes-test", ".sqlite", app.cacheDir).path
    val host: EngineHost

    init {
        Db(dbPath).apply {
            kvSet("device", "device_id", "emu-test")
            upsertSurvey(Seeds.json(survey))
            upsertConfig(Seeds.json(config))
            close()
        }
        host = EngineHost(app, dbPath, clock)
    }

    /** Advance the clock to `epoch` and tick — fires/expires like the alarm receiver would. */
    fun at(epoch: Long) {
        clock.epoch = epoch
        host.call { it.tick() }
    }

    fun fire(): TestHost = apply { at(Seeds.PING_EPOCH + 3) }

    fun expire(): TestHost = apply { at(Seeds.PING_EPOCH + 3 * 3600) }

    fun events(sample: String): List<JsonObject> =
        host.call { e -> e.db.eventsForSample(sample).map { it.third } }

    fun evTypes(sample: String): List<String> = events(sample).map { it.str("ev") }

    fun sample(sample: String): JsonObject? = host.call { it.db.sampleRow(sample) }
}

/** `produceState` loads run on the engine thread, invisible to Compose's idle
 * detection; poll for the text instead. */
fun ComposeTestRule.awaitText(text: String, substring: Boolean = false, timeoutMs: Long = 5_000) {
    waitUntil(timeoutMs) {
        (this as SemanticsNodeInteractionsProvider)
            .onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
}

fun ComposeTestRule.awaitGone(text: String, substring: Boolean = false, timeoutMs: Long = 5_000) {
    waitUntil(timeoutMs) {
        (this as SemanticsNodeInteractionsProvider)
            .onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()
    }
}
