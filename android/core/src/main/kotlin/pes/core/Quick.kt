/**
 * Quick/full survey selection (spec §7). Mirrors `pes/core/quick.py`.
 *
 * `index` is the sample's 0-based position in its local day's resolved
 * candidate list — after the collision rule, before suppression, so
 * suppressed candidates count. The list is `resolveDay`'s output order.
 */
package pes.core

import kotlinx.serialization.json.JsonObject

fun isFull(index: Int, fullSurveyEveryN: Int): Boolean =
    fullSurveyEveryN <= 1 || index % fullSurveyEveryN == 0

/**
 * Fields shown for a full or quick presentation. A quick presentation shows
 * `quick: true` fields; if none are flagged, the first `tags` field.
 */
fun presentedFields(survey: JsonObject, full: Boolean): List<JsonObject> {
    val fields = survey.objList("fields")
    if (full) return fields
    val quick = fields.filter { it.bool("quick", false) }
    if (quick.isNotEmpty()) return quick
    return fields.filter { it.str("type") == "tags" }.take(1)
}
