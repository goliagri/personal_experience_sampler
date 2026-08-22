/**
 * Config document validation (spec §8.1). Mirrors
 * `pes/core/config_validation.py`: returns the same sorted stable error codes
 * (`code:detail`) pinned by `spec/config_validation.json`.
 */
package pes.core

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val SLUG_RE = Regex("^[a-z0-9_]{1,32}$")
private val SEED_RE = Regex("^[0-9a-f]{32}$")
private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
private val WEEKDAY_SET = setOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
private val LOCATION_VALUES = setOf("off", "coarse", "precise")
private val DEFAULT_KEYS = setOf("snooze_minutes", "max_snoozes", "expiry_minutes", "backlog_hours", "location")
private val PROTOCOL_TYPES = setOf("poisson", "stratified", "fixed_interval", "fixed_times")

fun validateConfig(
    config: JsonObject,
    knownSurveys: List<Pair<String, Int>>? = null,
    now: String? = null,
): List<String> {
    val errors = mutableListOf<String>()

    for (key in listOf("version", "timezone", "effective_from", "streams")) {
        if (key !in config) errors.add("missing_field:$key")
    }
    if (errors.isNotEmpty()) return errors.sorted()

    if (!config["version"].isInt() || config.int("version") < 1) errors.add("bad_version")
    try {
        ZoneId.of(config.str("timezone"))
    } catch (e: Exception) {
        errors.add("bad_timezone:${config.str("timezone")}")
    }
    try {
        val effective = parseUtc(config.str("effective_from"))
        if (now != null && effective < parseUtc(now)) errors.add("effective_from_past")
    } catch (e: Exception) {
        errors.add("bad_effective_from")
    }

    val defaults = config.optObj("defaults") ?: JsonObject(emptyMap())
    for (key in defaults.keys) {
        if (key !in DEFAULT_KEYS) errors.add("unknown_default:$key")
    }
    checkSettings(defaults, "defaults", errors)

    val seenIds = mutableSetOf<String>()
    for (stream in config.objList("streams")) {
        val sid = stream.optStr("id") ?: ""
        val label = sid.ifEmpty { "?" }
        if (!SLUG_RE.matches(sid)) errors.add("bad_stream_id:$label")
        if (sid in seenIds) errors.add("duplicate_stream_id:$sid")
        seenIds.add(sid)
        if (!SEED_RE.matches(stream.optStr("seed") ?: "")) errors.add("bad_seed:$label")
        checkProtocol(stream.optObj("protocol") ?: JsonObject(emptyMap()), label, errors)
        for (zone in stream.objList("quiet_zones")) checkQuietZone(zone, label, errors)
        if (knownSurveys != null) {
            val ref = stream.optObj("survey") ?: JsonObject(emptyMap())
            val pair = Pair(ref.optStr("id"), (ref["version"] as? JsonPrimitive)?.content?.toIntOrNull())
            if (knownSurveys.none { it.first == pair.first && it.second == pair.second }) {
                errors.add("dangling_survey:$label")
            }
        }
        val n = stream["full_survey_every_n"]
        if (n != null && (!n.isInt() || stream.int("full_survey_every_n") < 1)) {
            errors.add("bad_full_survey_every_n:$label")
        }
        if ((stream.optStr("location") ?: "off") !in LOCATION_VALUES) errors.add("bad_location:$label")
        val overrides = stream.optObj("overrides") ?: JsonObject(emptyMap())
        checkSettings(overrides, label, errors)
        for (key in overrides.keys) {
            if (key !in DEFAULT_KEYS) errors.add("unknown_override:$label")
        }
    }

    return errors.sorted()
}

private fun checkSettings(settings: JsonObject, label: String, errors: MutableList<String>) {
    for (key in listOf("snooze_minutes", "max_snoozes", "expiry_minutes", "backlog_hours")) {
        val v = settings[key] ?: continue
        if (!v.isInt() || (v as JsonPrimitive).content.toLong() < 1) errors.add("bad_setting:$label.$key")
    }
    if ("location" in settings && settings.optStr("location") !in LOCATION_VALUES) {
        errors.add("bad_setting:$label.location")
    }
}

private fun checkProtocol(protocol: JsonObject, label: String, errors: MutableList<String>) {
    val ptype = protocol.optStr("type")
    if (ptype !in PROTOCOL_TYPES) {
        errors.add("bad_protocol_type:$label")
        return
    }

    fun positiveNumber(key: String) {
        val v = protocol[key]
        if (!v.isNumber() || (v as JsonPrimitive).content.toDouble() <= 0) {
            errors.add("bad_protocol_param:$label.$key")
        }
    }

    when (ptype) {
        "poisson" -> {
            positiveNumber("mean_gap_minutes")
            val mg = protocol["min_gap_minutes"]
            if (mg != null && (!mg.isNumber() || (mg as JsonPrimitive).content.toDouble() < 0)) {
                errors.add("bad_protocol_param:$label.min_gap_minutes")
            }
        }
        "stratified" -> {
            positiveNumber("interval_minutes")
            val v = protocol["pings_per_interval"]
            if (!v.isInt() || (v as JsonPrimitive).content.toLong() < 1) {
                errors.add("bad_protocol_param:$label.pings_per_interval")
            }
        }
        "fixed_interval" -> {
            positiveNumber("every_minutes")
            if (!HHMM_RE.matches(protocol.optStr("anchor_local") ?: "")) {
                errors.add("bad_protocol_param:$label.anchor_local")
            }
        }
        "fixed_times" -> {
            val times = protocol["times_local"] as? JsonArray
            if (times == null || times.isEmpty()) {
                errors.add("bad_protocol_param:$label.times_local")
            } else {
                for (t in times) {
                    val p = t as? JsonPrimitive
                    if (p == null || !p.isString || !HHMM_RE.matches(p.content)) {
                        errors.add("bad_protocol_param:$label.times_local")
                        break
                    }
                }
            }
            val days = protocol["days"]
            if (days != null) {
                val list = (days as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                if (list == null || list.isEmpty() || !WEEKDAY_SET.containsAll(list)) {
                    errors.add("bad_protocol_param:$label.days")
                }
            }
        }
    }
}

private fun checkQuietZone(zone: JsonObject, label: String, errors: MutableList<String>) {
    val frm = zone.optStr("from") ?: ""
    val to = zone.optStr("to") ?: ""
    var ok = true
    for (value in listOf(frm, to)) {
        if (!HHMM_RE.matches(value)) {
            errors.add("bad_quiet_zone_time:$label")
            ok = false
            break
        }
    }
    if (ok && frm == to) errors.add("quiet_zone_from_equals_to:$label")
    val days = zone["days"]
    if (days != null) {
        val list = (days as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        if (list == null || list.isEmpty() || !WEEKDAY_SET.containsAll(list)) {
            errors.add("bad_quiet_zone_days:$label")
        }
    }
}
