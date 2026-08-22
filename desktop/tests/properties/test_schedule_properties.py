"""Property tests for the scheduler (test plan §3): stability, min gap,
seam/completeness over DST days, statistical sanity.
"""

import itertools
from datetime import date, timedelta
from zoneinfo import ZoneInfo

import pytest

from pes.core.scheduler import resolve_day
from pes.core.timeutil import local_day_bounds

SEED = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"


def config(protocol, tz="America/Los_Angeles", quiet_zones=None):
    return {
        "version": 1,
        "effective_from": "2026-01-01T00:00:00Z",
        "timezone": tz,
        "streams": [{
            "id": "p", "name": "p", "enabled": True, "seed": SEED,
            "protocol": protocol, "quiet_zones": quiet_zones or [],
            "survey": {"id": "s", "version": 1},
        }],
    }


POISSON = {"type": "poisson", "mean_gap_minutes": 90, "min_gap_minutes": 15}
# Spans both 2026 US DST transitions.
DAYS = [date(2026, 3, 6) + timedelta(days=d) for d in range(5)] + \
       [date(2026, 10, 30) + timedelta(days=d) for d in range(5)] + \
       [date(2026, 8, 18) + timedelta(days=d) for d in range(3)]


@pytest.mark.parametrize("day", DAYS, ids=str)
def test_schedule_stability(day):
    cfg = [config(POISSON)]
    first = [(r.scheduled_utc, r.suppressed_reason, r.config_v) for r in resolve_day(cfg, "p", day)]
    second = [(r.scheduled_utc, r.suppressed_reason, r.config_v) for r in resolve_day(cfg, "p", day)]
    assert first == second


def test_min_gap_within_utc_day():
    cfg = [config(POISSON, tz="UTC")]
    for day in DAYS:
        times = [r.scheduled_utc for r in resolve_day(cfg, "p", day)]
        for a, b in itertools.pairwise(times):
            if a // 86400 == b // 86400:
                assert b - a >= 15 * 60


@pytest.mark.parametrize("protocol", [
    POISSON,
    {"type": "stratified", "interval_minutes": 120, "pings_per_interval": 2},
    {"type": "fixed_interval", "every_minutes": 240, "anchor_local": "01:30"},
    {"type": "fixed_times", "times_local": ["02:30", "09:00", "23:30"]},
], ids=lambda p: p["type"])
def test_seam_no_candidate_in_two_windows(protocol):
    """Every candidate lies in its day's window; adjacent days never share a
    scheduled second (completeness/seam property, incl. DST days)."""
    cfg = [config(protocol)]
    tz = ZoneInfo("America/Los_Angeles")
    seen: dict[int, date] = {}
    for day in DAYS:
        start, _end = local_day_bounds(day, tz)
        for r in resolve_day(cfg, "p", day):
            # Collision shifts may push past the window end (pinned in
            # schedule_vectors); membership in exactly one day still holds.
            assert r.scheduled_utc >= start
            assert r.scheduled_utc not in seen or seen[r.scheduled_utc] == day
            seen[r.scheduled_utc] = day


def test_poisson_statistical_sanity():
    cfg = [config({"type": "poisson", "mean_gap_minutes": 90}, tz="UTC")]
    times = []
    for d in range(60):
        day = date(2026, 5, 1) + timedelta(days=d)
        times += [r.scheduled_utc for r in resolve_day(cfg, "p", day)]
    gaps = [b - a for a, b in itertools.pairwise(times)]
    mean = sum(gaps) / len(gaps)
    assert 0.8 * 90 * 60 < mean < 1.2 * 90 * 60


def test_stratified_counts_per_full_interval():
    cfg = [config({"type": "stratified", "interval_minutes": 120, "pings_per_interval": 2}, tz="UTC")]
    resolved = resolve_day(cfg, "p", date(2026, 5, 1))
    assert len(resolved) == 12 * 2
    for k in range(12):
        lo = k * 7200
        within = [r for r in resolved if lo <= (r.scheduled_utc % 86400) < lo + 7200]
        assert len(within) == 2
