/**
 * Small helpers over the kotlinx-serialization JSON tree API. The core works
 * on JsonObject trees (mirroring the Python core's plain dicts) so config,
 * event, and survey documents keep their wire shape end to end.
 */
package pes.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun JsonObject.str(key: String): String = (getValue(key) as JsonPrimitive).content

fun JsonObject.optStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun JsonObject.int(key: String): Int = (getValue(key) as JsonPrimitive).content.toInt()

fun JsonObject.double(key: String): Double = (getValue(key) as JsonPrimitive).content.toDouble()

fun JsonObject.optDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()

fun JsonObject.bool(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

fun JsonObject.obj(key: String): JsonObject = getValue(key) as JsonObject

fun JsonObject.optObj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray = getValue(key) as JsonArray

fun JsonObject.optArr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objList(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.map { it as JsonObject } ?: emptyList()

fun JsonObject.optStringList(key: String): List<String>? =
    (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }

/** A JSON primitive that is an unquoted number (not a string or boolean). */
fun JsonElement?.isNumber(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    return !p.isString && p !is JsonNull && p.content.toDoubleOrNull() != null
}

/** A JSON primitive that is an unquoted integer (booleans excluded). */
fun JsonElement?.isInt(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    return !p.isString && p !is JsonNull && p.content.toLongOrNull() != null
}
