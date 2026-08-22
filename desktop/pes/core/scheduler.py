"""Scheduler core (spec §6.1–6.2): piecewise config history, collision rule,
quiet zones, DST suppression.

``resolve_day`` produces the resolved candidate list for one stream and one
local calendar day. The returned list is sorted by scheduled time; each
sample's position in it is the 0-based index used for quick/full selection
(§7) — suppressed candidates included.

Piecewise semantics (pinned here; the spec implies them): for each config
version v effective during the day, the day is generated in full under v
(protocol, timezone, seed) and candidates are kept iff they fall inside the
intersection of v's effective interval with the local-day window computed
under v's timezone. Generation order for the collision rule is segment order,
then the protocol's own emission order.

Quiet mode (§6.1 step 6) is evaluated at fire time by the client, not here.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from zoneinfo import ZoneInfo

from .protocols import get_protocol
from .timeutil import in_quiet_zone, local_day_bounds, parse_utc


@dataclass
class ResolvedSample:
    scheduled_utc: int
    config_v: int
    suppressed_reason: str | None = None  # "quiet_zone" | "dst" | None
    index: int = -1  # 0-based position in the day's time-sorted list


def _stream_in(config: dict, stream_id: str) -> dict | None:
    for s in config["streams"]:
        if s["id"] == stream_id:
            return s
    return None


def resolve_day(
    config_history: list[dict], stream_id: str, local_day: date
) -> list[ResolvedSample]:
    """Resolved candidate list for one stream on one local day.

    ``config_history`` is the ordered list of config documents (ascending
    ``version``); the version in effect at an instant is the highest one with
    ``effective_from`` <= instant (§6.1 step 1).
    """
    history = sorted(config_history, key=lambda c: c["version"])
    boundaries = [parse_utc(c["effective_from"]) for c in history]

    # Generate per effective segment, in segment order.
    raw: list[tuple[int, bool, int]] = []  # (utc, dst_gap, config_v) in generation order
    for i, config in enumerate(history):
        seg_start = boundaries[i]
        seg_end = boundaries[i + 1] if i + 1 < len(history) else None
        stream = _stream_in(config, stream_id)
        if stream is None or not stream.get("enabled", True):
            continue
        tz = ZoneInfo(config["timezone"])
        day_start, day_end = local_day_bounds(local_day, tz)
        lo = max(day_start, seg_start)
        hi = day_end if seg_end is None else min(day_end, seg_end)
        if lo >= hi:
            continue
        generate = get_protocol(stream["protocol"]["type"])
        for cand in generate(stream["protocol"], stream["seed"], local_day, tz):
            if lo <= cand.utc < hi:
                raw.append((cand.utc, cand.dst_gap, config["version"]))

    # Collision rule (§6.2): in generation order, shift +1 s until unique.
    by_version = {c["version"]: c for c in history}
    occupied: set[int] = set()
    resolved: list[ResolvedSample] = []
    for utc, dst_gap, config_v in raw:
        while utc in occupied:
            utc += 1
        occupied.add(utc)
        # Suppression: spec order is quiet zones (step 4) then DST (step 5);
        # the first applicable reason sticks. Evaluated under the config
        # version that generated the candidate.
        config = by_version[config_v]
        stream = _stream_in(config, stream_id)
        tz = ZoneInfo(config["timezone"])
        reason = None
        if in_quiet_zone(utc, stream.get("quiet_zones", []), tz):
            reason = "quiet_zone"
        elif dst_gap:
            reason = "dst"
        resolved.append(ResolvedSample(utc, config_v, reason))

    resolved.sort(key=lambda r: r.scheduled_utc)
    for i, r in enumerate(resolved):
        r.index = i
    return resolved
