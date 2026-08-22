"""Time handling: UTC formatting, DST resolution, local days, quiet zones (spec §4).

All elapsed-time arithmetic is in UTC epoch seconds (ints). Wall-clock
specifications are converted to UTC instants with the configured timezone
under the spec's DST rules:

- nonexistent local time (spring-forward gap) -> the instant the wall clock
  would have shown it under the pre-transition offset, flagged as a gap;
- duplicated local time (fall-back) -> the first occurrence.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

WEEKDAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


def parse_utc(s: str) -> int:
    """ISO-8601 ``...Z`` string -> epoch seconds."""
    return int(datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC).timestamp())


def fmt_utc(epoch: int) -> str:
    """Epoch seconds -> ISO-8601 ``...Z`` string, second resolution."""
    return datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def fmt_local(epoch: int, tz: ZoneInfo) -> str:
    """Epoch seconds -> local wall-clock ISO string without offset (exports)."""
    return datetime.fromtimestamp(epoch, tz).strftime("%Y-%m-%dT%H:%M:%S")


def resolve_local(day: date, hh: int, mm: int, tz: ZoneInfo) -> tuple[int, bool]:
    """Resolve a wall-clock time on a local day to (epoch_seconds, is_gap).

    ``fold=0`` gives PEP 495 semantics matching the spec: nonexistent times
    resolve with the pre-transition offset (is_gap=True); ambiguous times
    resolve to the first occurrence.
    """
    naive = datetime.combine(day, time(hh, mm))
    utc = naive.replace(tzinfo=tz, fold=0).astimezone(UTC)
    round_trip = utc.astimezone(tz).replace(tzinfo=None)
    return int(utc.timestamp()), round_trip != naive


def local_day_bounds(day: date, tz: ZoneInfo) -> tuple[int, int]:
    """UTC bounds [start, end) of a local calendar day (23, 24, or 25 h)."""
    start, _ = resolve_local(day, 0, 0, tz)
    end, _ = resolve_local(day + timedelta(days=1), 0, 0, tz)
    return start, end


def parse_hhmm(s: str) -> tuple[int, int]:
    hh, mm = s.split(":")
    return int(hh), int(mm)


def weekday_name(d: date) -> str:
    return WEEKDAYS[d.weekday()]


def in_quiet_zone(epoch: int, zones: list[dict], tz: ZoneInfo) -> bool:
    """True if the instant falls in any quiet zone, evaluated on wall-clock
    local time (spec §4). Windows are half-open [from, to). A midnight-wrapping
    window (from > to) starts on each listed day and runs into the next day.
    """
    local = datetime.fromtimestamp(epoch, tz)
    t = local.time()
    today = weekday_name(local.date())
    yesterday = weekday_name(local.date() - timedelta(days=1))
    for zone in zones:
        days = set(zone.get("days", WEEKDAYS))
        f_hh, f_mm = parse_hhmm(zone["from"])
        t_hh, t_mm = parse_hhmm(zone["to"])
        frm, to = time(f_hh, f_mm), time(t_hh, t_mm)
        if frm < to:
            if today in days and frm <= t < to:
                return True
        else:  # wraps midnight
            if (today in days and t >= frm) or (yesterday in days and t < to):
                return True
    return False


def utc_day_of(epoch: int) -> date:
    return datetime.fromtimestamp(epoch, UTC).date()


def utc_midnight(day: date) -> int:
    return int(datetime.combine(day, time(0, 0), tzinfo=UTC).timestamp())
