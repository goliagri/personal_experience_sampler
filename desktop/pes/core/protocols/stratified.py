"""Stratified protocol (spec §6.3).

Scope: per interval index within the local day (``interval:YYYY-MM-DD:k``).
Intervals of ``interval_minutes`` tile the actual local day (23/24/25 h) from
local midnight; a trailing partial interval gets
``floor(pings_per_interval * fraction)`` pings. Offsets are drawn uniformly in
whole seconds within the interval (``floor(u * interval_len)``), then sorted.
"""

from __future__ import annotations

import math
from datetime import date
from zoneinfo import ZoneInfo

from ..prng import rng_for
from ..timeutil import local_day_bounds
from .base import Candidate, register


def generate(protocol: dict, stream_seed: str, local_day: date, tz: ZoneInfo) -> list[Candidate]:
    start, end = local_day_bounds(local_day, tz)
    interval_s = protocol["interval_minutes"] * 60
    pings = protocol["pings_per_interval"]

    out: list[Candidate] = []
    k = 0
    pos = start
    while pos < end:
        length = min(interval_s, end - pos)
        n = pings if length == interval_s else math.floor(pings * length / interval_s)
        if n > 0:
            rng = rng_for(stream_seed, f"interval:{local_day.isoformat()}:{k}")
            offsets = sorted(math.floor(rng.uniform() * length) for _ in range(n))
            out.extend(Candidate(pos + off) for off in offsets)
        k += 1
        pos += interval_s
    return out


register("stratified", generate)
