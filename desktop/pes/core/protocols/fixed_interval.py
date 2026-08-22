"""Fixed-interval protocol (spec §6.3).

Deterministic: resolve ``anchor_local`` ("HH:MM") to a UTC instant for the
local day (DST rules §4), then emit every ``every_minutes`` in UTC until the
end of the local day. Only the anchor itself can be DST-gap-flagged; later
emissions are pure UTC arithmetic.
"""

from __future__ import annotations

from datetime import date
from zoneinfo import ZoneInfo

from ..timeutil import local_day_bounds, parse_hhmm, resolve_local
from .base import Candidate, register


def generate(protocol: dict, stream_seed: str, local_day: date, tz: ZoneInfo) -> list[Candidate]:
    _, end = local_day_bounds(local_day, tz)
    hh, mm = parse_hhmm(protocol["anchor_local"])
    anchor, gap = resolve_local(local_day, hh, mm, tz)
    step = protocol["every_minutes"] * 60

    out: list[Candidate] = []
    t = anchor
    while t < end:
        out.append(Candidate(t, dst_gap=(gap and t == anchor)))
        t += step
    return out


register("fixed_interval", generate)
