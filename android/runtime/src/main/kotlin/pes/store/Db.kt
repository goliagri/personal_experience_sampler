/**
 * Local SQLite database (spec §5.3). Mirrors `pes/store/db.py`: same schema,
 * same semantics, written against androidx.sqlite's driver API so the exact
 * same class runs in JVM scenario tests (BundledSQLiteDriver) and in the
 * Android app.
 *
 * Events are stored with their exact JSONL line text (`payload_json`) plus a
 * `(source_file, line)` position so the fold's deduplication order matches the
 * cloud files byte-for-byte. Own events are appended with the next line number
 * of their month file; imported files are replaced wholesale.
 *
 * Like the Python Db (one sqlite3 connection), an instance is single-threaded:
 * open one Db per thread.
 */
package pes.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import pes.core.EventTriple
import pes.core.int
import pes.core.optStr
import pes.core.str

private val SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY,
    dev TEXT NOT NULL,
    ev TEXT NOT NULL,
    sample TEXT,
    stream TEXT,
    t TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,
    source_file TEXT NOT NULL,
    line INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_sample ON events(sample);
CREATE INDEX IF NOT EXISTS idx_events_source ON events(source_file, line);
CREATE INDEX IF NOT EXISTS idx_events_ev ON events(ev);

CREATE TABLE IF NOT EXISTS samples (
    sample TEXT PRIMARY KEY,
    stream TEXT NOT NULL,
    scheduled_utc TEXT NOT NULL,
    status TEXT NOT NULL,
    row_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_samples_stream ON samples(stream, scheduled_utc);

CREATE TABLE IF NOT EXISTS schedule (
    sample TEXT PRIMARY KEY,
    stream TEXT NOT NULL,
    scheduled_utc TEXT NOT NULL,
    config_v INTEGER NOT NULL,
    suppressed_reason TEXT,
    idx INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'planned'
);
CREATE INDEX IF NOT EXISTS idx_schedule_time ON schedule(scheduled_utc);

CREATE TABLE IF NOT EXISTS config_cache (
    version INTEGER PRIMARY KEY,
    doc_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS survey_cache (
    survey_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    doc_json TEXT NOT NULL,
    PRIMARY KEY (survey_id, version)
);
CREATE TABLE IF NOT EXISTS kv (
    ns TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (ns, key)
);
CREATE TABLE IF NOT EXISTS tag_vocab (
    vocab TEXT NOT NULL,
    tag TEXT NOT NULL,
    last_used TEXT NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (vocab, tag)
);
"""

// kv namespaces: "device" (device_id, name, role), "sync_meta" (etags,
// last_materialized_at, export hashes), "state" (state.json document).

/** Serialize an event exactly like Python's
 * `json.dumps(ev, separators=(",", ":"), sort_keys=True)` — including
 * ensure_ascii: every non-ASCII character is \\uXXXX-escaped, so a JSONL
 * line can never contain characters like U+2028 that some line-splitters
 * treat as newlines. */
fun dumpsLine(ev: JsonObject): String =
    escapeNonAscii(Json.encodeToString(JsonElement.serializer(), sortKeys(ev)))

private fun escapeNonAscii(s: String): String {
    if (s.all { it.code < 0x80 }) return s
    val out = StringBuilder(s.length + 16)
    for (ch in s) {
        // Non-ASCII occurs only inside JSON string literals, where a \\uXXXX
        // escape (surrogates as two escapes) is always valid.
        if (ch.code < 0x80) out.append(ch) else out.append("\\u%04x".format(ch.code))
    }
    return out.toString()
}

private fun sortKeys(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> JsonObject(el.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
    is JsonArray -> JsonArray(el.map { sortKeys(it) })
    else -> el
}

fun parseJson(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

class Db(path: String) {
    val conn: SQLiteConnection = BundledSQLiteDriver().open(path)

    init {
        // WAL allows a writer alongside readers: the engine thread and the
        // sync worker each open their own Db on the same file.
        conn.prepare("PRAGMA journal_mode=WAL").use { it.step() }
        conn.execSQL("PRAGMA busy_timeout=5000")
        conn.execSQL("PRAGMA foreign_keys=ON")
        for (statement in SCHEMA.trim().split(";")) {
            if (statement.isNotBlank()) conn.execSQL(statement)
        }
    }

    fun close() = conn.close()

    private inline fun <R> query(sql: String, bind: (SQLiteStatement) -> Unit = {}, row: (SQLiteStatement) -> R): List<R> =
        conn.prepare(sql).use { st ->
            bind(st)
            val out = mutableListOf<R>()
            while (st.step()) out.add(row(st))
            out
        }

    private inline fun exec(sql: String, bind: (SQLiteStatement) -> Unit) =
        conn.prepare(sql).use { st ->
            bind(st)
            st.step()
        }

    // -- events -----------------------------------------------------------

    fun monthFile(dev: String, t: String): String = "events/$dev/${t.take(7)}.jsonl"

    /** Append one event authored by this device (assigns file position). */
    fun appendOwnEvent(ev: JsonObject) {
        val source = monthFile(ev.str("dev"), ev.str("t"))
        val line = query("SELECT COUNT(*) FROM events WHERE source_file = ?", { it.bindText(1, source) }) {
            it.getLong(0)
        }.first()
        exec(
            "INSERT INTO events (dev, ev, sample, stream, t, payload_json," +
                " synced, source_file, line) VALUES (?,?,?,?,?,?,0,?,?)"
        ) {
            it.bindText(1, ev.str("dev"))
            it.bindText(2, ev.str("ev"))
            bindTextOrNull(it, 3, ev.optStr("sample"))
            bindTextOrNull(it, 4, ev.optStr("stream"))
            it.bindText(5, ev.str("t"))
            it.bindText(6, dumpsLine(ev))
            it.bindText(7, source)
            it.bindLong(8, line)
        }
    }

    private fun bindTextOrNull(st: SQLiteStatement, index: Int, value: String?) {
        if (value == null) st.bindNull(index) else st.bindText(index, value)
    }

    /**
     * Replace the cached copy of another device's file. Returns the sample
     * ids whose event sets changed (for refolding).
     */
    fun importFile(sourceFile: String, rawLines: List<String>): List<String> {
        // Parse every line before touching the table so a malformed file
        // throws without half-replacing the cached copy.
        val parsed = rawLines.map { raw -> Pair(raw, parseJson(raw)) }
        for ((_, ev) in parsed) {
            ev.str("dev")
            ev.str("ev")
            ev.str("t")
        }

        val before = query(
            "SELECT line, payload_json FROM events WHERE source_file = ?",
            { it.bindText(1, sourceFile) },
        ) { Pair(it.getLong(0).toInt(), it.getText(1)) }.toSet()
        val after = rawLines.mapIndexed { i, raw -> Pair(i, raw) }.toSet()

        val affected = sortedSetOf<String>()
        for ((_, raw) in (after - before) + (before - after)) {
            (parseJson(raw).optStr("sample"))?.let { affected.add(it) }
        }

        conn.execSQL("BEGIN IMMEDIATE")
        try {
            exec("DELETE FROM events WHERE source_file = ?") { it.bindText(1, sourceFile) }
            for ((lineNo, entry) in parsed.withIndex()) {
                val (raw, ev) = entry
                exec(
                    "INSERT INTO events (dev, ev, sample, stream, t, payload_json," +
                        " synced, source_file, line) VALUES (?,?,?,?,?,?,1,?,?)"
                ) {
                    it.bindText(1, ev.str("dev"))
                    it.bindText(2, ev.str("ev"))
                    bindTextOrNull(it, 3, ev.optStr("sample"))
                    bindTextOrNull(it, 4, ev.optStr("stream"))
                    it.bindText(5, ev.str("t"))
                    it.bindText(6, raw)
                    it.bindText(7, sourceFile)
                    it.bindLong(8, lineNo.toLong())
                }
            }
            conn.execSQL("COMMIT")
        } catch (e: Exception) {
            conn.execSQL("ROLLBACK")
            throw e
        }
        return affected.toList()
    }

    fun eventsForSample(sample: String): List<EventTriple> =
        query(
            "SELECT source_file, line, payload_json FROM events" +
                " WHERE sample = ? ORDER BY source_file, line",
            { it.bindText(1, sample) },
        ) { Triple(it.getText(0), it.getLong(1).toInt(), parseJson(it.getText(2))) }

    fun eventsOfType(evType: String): List<JsonObject> =
        query(
            "SELECT payload_json FROM events WHERE ev = ? ORDER BY t",
            { it.bindText(1, evType) },
        ) { parseJson(it.getText(0)) }

    fun allSampleEvents(): List<JsonObject> =
        query(
            "SELECT payload_json FROM events WHERE sample IS NOT NULL" +
                " ORDER BY source_file, line"
        ) { parseJson(it.getText(0)) }

    fun unsyncedMonths(dev: String): List<String> =
        query(
            "SELECT DISTINCT source_file FROM events" +
                " WHERE dev = ? AND synced = 0 ORDER BY source_file",
            { it.bindText(1, dev) },
        ) { it.getText(0).substringAfterLast("/").removeSuffix(".jsonl") }

    fun monthLines(dev: String, month: String): List<String> = fileLines("events/$dev/$month.jsonl")

    /** Exact JSONL lines of one cached file (own or imported), in order. */
    fun fileLines(sourceFile: String): List<String> =
        query(
            "SELECT payload_json FROM events WHERE source_file = ? ORDER BY line",
            { it.bindText(1, sourceFile) },
        ) { it.getText(0) }

    /** Every cached event file path (own months plus imported copies). */
    fun eventFiles(): List<String> =
        query("SELECT DISTINCT source_file FROM events ORDER BY source_file") { it.getText(0) }

    /** Mark a month's events uploaded; `uptoLine` bounds the snapshot that
     * was actually written (events appended mid-upload stay unsynced). */
    fun markMonthSynced(dev: String, month: String, uptoLine: Int? = null) =
        if (uptoLine == null) {
            exec("UPDATE events SET synced = 1 WHERE source_file = ?") {
                it.bindText(1, "events/$dev/$month.jsonl")
            }
        } else {
            exec("UPDATE events SET synced = 1 WHERE source_file = ? AND line < ?") {
                it.bindText(1, "events/$dev/$month.jsonl")
                it.bindLong(2, uptoLine.toLong())
            }
        }

    // -- samples (folded view) -------------------------------------------

    fun upsertSample(row: JsonObject) =
        exec(
            "INSERT INTO samples (sample, stream, scheduled_utc, status, row_json)" +
                " VALUES (?,?,?,?,?) ON CONFLICT(sample) DO UPDATE SET" +
                " stream=excluded.stream, scheduled_utc=excluded.scheduled_utc," +
                " status=excluded.status, row_json=excluded.row_json"
        ) {
            it.bindText(1, row.str("sample"))
            it.bindText(2, row.str("stream"))
            it.bindText(3, row.str("scheduled_utc"))
            it.bindText(4, row.str("status"))
            it.bindText(5, Json.encodeToString(JsonElement.serializer(), row))
        }

    fun sampleRow(sample: String): JsonObject? =
        query("SELECT row_json FROM samples WHERE sample = ?", { it.bindText(1, sample) }) {
            parseJson(it.getText(0))
        }.firstOrNull()

    fun sampleRows(stream: String? = null, statuses: List<String>? = null): List<JsonObject> {
        var sql = "SELECT row_json FROM samples"
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()
        if (stream != null) {
            clauses.add("stream = ?")
            params.add(stream)
        }
        if (statuses != null) {
            clauses.add("status IN (${statuses.joinToString(",") { "?" }})")
            params.addAll(statuses)
        }
        if (clauses.isNotEmpty()) sql += " WHERE " + clauses.joinToString(" AND ")
        sql += " ORDER BY scheduled_utc"
        return query(sql, { st -> params.forEachIndexed { i, p -> st.bindText(i + 1, p) } }) {
            parseJson(it.getText(0))
        }
    }

    // -- schedule ---------------------------------------------------------

    /** Rebuild the materialized horizon (states recomputed by caller). */
    fun replaceSchedule(rows: List<JsonObject>) {
        conn.execSQL("DELETE FROM schedule")
        for (r in rows) {
            exec(
                "INSERT INTO schedule (sample, stream, scheduled_utc, config_v," +
                    " suppressed_reason, idx, state) VALUES (?,?,?,?,?,?,?)"
            ) {
                it.bindText(1, r.str("sample"))
                it.bindText(2, r.str("stream"))
                it.bindText(3, r.str("scheduled_utc"))
                it.bindLong(4, r.int("config_v").toLong())
                bindTextOrNull(it, 5, r.optStr("suppressed_reason"))
                it.bindLong(6, r.int("idx").toLong())
                it.bindText(7, r.str("state"))
            }
        }
    }

    data class ScheduleRow(
        val sample: String,
        val stream: String,
        val scheduledUtc: String,
        val configV: Int,
        val suppressedReason: String?,
        val idx: Int,
    )

    fun dueSchedule(nowIso: String): List<ScheduleRow> =
        query(
            "SELECT sample, stream, scheduled_utc, config_v," +
                " suppressed_reason, idx FROM schedule" +
                " WHERE state = 'planned' AND scheduled_utc <= ? ORDER BY scheduled_utc",
            { it.bindText(1, nowIso) },
        ) {
            ScheduleRow(
                it.getText(0), it.getText(1), it.getText(2), it.getLong(3).toInt(),
                if (it.isNull(4)) null else it.getText(4), it.getLong(5).toInt(),
            )
        }

    fun nextPlanned(nowIso: String): String? =
        query(
            "SELECT MIN(scheduled_utc) FROM schedule" +
                " WHERE state = 'planned' AND scheduled_utc > ?",
            { it.bindText(1, nowIso) },
        ) { if (it.isNull(0)) null else it.getText(0) }.firstOrNull()

    fun markSchedule(sample: String, state: String) =
        exec("UPDATE schedule SET state = ? WHERE sample = ?") {
            it.bindText(1, state)
            it.bindText(2, sample)
        }

    // -- cloud document caches -------------------------------------------

    fun upsertConfig(doc: JsonObject) =
        exec("INSERT OR REPLACE INTO config_cache (version, doc_json) VALUES (?,?)") {
            it.bindLong(1, doc.int("version").toLong())
            it.bindText(2, Json.encodeToString(JsonElement.serializer(), doc))
        }

    fun configHistory(): List<JsonObject> =
        query("SELECT doc_json FROM config_cache ORDER BY version") { parseJson(it.getText(0)) }

    fun latestConfig(): JsonObject? =
        query("SELECT doc_json FROM config_cache ORDER BY version DESC LIMIT 1") {
            parseJson(it.getText(0))
        }.firstOrNull()

    fun upsertSurvey(doc: JsonObject) =
        exec("INSERT OR REPLACE INTO survey_cache (survey_id, version, doc_json) VALUES (?,?,?)") {
            it.bindText(1, doc.str("id"))
            it.bindLong(2, doc.int("version").toLong())
            it.bindText(3, Json.encodeToString(JsonElement.serializer(), doc))
        }

    fun survey(surveyId: String, version: Int): JsonObject? =
        query(
            "SELECT doc_json FROM survey_cache WHERE survey_id = ? AND version = ?",
            {
                it.bindText(1, surveyId)
                it.bindLong(2, version.toLong())
            },
        ) { parseJson(it.getText(0)) }.firstOrNull()

    fun allSurveys(): Map<Pair<String, Int>, JsonObject> =
        query("SELECT survey_id, version, doc_json FROM survey_cache") {
            Pair(Pair(it.getText(0), it.getLong(1).toInt()), parseJson(it.getText(2)))
        }.toMap()

    // -- kv (device / sync_meta / state) ---------------------------------

    fun kvGet(ns: String, key: String): String? =
        query(
            "SELECT value FROM kv WHERE ns = ? AND key = ?",
            {
                it.bindText(1, ns)
                it.bindText(2, key)
            },
        ) { it.getText(0) }.firstOrNull()

    fun kvSet(ns: String, key: String, value: String) =
        exec("INSERT OR REPLACE INTO kv (ns, key, value) VALUES (?,?,?)") {
            it.bindText(1, ns)
            it.bindText(2, key)
            it.bindText(3, value)
        }

    fun kvAll(ns: String): Map<String, String> =
        query("SELECT key, value FROM kv WHERE ns = ?", { it.bindText(1, ns) }) {
            Pair(it.getText(0), it.getText(1))
        }.toMap()

    // -- tag vocabulary ---------------------------------------------------

    fun bumpTags(vocab: String, tags: List<String>, nowIso: String) {
        for (tag in tags) {
            exec(
                "INSERT INTO tag_vocab (vocab, tag, last_used, count) VALUES (?,?,?,1)" +
                    " ON CONFLICT(vocab, tag) DO UPDATE SET" +
                    " last_used = excluded.last_used, count = count + 1"
            ) {
                it.bindText(1, vocab)
                it.bindText(2, tag)
                it.bindText(3, nowIso)
            }
        }
    }

    fun suggestTags(vocab: String, prefix: String, limit: Int = 8): List<String> {
        val like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return query(
            "SELECT tag FROM tag_vocab WHERE vocab = ? AND tag LIKE ? ESCAPE '\\'" +
                " ORDER BY count DESC, last_used DESC LIMIT ?",
            {
                it.bindText(1, vocab)
                it.bindText(2, "$like%")
                it.bindLong(3, limit.toLong())
            },
        ) { it.getText(0) }
    }
}
