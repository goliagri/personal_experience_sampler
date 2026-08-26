/**
 * Runtime engine (spec §6.4–6.5): materialization, firing, snooze / skip /
 * answer / expiry, quiet mode, backfill, and refolding. Mirrors
 * `pes/engine.py`.
 *
 * Headless by design: the Android UI, the alarm receivers, and the scenario
 * tests all drive this class. Time comes from an injected clock;
 * notifications go to an injected `Notifier`. The ping path never touches the
 * network (local-first); sync is a separate step (`pes.Syncer`).
 */
package pes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pes.core.backfill as coreBackfill
import pes.core.BackfillSample
import pes.core.QuietChange
import pes.core.bool
import pes.core.fmtUtc
import pes.core.foldSample
import pes.core.int
import pes.core.isFull
import pes.core.obj
import pes.core.objList
import pes.core.optObj
import pes.core.optStr
import pes.core.parseUtc
import pes.core.resolveDay
import pes.core.str
import pes.core.validateConfig
import pes.store.Db
import pes.store.parseJson

val DEFAULTS: Map<String, JsonElement> = mapOf(
    "snooze_minutes" to JsonPrimitive(10),
    "max_snoozes" to JsonPrimitive(3),
    "expiry_minutes" to JsonPrimitive(60),
    "backlog_hours" to JsonPrimitive(12),
    "location" to JsonPrimitive("off"),
)

const val HORIZON_S = 48L * 3600
const val CLOCK_JUMP_S = 300L // a gap this large between ticks means we slept

class Engine(
    val db: Db,
    val deviceId: String,
    val notifier: Notifier,
    val clock: Clock = SystemClock(),
) {
    // -- config ----------------------------------------------------------

    /** Config version in effect at an instant (§6.1 step 1). */
    fun configAt(instant: Long): JsonObject? {
        val history = db.configHistory()
        var effective: JsonObject? = null
        for (doc in history) { // ascending version
            if (parseUtc(doc.str("effective_from")) <= instant) effective = doc
        }
        return effective ?: history.firstOrNull()
    }

    /** Stream definition at an instant. Falls back to the latest staged
     * config so a just-created stream is usable (test pings, answering)
     * before its effective_from; scheduling never goes through this — it
     * resolves the config history itself, so effective_from still gates
     * real pings. */
    fun streamConfig(streamId: String, instant: Long): JsonObject? =
        listOfNotNull(configAt(instant), db.latestConfig()).firstNotNullOfOrNull { config ->
            config.objList("streams").firstOrNull { it.str("id") == streamId }
        }

    /** Cascade: stream overrides -> config defaults -> built-ins (§6.5). */
    fun effectiveSettings(streamId: String, instant: Long): JsonObject {
        val config = configAt(instant)
        val stream = streamConfig(streamId, instant)
        val merged = DEFAULTS.toMutableMap()
        config?.optObj("defaults")?.let { merged.putAll(it) }
        stream?.optObj("overrides")?.let { merged.putAll(it) }
        return JsonObject(merged)
    }

    /** First-run bootstrap: an empty config so the app is operable. */
    fun ensureConfig(timezone: String) {
        if (db.latestConfig() != null) return
        val nowIso = fmtUtc(clock.now())
        applyConfig(
            buildJsonObject {
                put("version", 1)
                put("base_version", 0)
                put("written_by", deviceId)
                put("written_at", nowIso)
                put("effective_from", nowIso)
                put("timezone", timezone)
                put("defaults", JsonObject(DEFAULTS))
                put("streams", kotlinx.serialization.json.JsonArray(emptyList()))
            }
        )
    }

    /** Begin using a config version: cache it, log, re-materialize. */
    fun applyConfig(doc: JsonObject) {
        db.upsertConfig(doc)
        appendEvent(
            buildJsonObject {
                put("ev", "config_applied")
                put("t", fmtUtc(clock.now()))
                put("dev", deviceId)
                put("config_v", doc.int("version"))
            }
        )
        materialize()
    }

    /**
     * Create version latest+1 locally (§8.2); uploaded at next sync.
     * Returns validation error codes (empty on success).
     */
    fun stageNewConfig(
        streams: List<JsonObject>,
        defaults: JsonObject,
        timezone: String,
        effectiveFrom: String,
    ): List<String> {
        val base = db.latestConfig()
        val nowIso = fmtUtc(clock.now())
        val doc = buildJsonObject {
            put("version", (base?.int("version") ?: 0) + 1)
            put("base_version", base?.int("version") ?: 0)
            put("written_by", deviceId)
            put("written_at", nowIso)
            put("effective_from", effectiveFrom)
            put("timezone", timezone)
            put("defaults", defaults)
            put("streams", kotlinx.serialization.json.JsonArray(streams))
        }
        val errors = validateConfig(doc, db.allSurveys().keys.toList(), nowIso)
        if (errors.isNotEmpty()) return errors
        applyConfig(doc)
        return emptyList()
    }

    // -- events / fold ----------------------------------------------------

    fun appendEvent(ev: JsonObject) {
        db.appendOwnEvent(ev)
        ev.optStr("sample")?.let { refold(it) }
    }

    fun refold(sampleId: String): JsonObject {
        val events = db.eventsForSample(sampleId)
        val scheduled = parseUtc(sampleId.substringAfter("|"))
        val expiry = effectiveSettings(sampleId.substringBefore("|"), scheduled).int("expiry_minutes")
        val (row, _) = foldSample(events, expiry)
        if (row.str("status") != "scheduled") db.upsertSample(row)
        return row
    }

    private fun sampleBase(sampleId: String, now: Long): Map<String, JsonElement> = mapOf(
        "t" to JsonPrimitive(fmtUtc(now)),
        "dev" to JsonPrimitive(deviceId),
        "sample" to JsonPrimitive(sampleId),
        "stream" to JsonPrimitive(sampleId.substringBefore("|")),
    )

    private fun event(ev: String, base: Map<String, JsonElement>, extra: Map<String, JsonElement> = emptyMap()) =
        JsonObject(mapOf("ev" to JsonPrimitive(ev)) + extra + base)

    // -- quiet mode -------------------------------------------------------

    fun quietState(): JsonObject {
        val raw = db.kvGet("state", "state")
        return if (raw != null) parseJson(raw) else JsonObject(mapOf("quiet_until" to JsonNull))
    }

    fun quietActive(now: Long): Boolean {
        val until = quietState().optStr("quiet_until") ?: return false
        if (until == "indefinite") return true
        return now < parseUtc(until)
    }

    /** quietUntil: ISO instant, "indefinite", or null (off). */
    fun setQuiet(quietUntil: String?) {
        val nowIso = fmtUtc(clock.now())
        val state = buildJsonObject {
            put("quiet_until", quietUntil?.let { JsonPrimitive(it) } ?: JsonNull)
            put("set_by", deviceId)
            put("set_at", nowIso)
        }
        db.kvSet("state", "state", Json.encodeToString(JsonElement.serializer(), state))
        db.kvSet("state", "dirty", "1") // push state.json at next sync
        appendEvent(
            buildJsonObject {
                put("ev", "quiet_changed")
                put("t", nowIso)
                put("dev", deviceId)
                put("quiet_until", quietUntil?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        )
    }

    // -- materialization (§6.4) ------------------------------------------

    private fun streamIds(): List<String> {
        val ids = mutableListOf<String>()
        for (doc in db.configHistory()) {
            for (s in doc.objList("streams")) {
                if (s.str("id") !in ids) ids.add(s.str("id"))
            }
        }
        return ids
    }

    private fun timezoneAt(instant: Long): ZoneId {
        val config = configAt(instant)
        return if (config != null) ZoneId.of(config.str("timezone")) else ZoneOffset.UTC
    }

    private fun localDays(start: Long, end: Long): List<LocalDate> {
        val zone = timezoneAt(end)
        val first = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochSecond(end).atZone(zone).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var day = first
        while (!day.isAfter(last)) {
            days.add(day)
            day = day.plusDays(1)
        }
        return days
    }

    /** Resolved candidates with scheduled_utc in [start, end). */
    private fun resolvedWindow(start: Long, end: Long): List<JsonObject> {
        val history = db.configHistory()
        if (history.isEmpty()) return emptyList()
        val out = mutableListOf<JsonObject>()
        for (day in localDays(start, end)) {
            for (streamId in streamIds()) {
                for (r in resolveDay(history, streamId, day)) {
                    if (r.scheduledUtc in start until end) {
                        val iso = fmtUtc(r.scheduledUtc)
                        out.add(
                            buildJsonObject {
                                put("sample", "$streamId|$iso")
                                put("stream", streamId)
                                put("scheduled_utc", iso)
                                put("config_v", r.configV)
                                put("suppressed_reason", r.suppressedReason?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("idx", r.index)
                            }
                        )
                    }
                }
            }
        }
        return out.sortedBy { it.str("scheduled_utc") }
    }

    /** Rebuild the 48 h schedule horizon from the config history. */
    fun materialize() {
        val now = clock.now()
        val rows = resolvedWindow(now, now + HORIZON_S).map { r ->
            val types = db.eventsForSample(r.str("sample")).map { it.third.str("ev") }.toSet()
            val done = types.intersect(
                setOf("fired", "answered", "skipped", "expired", "retracted", "suppressed")
            ).isNotEmpty()
            JsonObject(r + mapOf("state" to JsonPrimitive(if (done) "done" else "planned")))
        }
        db.replaceSchedule(rows)
        val config = configAt(now)
        if (config != null) {
            val localDay = Instant.ofEpochSecond(now)
                .atZone(ZoneId.of(config.str("timezone"))).toLocalDate()
            db.kvSet("sync_meta", "materialized_day", localDay.toString())
        }
        db.kvSet("sync_meta", "materialized_until", fmtUtc(now + HORIZON_S))
    }

    // -- backfill (§6.4) --------------------------------------------------

    /** App-start sequence: materialize, then backfill the gap. */
    fun start() {
        val now = clock.now()
        if (db.kvGet("sync_meta", "last_materialized_at") == null) {
            // First run: no past to account for.
            db.kvSet("sync_meta", "last_materialized_at", fmtUtc(now))
        }
        materialize()
        backfillNow()
        db.kvSet("sync_meta", "last_tick", now.toString())
    }

    /**
     * Classify [last_materialized_at, now) per §6.4; append the results.
     *
     * The watermark advances only to `now - max expiry`: samples whose active
     * window is still open are left to the live scheduler (they can still
     * fire late) and revisited by the next backfill run.
     */
    fun backfillNow(): List<JsonObject> {
        val now = clock.now()
        val wmIso = db.kvGet("sync_meta", "last_materialized_at")
        val wm = if (wmIso != null) parseUtc(wmIso) else now
        if (wm >= now) return emptyList()
        val resolved = resolvedWindow(wm, now).map {
            BackfillSample(
                it.str("sample"), it.str("stream"), it.str("scheduled_utc"),
                it.optStr("suppressed_reason"),
            )
        }
        val config = configAt(now)
        val expiries = streamIds().associateWith {
            effectiveSettings(it, now).int("expiry_minutes")
        }
        val emitted = coreBackfill(
            resolvedSamples = resolved,
            knownEvents = db.allSampleEvents(),
            quietHistory = db.eventsOfType("quiet_changed").map {
                QuietChange(it.str("t"), it.optStr("quiet_until"))
            },
            now = now,
            deviceId = deviceId,
            configV = config?.int("version") ?: 0,
            expiryMinutesFor = expiries,
        )
        for (ev in emitted) appendEvent(ev)
        val lookbackS = (expiries.values.maxOrNull() ?: 60) * 60L
        db.kvSet("sync_meta", "last_materialized_at", fmtUtc(maxOf(wm, now - lookbackS)))
        return emitted
    }

    // -- tick loop --------------------------------------------------------

    /** Fire due pings, re-fire snoozes, expire; returns next wake epoch. */
    fun tick(): Long? {
        val now = clock.now()
        val lastTick = db.kvGet("sync_meta", "last_tick")
        if (lastTick != null && now - lastTick.toLong() > CLOCK_JUMP_S) {
            backfillNow() // slept through the gap
        }
        if (dayRolledOrHorizonNear(now)) materialize()

        for (due in db.dueSchedule(fmtUtc(now))) handleDue(due, now)
        refireSnoozes(now)
        expirePending(now)

        db.kvSet("sync_meta", "last_tick", now.toString())
        return nextWake(now)
    }

    private fun dayRolledOrHorizonNear(now: Long): Boolean {
        val until = db.kvGet("sync_meta", "materialized_until")
        if (until == null || parseUtc(until) - now < HORIZON_S / 2) return true
        val config = configAt(now) ?: return false
        val localDay = Instant.ofEpochSecond(now)
            .atZone(ZoneId.of(config.str("timezone"))).toLocalDate().toString()
        return db.kvGet("sync_meta", "materialized_day") != localDay
    }

    private fun handleDue(due: Db.ScheduleRow, now: Long) {
        val sampleId = due.sample
        val types = db.eventsForSample(sampleId).map { it.third.str("ev") }.toSet()
        val base = sampleBase(sampleId, now)
        val terminal = types.intersect(setOf("answered", "skipped", "expired", "retracted", "suppressed"))
        val scheduled = parseUtc(due.scheduledUtc)
        val expiryS = effectiveSettings(due.stream, scheduled).int("expiry_minutes") * 60L

        if (terminal.isNotEmpty() || "fired" in types) {
            // already handled here or elsewhere
        } else if (due.suppressedReason != null) {
            appendEvent(event("suppressed", base, mapOf("reason" to JsonPrimitive(due.suppressedReason))))
        } else if (quietActive(now)) {
            appendEvent(event("suppressed", base, mapOf("reason" to JsonPrimitive("quiet_mode"))))
        } else if (scheduled + expiryS <= now) {
            // The whole active window passed while nothing was running and no
            // backfill covered it yet; classify rather than fire stale.
            if ("unobserved" !in types) {
                appendEvent(event("unobserved", base, mapOf("config_v" to JsonPrimitive(due.configV))))
            }
        } else {
            appendEvent(
                event(
                    "fired", base,
                    mapOf(
                        "config_v" to JsonPrimitive(due.configV),
                        "scheduled" to JsonPrimitive(due.scheduledUtc),
                        "test" to JsonPrimitive(false),
                    ),
                )
            )
            notifySample(sampleId, scheduled, snoozedN = 0)
        }
        db.markSchedule(sampleId, "done")
    }

    private fun notifySample(sampleId: String, scheduled: Long, snoozedN: Int) {
        val streamId = sampleId.substringBefore("|")
        val name = streamConfig(streamId, scheduled)?.str("name") ?: streamId
        val local = Instant.ofEpochSecond(scheduled).atZone(timezoneAt(scheduled))
        val when_ = "%02d:%02d".format(local.hour, local.minute)
        val title = if (snoozedN == 0) name else "$name (snoozed x$snoozedN)"
        notifier.notify(sampleId, title, "Ping at $when_ - answer now")
    }

    private fun refireSnoozes(now: Long) {
        for (row in db.sampleRows(statuses = listOf("pending"))) {
            val events = db.eventsForSample(row.str("sample")).map { it.third }
            val snoozes = events.filter { it.str("ev") == "snoozed" }
            if (snoozes.isEmpty()) continue
            val latest = snoozes.maxBy { it.str("until") }
            val until = parseUtc(latest.str("until"))
            if (until > now) continue
            val refired = events.any {
                it.str("ev") == "fired" && it.str("dev") == deviceId && parseUtc(it.str("t")) >= until
            }
            if (refired) continue
            val scheduled = parseUtc(row.str("scheduled_utc"))
            val expiryS = effectiveSettings(row.str("stream"), scheduled).int("expiry_minutes") * 60L
            if (scheduled + expiryS <= now) continue // expiry scan will close it out
            val base = sampleBase(row.str("sample"), now)
            appendEvent(
                event(
                    "fired", base,
                    mapOf(
                        "config_v" to JsonPrimitive(configAt(scheduled)!!.int("version")),
                        "scheduled" to row.getValue("scheduled_utc"),
                        "test" to JsonPrimitive(false),
                    ),
                )
            )
            notifySample(row.str("sample"), scheduled, snoozedN = snoozes.size)
        }
    }

    private fun expirePending(now: Long) {
        for (row in db.sampleRows(statuses = listOf("pending"))) {
            val scheduled = parseUtc(row.str("scheduled_utc"))
            val expiryS = effectiveSettings(row.str("stream"), scheduled).int("expiry_minutes") * 60L
            if (scheduled + expiryS > now) continue
            val base = sampleBase(row.str("sample"), now)
            val config = configAt(scheduled)
            appendEvent(
                event("expired", base, mapOf("config_v" to JsonPrimitive(config?.int("version") ?: 0)))
            )
            notifier.cancel(row.str("sample"))
        }
    }

    fun nextWake(now: Long): Long? {
        val candidates = mutableListOf<Long>()
        db.nextPlanned(fmtUtc(now))?.let { candidates.add(parseUtc(it)) }
        for (row in db.sampleRows(statuses = listOf("pending"))) {
            val scheduled = parseUtc(row.str("scheduled_utc"))
            val expiryS = effectiveSettings(row.str("stream"), scheduled).int("expiry_minutes") * 60L
            if (scheduled + expiryS > now) candidates.add(scheduled + expiryS)
            for ((_, _, ev) in db.eventsForSample(row.str("sample"))) {
                if (ev.str("ev") == "snoozed" && parseUtc(ev.str("until")) > now) {
                    candidates.add(parseUtc(ev.str("until")))
                }
            }
        }
        db.kvGet("sync_meta", "materialized_until")?.let {
            candidates.add(parseUtc(it) - HORIZON_S / 2)
        }
        return candidates.minOrNull()
    }

    // -- user actions (§6.5, §10.3) --------------------------------------

    fun answer(
        sampleId: String,
        answers: JsonObject,
        partial: Boolean = false,
        supersedes: String? = null,
        loc: JsonObject? = null,
    ): JsonObject {
        val now = clock.now()
        val streamId = sampleId.substringBefore("|")
        val scheduled = parseUtc(sampleId.substringAfter("|"))
        val stream = streamConfig(streamId, scheduled) ?: streamConfig(streamId, now)
            ?: throw IllegalArgumentException("unknown stream $streamId")
        val surveyRef = stream.obj("survey")
        appendEvent(
            event(
                "answered", sampleBase(sampleId, now),
                mapOf(
                    "survey" to buildJsonObject {
                        put("id", surveyRef.str("id"))
                        put("version", surveyRef.int("version"))
                    },
                    "answers" to answers,
                    "loc" to (loc ?: JsonNull),
                    "supersedes" to (supersedes?.let { JsonPrimitive(it) } ?: JsonNull),
                    "partial" to JsonPrimitive(partial),
                ),
            )
        )
        bumpVocab(surveyRef, answers, fmtUtc(now))
        notifier.cancel(sampleId)
        db.markSchedule(sampleId, "done")
        return refold(sampleId)
    }

    private fun bumpVocab(surveyRef: JsonObject, answers: JsonObject, nowIso: String) {
        val survey = db.survey(surveyRef.str("id"), surveyRef.int("version")) ?: return
        for (field in survey.objList("fields")) {
            if (field.str("type") != "tags") continue
            val tags = answers.optStringList(field.str("id"))
            if (!tags.isNullOrEmpty()) {
                val vocab = field.optStr("vocab") ?: "${survey.str("id")}.${field.str("id")}"
                db.bumpTags(vocab, tags, nowIso)
            }
        }
    }

    fun skip(sampleId: String) {
        val now = clock.now()
        appendEvent(event("skipped", sampleBase(sampleId, now)))
        notifier.cancel(sampleId)
        db.markSchedule(sampleId, "done")
    }

    /** Snooze; returns null on success or a refusal reason (§6.5). */
    fun snooze(sampleId: String): String? {
        val now = clock.now()
        val streamId = sampleId.substringBefore("|")
        val scheduled = parseUtc(sampleId.substringAfter("|"))
        val settings = effectiveSettings(streamId, scheduled)
        val events = db.eventsForSample(sampleId).map { it.third }
        val n = events.count { it.str("ev") == "snoozed" } + 1
        if (n > settings.int("max_snoozes")) return "max_snoozes"
        val until = now + settings.int("snooze_minutes") * 60L
        if (until >= scheduled + settings.int("expiry_minutes") * 60L) return "near_expiry"
        appendEvent(
            event(
                "snoozed", sampleBase(sampleId, now),
                mapOf("n" to JsonPrimitive(n), "until" to JsonPrimitive(fmtUtc(until))),
            )
        )
        notifier.cancel(sampleId)
        return null
    }

    fun retract(sampleId: String, note: String? = null) {
        val now = clock.now()
        val extra = if (note != null) mapOf("note" to JsonPrimitive(note) as JsonElement) else emptyMap()
        appendEvent(event("retracted", sampleBase(sampleId, now), extra))
    }

    /** Real sample at the current second with test=true (§10.2). */
    fun fireTestPing(streamId: String): String {
        val now = clock.now()
        val config = configAt(now)
        val sampleId = "$streamId|${fmtUtc(now)}"
        appendEvent(
            event(
                "fired", sampleBase(sampleId, now),
                mapOf(
                    "config_v" to JsonPrimitive(config?.int("version") ?: 0),
                    "scheduled" to JsonPrimitive(fmtUtc(now)),
                    "test" to JsonPrimitive(true),
                ),
            )
        )
        notifySample(sampleId, now, snoozedN = 0)
        return sampleId
    }

    // -- UI queries -------------------------------------------------------

    /** 0-based index in the local day's resolved list (§7), null for ad-hoc
     * samples (test pings). */
    fun sampleIndex(sampleId: String): Int? {
        val streamId = sampleId.substringBefore("|")
        val scheduled = parseUtc(sampleId.substringAfter("|"))
        val history = db.configHistory()
        if (history.isEmpty()) return null
        for (day in localDays(scheduled, scheduled)) {
            for (r in resolveDay(history, streamId, day)) {
                if (r.scheduledUtc == scheduled) return r.index
            }
        }
        return null
    }

    fun isFullSurvey(sampleId: String): Boolean {
        val streamId = sampleId.substringBefore("|")
        val scheduled = parseUtc(sampleId.substringAfter("|"))
        val stream = streamConfig(streamId, scheduled)
        val n = stream?.let { if ("full_survey_every_n" in it) it.int("full_survey_every_n") else 1 } ?: 1
        val index = sampleIndex(sampleId) ?: return true
        return isFull(index, n)
    }

    /** Pending, fired here or anywhere, still inside the active window. */
    fun activeSamples(now: Long = clock.now()): List<JsonObject> =
        db.sampleRows(statuses = listOf("pending")).filter { row ->
            val scheduled = parseUtc(row.str("scheduled_utc"))
            val expiryS = effectiveSettings(row.str("stream"), scheduled).int("expiry_minutes") * 60L
            row.bool("observed", false) && scheduled + expiryS > now
        }

    /** Expired/unobserved/stale-pending within the backlog window (§6.5). */
    fun backlog(now: Long = clock.now()): List<JsonObject> {
        val active = activeSamples(now).map { it.str("sample") }.toSet()
        return db.sampleRows(statuses = listOf("expired", "unobserved", "pending")).filter { row ->
            if (row.str("sample") in active) return@filter false
            val scheduled = parseUtc(row.str("scheduled_utc"))
            val backlogS = effectiveSettings(row.str("stream"), scheduled).int("backlog_hours") * 3600L
            scheduled > now - backlogS && scheduled <= now
        }
    }
}

private fun JsonObject.optStringList(key: String): List<String>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content }
