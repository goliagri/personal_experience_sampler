"""Poisson protocol (spec §6.3).

Scope: per UTC day (``day:YYYY-MM-DD``). For a requested local day, every
overlapping UTC day is generated and candidates are filtered to the local-day
bounds. Starting at 00:00Z, exponential gaps with the configured mean are
drawn until the UTC day ends. ``min_gap_minutes`` is enforced only within a
UTC day, never across 00:00Z.
"""

from __future__ import annotations

import math
from datetime import date, timedelta
from zoneinfo import ZoneInfo

from ..fdlibm_log import fdlibm_log
from ..prng import rng_for
from ..timeutil import local_day_bounds, utc_day_of, utc_midnight
from .base import Candidate, register

DAY_S = 86400


def generate(protocol: dict, stream_seed: str, local_day: date, tz: ZoneInfo) -> list[Candidate]:
    start, end = local_day_bounds(local_day, tz)
    mean_s = protocol["mean_gap_minutes"] * 60.0
    min_gap = protocol.get("min_gap_minutes")
    min_s = None if min_gap is None else int(min_gap * 60)

    out: list[Candidate] = []
    utc_day = utc_day_of(start)
    last_day = utc_day_of(end - 1)
    while utc_day <= last_day:
        day_start = utc_midnight(utc_day)
        day_end = day_start + DAY_S
        rng = rng_for(stream_seed, f"day:{utc_day.isoformat()}")
        cursor = day_start
        prev = None
        while True:
            u = rng.uniform()
            gap = math.floor(-mean_s * fdlibm_log(1.0 - u))
            cursor += gap
            if prev is not None and min_s is not None and cursor - prev < min_s:
                cursor = prev + min_s
            if cursor >= day_end:
                break
            if start <= cursor < end:
                out.append(Candidate(cursor))
            prev = cursor
        utc_day += timedelta(days=1)
    return out


register("poisson", generate)
