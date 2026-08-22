"""Protocol interface (spec §6.1).

A protocol's ``generate(protocol_config, stream_seed, local_day, tz)`` returns
candidate UTC times for one local calendar day, in generation order. The
scheduler core (collision rule, quiet zones, config piecewise) is layered on
top in ``scheduler.py``; protocols only emit candidates.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from zoneinfo import ZoneInfo


@dataclass
class Candidate:
    """One candidate ping time. ``utc`` is epoch seconds (whole).

    ``dst_gap`` marks a candidate whose local specification did not exist on
    this day (spring-forward); the scheduler turns it into suppressed(dst).
    """

    utc: int
    dst_gap: bool = False


GenerateFn = Callable[[dict, str, date, ZoneInfo], list[Candidate]]

_REGISTRY: dict[str, GenerateFn] = {}


def register(protocol_type: str, fn: GenerateFn) -> None:
    _REGISTRY[protocol_type] = fn


def get_protocol(protocol_type: str) -> GenerateFn:
    return _REGISTRY[protocol_type]
