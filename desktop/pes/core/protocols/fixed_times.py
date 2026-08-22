"""Fixed-times protocol (spec §6.3).

Domain is exactly the listed local wall-clock times on matching local days,
resolved per §4 (nonexistent -> suppressed(dst) at the pre-transition-offset
instant; duplicated -> first occurrence). Generation order is ``times_local``
order, which the collision rule relies on.
"""

from __future__ import annotations

from datetime import date
from zoneinfo import ZoneInfo

from ..timeutil import parse_hhmm, resolve_local, weekday_name
from .base import Candidate, register


def generate(protocol: dict, stream_seed: str, local_day: date, tz: ZoneInfo) -> list[Candidate]:
    days = protocol.get("days")
    if days is not None and weekday_name(local_day) not in days:
        return []
    out: list[Candidate] = []
    for hhmm in protocol["times_local"]:
        hh, mm = parse_hhmm(hhmm)
        utc, gap = resolve_local(local_day, hh, mm, tz)
        out.append(Candidate(utc, dst_gap=gap))
    return out


register("fixed_times", generate)
