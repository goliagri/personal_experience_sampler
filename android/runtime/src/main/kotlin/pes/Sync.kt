/**
 * Sync procedure (spec §8.4) over a `CloudStore`. Mirrors `pes/sync.py`.
 *
 * Every step is idempotent; a failed step is simply retried on the next
 * trigger. Sync never blocks the ping path — the engine works entirely from
 * the local database, and this class only moves bytes between it and the
 * cloud.
 *
 * Change detection uses the store's opaque `etag` (content hash for the local
 * folder backend, `modifiedTime` for Drive), remembered in `sync_meta`.
 */
package pes

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pes.core.columnsJson
import pes.core.exportCsv
import pes.core.fmtUtc
import pes.core.int
import pes.core.objList
import pes.core.optStr
import pes.core.parseUtc
import pes.core.str
import pes.store.CloudStore
import pes.store.parseJson

val TERMINAL = setOf("answered", "skipped", "expired", "retracted", "suppressed")

const val APP_VERSION = "0.3.0"
const val SNAPSHOT_KEEP = 12 // §9: 12 weekly + 12 monthly
const val PRIMARY_STALE_S = 14L * 86400 // §9: a primary silent this long can be replaced
const val FORMAT_VERSION = 1

class SyncResult {
    val conflicts = mutableListOf<String>()
    val imported = mutableListOf<String>()
    val exported = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    var changedStreams: List<String> = emptyList()
    var appliedConfig: Int? = null
    var backfilled = 0
    var role = ""
    var snapshot: String? = null
    var snapshotMonthly: String? = null
}

class RestoreResult {
    val uploaded = mutableListOf<String>()
    val restored = mutableListOf<String>()
    val docs = mutableListOf<String>()
    lateinit var sync: SyncResult
}

private fun jsonl(lines: List<String>): ByteArray =
    (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

/** Local date (YYYY-MM-DD) of the most recent Sunday 03:00 <= now. */
fun lastSunday0300(now: Long, timezone: String): String {
    val local = Instant.ofEpochSecond(now).atZone(ZoneId.of(timezone))
    val daysSinceSunday = if (local.dayOfWeek == DayOfWeek.SUNDAY) 0L else local.dayOfWeek.value.toLong()
    var sunday = local.toLocalDate().minusDays(daysSinceSunday)
    if (daysSinceSunday == 0L && local.toLocalTime() < LocalTime.of(3, 0)) sunday = sunday.minusDays(7)
    return sunday.toString()
}

/** json.dumps(doc, indent=2) + "\n", matching the Python `_dumps`. */
private val PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

fun dumpsDoc(doc: JsonObject): ByteArray =
    (PRETTY.encodeToString(JsonElement.serializer(), doc) + "\n").toByteArray(Charsets.UTF_8)

class Syncer(
    private val engine: Engine,
    private val store: CloudStore,
    private val platform: String = "android",
) {
    private val db = engine.db

    // -- helpers ----------------------------------------------------------

    private fun seenEtag(path: String): String? = db.kvGet("sync_meta", "etag:$path")

    private fun rememberEtag(path: String) {
        store.metadata(path)?.let { db.kvSet("sync_meta", "etag:$path", it.etag) }
    }

    private fun changed(path: String): Boolean {
        val meta = store.metadata(path) ?: return false
        return meta.etag != seenEtag(path)
    }

    // -- full sync --------------------------------------------------------

    fun sync(): SyncResult {
        val result = SyncResult()
        syncConfig(result)
        syncState()
        syncSurveys()
        uploadOwnMonths()
        importOtherDevices(result)
        regenerateExports(result)
        updateDeviceDoc(result)
        snapshot(result)
        result.backfilled = engine.backfillNow().size
        db.kvSet("sync_meta", "last_sync", fmtUtc(engine.clock.now()))
        return result
    }

    // -- restore (§8.6) ---------------------------------------------------

    /**
     * Rebuild a lost or damaged cloud folder from this device's cache, then
     * run a normal sync. Creates files only: another device's cloud file is
     * never overwritten — lines it lacks go to `restored/`.
     */
    fun restore(): RestoreResult {
        val result = RestoreResult()
        val dev = engine.deviceId
        val cloudFiles = store.list("events").toSet()
        for (source in db.eventFiles()) {
            if (!source.startsWith("events/")) continue // restored/ fragments are re-derived below
            val (_, fileDev, name) = source.split("/")
            val lines = db.fileLines(source)
            val data = jsonl(lines)
            if (source !in cloudFiles) {
                if (store.putIfAbsent(source, data)) result.uploaded.add(source)
                continue
            }
            val present = (store.get(source) ?: ByteArray(0)).decodeToString().split("\n").toSet()
            val extra = lines.filter { it !in present }
            if (extra.isEmpty()) continue
            if (fileDev == dev) {
                // Own log: authoritative, and the normal upload path
                // overwrites it wholesale anyway.
                store.put(source, data)
                result.uploaded.add(source)
            } else {
                val path = "restored/$dev/$fileDev/$name"
                store.put(path, jsonl(extra))
                result.restored.add(path)
            }
        }

        for (doc in db.configHistory()) {
            val path = "config/history/config_v%04d.json".format(doc.int("version"))
            if (store.putIfAbsent(path, dumpsDoc(doc))) result.docs.add(path)
        }
        db.latestConfig()?.let {
            if (store.putIfAbsent("config/current.json", dumpsDoc(it))) result.docs.add("config/current.json")
        }
        for ((key, doc) in db.allSurveys()) {
            val path = "surveys/${key.first}/v${key.second}.json"
            if (store.putIfAbsent(path, dumpsDoc(doc))) result.docs.add(path)
        }
        if (ensureManifest()) result.docs.add("manifest.json")
        result.sync = sync()
        return result
    }

    private fun ensureManifest(): Boolean {
        val doc = buildJsonObject {
            put("format_version", JsonPrimitive(FORMAT_VERSION))
            put("created_at", JsonPrimitive(fmtUtc(engine.clock.now())))
            put("install_id", JsonPrimitive(engine.deviceId))
        }
        return store.putIfAbsent("manifest.json", dumpsDoc(doc))
    }

    /** Pre-notification check (§8.4): config + state metadata only. */
    fun lightweightCheck() {
        try {
            syncConfig(SyncResult())
            syncState()
        } catch (_: IOException) {
            // offline; skip per spec
        }
    }

    // -- step 1: config / state / surveys --------------------------------

    private fun syncConfig(result: SyncResult) {
        val path = "config/current.json"
        var local = db.latestConfig()
        val cloudRaw = if (changed(path) || local == null) store.get(path) else null
        val cloud = cloudRaw?.let { parseJson(it.decodeToString()) }

        if (cloud != null && local != null) {
            if (cloud.int("version") == local.int("version") && cloud != local) {
                resolveConflict(local, cloud, result)
                return
            }
            if (cloud.int("version") > local.int("version")) {
                reconcileHistory(cloud.int("version"), result)
                engine.applyConfig(cloud)
                result.appliedConfig = cloud.int("version")
            }
        } else if (cloud != null) {
            reconcileHistory(cloud.int("version"), result)
            engine.applyConfig(cloud)
            result.appliedConfig = cloud.int("version")
        }

        // Upload anything the cloud is missing (new local versions, history).
        local = db.latestConfig() ?: return
        for (doc in db.configHistory()) {
            val histPath = "config/history/config_v%04d.json".format(doc.int("version"))
            if (store.metadata(histPath) == null) store.put(histPath, dumpsDoc(doc))
        }
        val cloudNow = if (store.metadata(path) != null) {
            store.get(path)?.let { parseJson(it.decodeToString()) }
        } else {
            null
        }
        if (cloudNow == null || cloudNow.int("version") < local.int("version")) {
            store.put(path, dumpsDoc(local))
        }
        rememberEtag(path)
    }

    /**
     * Cache the cloud's config versions below `before` for piecewise
     * scheduling. A local doc that disagrees with the cloud's same-version
     * doc lost a race it never saw (another device's version chain moved
     * past it, §8.2): archive it as a conflict and adopt the cloud lineage —
     * silently keeping it would schedule from a history no other device has.
     */
    private fun reconcileHistory(before: Int, result: SyncResult) {
        val localByV = db.configHistory().associateBy { it.int("version") }
        for (v in 1 until before) {
            val raw = store.get("config/history/config_v%04d.json".format(v)) ?: continue
            val cloudDoc = parseJson(raw.decodeToString())
            val localDoc = localByV[v]
            if (localDoc == cloudDoc) continue
            if (localDoc != null) {
                archiveRejected(localDoc, result)
                result.warnings.add(
                    "config v$v from ${localDoc.str("written_by")} rejected" +
                        " (cloud lineage moved past it); kept ${cloudDoc.str("written_by")}'s"
                )
            }
            db.upsertConfig(cloudDoc)
        }
    }

    private fun archiveRejected(doc: JsonObject, result: SyncResult) {
        val stamp = doc.str("written_at").replace(":", "").replace("-", "")
        val conflictPath = "config/conflicts/config_v%04d_rejected_%s_%s.json"
            .format(doc.int("version"), doc.str("written_by"), stamp)
        store.putIfAbsent(conflictPath, dumpsDoc(doc))
        result.conflicts.add(conflictPath)
    }

    /** Two writers branched the same base (§8.2): later written_at wins. */
    private fun resolveConflict(local: JsonObject, cloud: JsonObject, result: SyncResult) {
        val (loser, winner) = if (local.str("written_at") <= cloud.str("written_at")) {
            Pair(local, cloud)
        } else {
            Pair(cloud, local)
        }
        archiveRejected(loser, result)
        result.warnings.add(
            "config v${loser.int("version")} from ${loser.str("written_by")} rejected" +
                " (concurrent edit); kept ${winner.str("written_by")}'s"
        )
        if (winner === cloud) {
            engine.applyConfig(cloud)
        } else {
            store.put("config/current.json", dumpsDoc(local))
        }
        // The loser may have uploaded its doc as this version's history file
        // already; the lineage must record the winner.
        store.put("config/history/config_v%04d.json".format(winner.int("version")), dumpsDoc(winner))
        rememberEtag("config/current.json")
    }

    private fun syncState() {
        val path = "state.json"
        val local = engine.quietState()
        val cloud = store.get(path)?.let { parseJson(it.decodeToString()) }
        var dirty = db.kvGet("state", "dirty") == "1"
        // Last-writer-wins by set_at (§8.3).
        val localSetAt = local.optStr("set_at")
        if (cloud != null && (localSetAt == null || (cloud.optStr("set_at") ?: "") > localSetAt)) {
            db.kvSet("state", "state", Json.encodeToString(JsonElement.serializer(), cloud))
            dirty = false
            db.kvSet("state", "dirty", "0")
        } else if (dirty && localSetAt != null) {
            store.put(path, dumpsDoc(local))
            db.kvSet("state", "dirty", "0")
        }
        rememberEtag(path)
    }

    private fun syncSurveys() {
        val cached = db.allSurveys()
        for (path in store.list("surveys")) {
            if (!path.endsWith(".json")) continue
            val surveyId = path.split("/")[1]
            val version = path.substringAfterLast("/").removePrefix("v").removeSuffix(".json").toInt()
            if (Pair(surveyId, version) !in cached) {
                store.get(path)?.let { db.upsertSurvey(parseJson(it.decodeToString())) }
            }
        }
        // Surveys are immutable per version: upload local ones the cloud lacks.
        for ((key, doc) in db.allSurveys()) {
            store.putIfAbsent("surveys/${key.first}/v${key.second}.json", dumpsDoc(doc))
        }
    }

    // -- step 2: upload own event months ---------------------------------

    private fun uploadOwnMonths() {
        val dev = engine.deviceId
        for (month in db.unsyncedMonths(dev)) {
            val path = "events/$dev/$month.jsonl"
            val lines = db.monthLines(dev, month)
            store.put(path, (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            // Mark only the snapshot uploaded: the engine thread may append
            // to this month while the put is in flight, and those events
            // must stay unsynced for the next trigger.
            db.markMonthSynced(dev, month, uptoLine = lines.size)
            rememberEtag(path)
        }
    }

    // -- step 3: import other devices' events ----------------------------

    private fun importOtherDevices(result: SyncResult) {
        val ownPrefix = "events/${engine.deviceId}/"
        val now = engine.clock.now()
        val paths = (store.list("events") + store.list("restored"))
            .filter { it.endsWith(".jsonl") && !it.startsWith(ownPrefix) }
        val changedStreams = sortedSetOf<String>()
        for (path in paths) {
            if (!changed(path)) continue
            val raw = store.get(path) ?: continue
            // A malformed file is skipped with a warning — its etag stays
            // unremembered so it is retried, and it cannot block the
            // remaining files.
            val affected = try {
                db.importFile(path, raw.decodeToString().split("\n").filter { it.isNotEmpty() })
            } catch (e: Exception) {
                if (e is java.io.IOException) throw e
                result.warnings.add("skipped $path: malformed line ($e)")
                continue
            }
            result.imported.add(path)
            rememberEtag(path)
            for (sampleId in affected) {
                engine.refold(sampleId)
                changedStreams.add(sampleId.substringBefore("|"))
                val types = db.eventsForSample(sampleId).map { it.third.str("ev") }.toSet()
                if (types.intersect(TERMINAL).isNotEmpty()) {
                    engine.notifier.cancel(sampleId)
                    continue
                }
                // Retroactive expiry (§8.4 step 3), independent of the
                // backfill watermark — decided on event types, not the
                // folded status: fired + unobserved folds to unobserved
                // (precedence), yet an observed sample whose window has
                // passed must still be closed out as expired.
                if ("fired" !in types) continue
                val streamId = sampleId.substringBefore("|")
                val scheduled = parseUtc(sampleId.substringAfter("|"))
                val expiryS = engine.effectiveSettings(streamId, scheduled)
                    .int("expiry_minutes") * 60L
                if (scheduled + expiryS < now) {
                    engine.appendEvent(
                        kotlinx.serialization.json.buildJsonObject {
                            put("ev", kotlinx.serialization.json.JsonPrimitive("expired"))
                            put(
                                "config_v",
                                kotlinx.serialization.json.JsonPrimitive(
                                    engine.configAt(scheduled)?.int("version") ?: 0
                                ),
                            )
                            put("t", kotlinx.serialization.json.JsonPrimitive(fmtUtc(now)))
                            put("dev", kotlinx.serialization.json.JsonPrimitive(engine.deviceId))
                            put("sample", kotlinx.serialization.json.JsonPrimitive(sampleId))
                            put("stream", kotlinx.serialization.json.JsonPrimitive(streamId))
                        }
                    )
                    engine.notifier.cancel(sampleId)
                }
            }
        }
        result.changedStreams = changedStreams.toList()
    }

    // -- step 4: exports --------------------------------------------------

    private fun regenerateExports(result: SyncResult) {
        val config = db.latestConfig() ?: return
        val surveys = db.allSurveys()
        val streamIds = (
            config.objList("streams").map { it.str("id") } +
                db.sampleRows().map { it.str("stream") }
            ).toSortedSet()
        for (streamId in streamIds) {
            val rows = db.sampleRows(stream = streamId).filter { it.str("status") != "scheduled" }
            if (rows.isEmpty()) continue
            val (csvBytes, columns) = exportCsv(rows, surveys, config.str("timezone"))
            val digest = MessageDigest.getInstance("SHA-256").digest(csvBytes)
                .joinToString("") { "%02x".format(it) }
            if (digest == db.kvGet("sync_meta", "export:$streamId")) continue
            store.put("exports/$streamId.csv", csvBytes)
            store.put("exports/$streamId.columns.json", columnsJson(columns))
            db.kvSet("sync_meta", "export:$streamId", digest)
            result.exported.add(streamId)
        }
    }

    // -- step 5: device document ------------------------------------------

    private fun updateDeviceDoc(result: SyncResult) {
        val dev = engine.deviceId
        val now = engine.clock.now()
        val others = otherDevices(dev)
        var role = db.kvGet("device", "role") ?: ""
        // §9: claim primary when no live primary exists (first run, or the
        // previous primary has been silent for 14 days). Two simultaneous
        // claimants are resolved by lexicographic device_id at the next
        // sync that sees both.
        if (role != "primary" && others.none { isLivePrimary(it, now) }) role = "primary"
        if (role == "primary" && others.any { isLivePrimary(it, now) && it.str("device_id") < dev }) role = ""
        db.kvSet("device", "role", role)
        result.role = role
        val doc = kotlinx.serialization.json.buildJsonObject {
            put("device_id", kotlinx.serialization.json.JsonPrimitive(dev))
            put("name", kotlinx.serialization.json.JsonPrimitive(db.kvGet("device", "name") ?: dev))
            put("platform", kotlinx.serialization.json.JsonPrimitive(platform))
            put("app_version", kotlinx.serialization.json.JsonPrimitive(APP_VERSION))
            put("last_sync", kotlinx.serialization.json.JsonPrimitive(fmtUtc(now)))
            put("role", kotlinx.serialization.json.JsonPrimitive(role))
        }
        // Idempotence: rewrite only when something besides last_sync changed
        // or the recorded last_sync is over an hour stale.
        val path = "devices/$dev.json"
        val prevRaw = store.get(path)
        if (prevRaw != null) {
            val prev = parseJson(prevRaw.decodeToString())
            val unchanged = prev.filterKeys { it != "last_sync" } == doc.filterKeys { it != "last_sync" }
            if (unchanged && now - parseUtc(prev.str("last_sync")) < 3600) return
        }
        store.put(path, dumpsDoc(doc))
        ensureManifest()
    }

    private fun otherDevices(ownDev: String): List<JsonObject> =
        store.list("devices").mapNotNull { path ->
            store.get(path)?.let { parseJson(it.decodeToString()) }
        }.filter { doc -> doc.optStr("device_id").let { it != null && it != ownDev } }

    private fun isLivePrimary(doc: JsonObject, now: Long): Boolean =
        doc.optStr("role") == "primary" &&
            now - parseUtc(doc.optStr("last_sync") ?: "1970-01-01T00:00:00Z") < PRIMARY_STALE_S

    // -- step 5b: snapshots (§9) -----------------------------------------

    /**
     * Primary only: zip the folder on the first sync after Sunday 03:00
     * local, promote the month's first weekly zip to monthly, prune to
     * 12 + 12. Keyed by the Sunday's date, so retries are idempotent.
     */
    private fun snapshot(result: SyncResult) {
        if (db.kvGet("device", "role") != "primary") return
        val config = db.latestConfig() ?: return
        val sunday = lastSunday0300(engine.clock.now(), config.str("timezone"))
        val weekly = "snapshots/weekly/$sunday.zip"
        if (store.metadata(weekly) == null) {
            val data = zipFolder()
            if (store.putIfAbsent(weekly, data)) result.snapshot = weekly
            val monthly = "snapshots/monthly/${sunday.take(7)}.zip"
            if (store.putIfAbsent(monthly, data)) result.snapshotMonthly = monthly
        }
        for (prefix in listOf("snapshots/weekly", "snapshots/monthly")) {
            val zips = store.list(prefix).filter { it.endsWith(".zip") }.sorted()
            for (path in zips.dropLast(SNAPSHOT_KEEP)) store.delete(path)
        }
    }

    private fun zipFolder(): ByteArray {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            for (path in store.list("")) {
                if (path.startsWith("snapshots/")) continue
                val data = store.get(path) ?: continue
                zip.putNextEntry(ZipEntry(path).apply { time = 315532800000L }) // 1980-01-01, deterministic
                zip.write(data)
                zip.closeEntry()
            }
        }
        return buf.toByteArray()
    }
}
