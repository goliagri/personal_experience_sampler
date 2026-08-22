/**
 * Per-stream CSV export (spec §14). Mirrors `pes/core/export.py`; byte-exact
 * output is pinned by `spec/export_vectors/` (UTF-8, RFC 4180 CRLF + minimal
 * quoting, booleans `true`/`false`, `;` joins, sorted by scheduled_utc).
 *
 * Cells sourced from JSON keep their canonical lexical form
 * (JsonPrimitive.content), which matches the Python side's repr of the same
 * parsed values for canonically-serialized documents.
 */
package pes.core

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val FIXED_COLUMNS = listOf(
    "sample_id", "scheduled_utc", "scheduled_local", "status", "prior_status",
    "late", "test", "partial", "answered_at", "latency_s", "snoozes",
    "fired_on", "answered_on", "config_version", "survey_version",
    "lat", "lon", "loc_accuracy_m", "loc_age_s",
)

data class ColumnDesc(
    val column: String,
    val fieldId: String,
    val type: String,
    val surveyVersions: MutableList<Int>,
)

/** The `f_` column descriptors for a stream (also columns.json). */
fun fieldColumns(rows: List<JsonObject>, surveys: Map<Pair<String, Int>, JsonObject>): List<ColumnDesc> {
    val versionsUsed = rows
        .filter { it["survey_id"] !is JsonNull && it["survey_id"] != null }
        .map { Pair(it.str("survey_id"), it.int("survey_version")) }
        .distinct()
        .sortedBy { it.second }
    val columns = mutableListOf<ColumnDesc>()
    val byField = mutableMapOf<String, ColumnDesc>()
    for (surveyKey in versionsUsed) {
        val survey = surveys.getValue(surveyKey)
        for (field in survey.objList("fields")) {
            val fid = field.str("id")
            val existing = byField[fid]
            if (existing == null) {
                val desc = ColumnDesc("f_$fid", fid, field.str("type"), mutableListOf(surveyKey.second))
                byField[fid] = desc
                columns.add(desc)
            } else {
                existing.surveyVersions.add(surveyKey.second)
            }
        }
    }
    return columns
}

private fun cell(value: JsonElement?): String = when {
    value == null || value is JsonNull -> ""
    value is JsonArray -> value.joinToString(";") { cell(it) }
    value is JsonPrimitive -> value.content
    else -> value.toString()
}

private fun quote(cell: String): String =
    if (cell.any { it in ",\"\r\n" }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell

/**
 * Render (csvBytes, columnDescriptors) for one stream's folded rows.
 * `rows` are fold output objects; `scheduled` (future) rows must not be
 * passed. `surveys` maps (surveyId, version) -> survey document.
 */
fun exportCsv(
    rows: List<JsonObject>,
    surveys: Map<Pair<String, Int>, JsonObject>,
    timezone: String,
): Pair<ByteArray, List<ColumnDesc>> {
    val zone = ZoneId.of(timezone)
    val columns = fieldColumns(rows, surveys)
    val header = FIXED_COLUMNS + columns.map { it.column }

    val lines = mutableListOf(header.joinToString(","))
    for (row in rows.sortedBy { it.str("scheduled_utc") }) {
        val loc = row["loc"] as? JsonObject
        val answers = row.optObj("answers") ?: JsonObject(emptyMap())
        val cells = listOf(
            row["sample"], row["scheduled_utc"],
            JsonPrimitive(fmtLocal(parseUtc(row.str("scheduled_utc")), zone)),
            row["status"], row["prior_status"],
            row["late"], row["test"], row["partial"],
            row["answered_at"], row["latency_s"], row["snoozes"],
            row["fired_on"], row["answered_on"],
            row["config_version"], row["survey_version"],
            loc?.get("lat"), loc?.get("lon"), loc?.get("acc_m"), loc?.get("age_s"),
        ) + columns.map { answers[it.fieldId] }
        lines.add(cells.joinToString(",") { quote(cell(it)) })
    }

    val bytes = (lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    return Pair(bytes, columns)
}

/** Byte-exact `{stream_id}.columns.json` rendering (matches Python's
 * `json.dumps(columns, indent=2) + "\n"`). */
fun columnsJson(columns: List<ColumnDesc>): ByteArray {
    if (columns.isEmpty()) return "[]\n".toByteArray(Charsets.UTF_8)
    val sb = StringBuilder()
    sb.append("[\n")
    columns.forEachIndexed { i, c ->
        sb.append("  {\n")
        sb.append("    \"column\": \"${c.column}\",\n")
        sb.append("    \"field_id\": \"${c.fieldId}\",\n")
        sb.append("    \"type\": \"${c.type}\",\n")
        sb.append("    \"survey_versions\": [\n")
        c.surveyVersions.forEachIndexed { j, v ->
            sb.append("      $v")
            sb.append(if (j < c.surveyVersions.size - 1) ",\n" else "\n")
        }
        sb.append("    ]\n")
        sb.append("  }")
        sb.append(if (i < columns.size - 1) ",\n" else "\n")
    }
    sb.append("]\n")
    return sb.toString().toByteArray(Charsets.UTF_8)
}
