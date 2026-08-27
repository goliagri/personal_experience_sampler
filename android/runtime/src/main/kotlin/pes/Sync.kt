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

import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

class SyncResult {
    val conflicts = mutableListOf<String>()
    val imported = mutableListOf<String>()
    val exported = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    var changedStreams: List<String> = emptyList()
    var appliedConfig: Int? = null
    var backfilled = 0
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
        updateDeviceDoc()
        result.backfilled = engine.backfillNow().size
        db.kvSet("sync_meta", "last_sync", fmtUtc(engine.clock.now()))
        return result
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

    private fun updateDeviceDoc() {
        val dev = engine.deviceId
        val now = engine.clock.now()
        var role = db.kvGet("device", "role")
        if (role == null) {
            role = if (noOtherPrimary(dev)) "primary" else ""
            db.kvSet("device", "role", role)
        }
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
    }

    private fun noOtherPrimary(ownDev: String): Boolean {
        for (path in store.list("devices")) {
            val raw = store.get(path) ?: continue
            val doc = parseJson(raw.decodeToString())
            if (doc.optStr("device_id") != ownDev && doc.optStr("role") == "primary") return false
        }
        return true
    }
}
