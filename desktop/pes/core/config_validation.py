"""Config document validation (spec §8.1).

``validate_config`` returns a sorted list of stable error codes (strings of
the form ``code:detail``) so conformance vectors can assert them exactly.
An empty list means the document is valid.
"""

from __future__ import annotations

import re
from zoneinfo import ZoneInfo

from .timeutil import parse_utc

SLUG_RE = re.compile(r"^[a-z0-9_]{1,32}$")
SEED_RE = re.compile(r"^[0-9a-f]{32}$")
HHMM_RE = re.compile(r"^([01][0-9]|2[0-3]):[0-5][0-9]$")
WEEKDAY_SET = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"}
LOCATION_VALUES = {"off", "coarse", "precise"}
DEFAULT_KEYS = {"snooze_minutes", "max_snoozes", "expiry_minutes", "backlog_hours", "location"}
PROTOCOL_TYPES = {"poisson", "stratified", "fixed_interval", "fixed_times"}


def validate_config(
    config: dict,
    known_surveys: list[tuple[str, int]] | None = None,
    now: str | None = None,
) -> list[str]:
    """Validate a config document.

    ``known_surveys``: (survey_id, version) pairs that exist; None skips
    reference checking. ``now``: ISO instant for the past-``effective_from``
    check; None skips it (e.g. when re-validating history).
    """
    errors: list[str] = []

    for key in ("version", "timezone", "effective_from", "streams"):
        if key not in config:
            errors.append(f"missing_field:{key}")
    if errors:
        return sorted(errors)

    if not isinstance(config["version"], int) or config["version"] < 1:
        errors.append("bad_version")
    try:
        ZoneInfo(config["timezone"])
    except (KeyError, ValueError, TypeError, OSError):
        # ZoneInfoNotFoundError subclasses KeyError; bad types raise the rest.
        errors.append(f"bad_timezone:{config['timezone']}")
    try:
        effective = parse_utc(config["effective_from"])
        if now is not None and effective < parse_utc(now):
            errors.append("effective_from_past")
    except ValueError:
        errors.append("bad_effective_from")

    for key in config.get("defaults", {}):
        if key not in DEFAULT_KEYS:
            errors.append(f"unknown_default:{key}")
    _check_settings(config.get("defaults", {}), "defaults", errors)

    seen_ids: set[str] = set()
    for stream in config["streams"]:
        sid = stream.get("id", "")
        label = sid or "?"
        if not SLUG_RE.match(sid):
            errors.append(f"bad_stream_id:{label}")
        if sid in seen_ids:
            errors.append(f"duplicate_stream_id:{sid}")
        seen_ids.add(sid)
        if not SEED_RE.match(stream.get("seed", "")):
            errors.append(f"bad_seed:{label}")
        _check_protocol(stream.get("protocol", {}), label, errors)
        for zone in stream.get("quiet_zones", []):
            _check_quiet_zone(zone, label, errors)
        if known_surveys is not None:
            ref = stream.get("survey", {})
            if (ref.get("id"), ref.get("version")) not in known_surveys:
                errors.append(f"dangling_survey:{label}")
        n = stream.get("full_survey_every_n", 1)
        if not isinstance(n, int) or isinstance(n, bool) or n < 1:
            errors.append(f"bad_full_survey_every_n:{label}")
        if stream.get("location", "off") not in LOCATION_VALUES:
            errors.append(f"bad_location:{label}")
        _check_settings(stream.get("overrides", {}), label, errors)
        for key in stream.get("overrides", {}):
            if key not in DEFAULT_KEYS:
                errors.append(f"unknown_override:{label}")

    return sorted(errors)


def _check_settings(settings: dict, label: str, errors: list[str]) -> None:
    for key in ("snooze_minutes", "max_snoozes", "expiry_minutes", "backlog_hours"):
        if key in settings:
            v = settings[key]
            if not isinstance(v, int) or isinstance(v, bool) or v < 1:
                errors.append(f"bad_setting:{label}.{key}")
    if "location" in settings and settings["location"] not in LOCATION_VALUES:
        errors.append(f"bad_setting:{label}.location")


def _check_protocol(protocol: dict, label: str, errors: list[str]) -> None:
    ptype = protocol.get("type")
    if ptype not in PROTOCOL_TYPES:
        errors.append(f"bad_protocol_type:{label}")
        return
    def positive_number(key: str) -> None:
        v = protocol.get(key)
        if not isinstance(v, (int, float)) or isinstance(v, bool) or v <= 0:
            errors.append(f"bad_protocol_param:{label}.{key}")

    if ptype == "poisson":
        positive_number("mean_gap_minutes")
        if "min_gap_minutes" in protocol:
            v = protocol["min_gap_minutes"]
            if not isinstance(v, (int, float)) or isinstance(v, bool) or v < 0:
                errors.append(f"bad_protocol_param:{label}.min_gap_minutes")
    elif ptype == "stratified":
        positive_number("interval_minutes")
        v = protocol.get("pings_per_interval")
        if not isinstance(v, int) or isinstance(v, bool) or v < 1:
            errors.append(f"bad_protocol_param:{label}.pings_per_interval")
    elif ptype == "fixed_interval":
        positive_number("every_minutes")
        if not HHMM_RE.match(protocol.get("anchor_local", "")):
            errors.append(f"bad_protocol_param:{label}.anchor_local")
    elif ptype == "fixed_times":
        times = protocol.get("times_local")
        if not isinstance(times, list) or not times:
            errors.append(f"bad_protocol_param:{label}.times_local")
        else:
            for t in times:
                if not isinstance(t, str) or not HHMM_RE.match(t):
                    errors.append(f"bad_protocol_param:{label}.times_local")
                    break
        if "days" in protocol:
            days = protocol["days"]
            if not isinstance(days, list) or not days or not set(days) <= WEEKDAY_SET:
                errors.append(f"bad_protocol_param:{label}.days")


def _check_quiet_zone(zone: dict, label: str, errors: list[str]) -> None:
    frm, to = zone.get("from", ""), zone.get("to", "")
    ok = True
    for value in (frm, to):
        if not isinstance(value, str) or not HHMM_RE.match(value):
            errors.append(f"bad_quiet_zone_time:{label}")
            ok = False
            break
    if ok and frm == to:
        errors.append(f"quiet_zone_from_equals_to:{label}")
    days = zone.get("days")
    if days is not None and (not isinstance(days, list) or not days or not set(days) <= WEEKDAY_SET):
        errors.append(f"bad_quiet_zone_days:{label}")
