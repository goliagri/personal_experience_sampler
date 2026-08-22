/**
 * Time handling: UTC formatting, DST resolution, local days, quiet zones
 * (spec §4). Mirrors `pes/core/timeutil.py`.
 *
 * java.time's defaults match the spec's DST rules: constructing a
 * ZonedDateTime at a nonexistent local time shifts it forward by the gap
 * length (the pre-transition offset applied), and an ambiguous local time
 * resolves to the earlier offset (first occurrence).
 */
package pes.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject

val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
private val LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** ISO-8601 `...Z` string -> epoch seconds. */
fun parseUtc(s: String): Long =
    LocalDateTime.parse(s, UTC_FORMAT).toEpochSecond(ZoneOffset.UTC)

/** Epoch seconds -> ISO-8601 `...Z` string, second resolution. */
fun fmtUtc(epoch: Long): String =
    LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC).format(UTC_FORMAT)

/** Epoch seconds -> local wall-clock ISO string without offset (exports). */
fun fmtLocal(epoch: Long, zone: ZoneId): String =
    Instant.ofEpochSecond(epoch).atZone(zone).format(LOCAL_FORMAT)

/**
 * Resolve a wall-clock time on a local day to (epochSeconds, isGap).
 * Nonexistent times resolve with the pre-transition offset (isGap = true);
 * ambiguous times resolve to the first occurrence.
 */
fun resolveLocal(day: LocalDate, hh: Int, mm: Int, zone: ZoneId): Pair<Long, Boolean> {
    val naive = LocalDateTime.of(day, LocalTime.of(hh, mm))
    val gap = zone.rules.getValidOffsets(naive).isEmpty()
    return Pair(naive.atZone(zone).toEpochSecond(), gap)
}

/** UTC bounds [start, end) of a local calendar day (23, 24, or 25 h). */
fun localDayBounds(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
    val (start, _) = resolveLocal(day, 0, 0, zone)
    val (end, _) = resolveLocal(day.plusDays(1), 0, 0, zone)
    return Pair(start, end)
}

fun parseHhmm(s: String): Pair<Int, Int> {
    val (hh, mm) = s.split(":")
    return Pair(hh.toInt(), mm.toInt())
}

fun weekdayName(d: LocalDate): String = WEEKDAYS[d.dayOfWeek.value - DayOfWeek.MONDAY.value]

/**
 * True if the instant falls in any quiet zone, evaluated on wall-clock local
 * time (spec §4). Windows are half-open [from, to). A midnight-wrapping
 * window (from > to) starts on each listed day and runs into the next day.
 */
fun inQuietZone(epoch: Long, zones: List<JsonObject>, zone: ZoneId): Boolean {
    val local = Instant.ofEpochSecond(epoch).atZone(zone)
    val t = local.toLocalTime()
    val today = weekdayName(local.toLocalDate())
    val yesterday = weekdayName(local.toLocalDate().minusDays(1))
    for (z in zones) {
        val days = z.optStringList("days")?.toSet() ?: WEEKDAYS.toSet()
        val (fh, fm) = parseHhmm(z.str("from"))
        val (th, tm) = parseHhmm(z.str("to"))
        val frm = LocalTime.of(fh, fm)
        val to = LocalTime.of(th, tm)
        if (frm < to) {
            if (today in days && t >= frm && t < to) return true
        } else { // wraps midnight
            if ((today in days && t >= frm) || (yesterday in days && t < to)) return true
        }
    }
    return false
}

fun utcDayOf(epoch: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC)

fun utcMidnight(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
