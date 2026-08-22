"""Property tests for backfill (test plan §3): idempotence and completeness
(every generated candidate ends with exactly one folded row).
"""

from datetime import date, timedelta

from pes.core.backfill import backfill
from pes.core.fold import fold_sample
from pes.core.scheduler import resolve_day
from pes.core.timeutil import fmt_utc, parse_utc

SEED = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"
CFG = {
    "version": 1,
    "effective_from": "2026-01-01T00:00:00Z",
    "timezone": "America/Los_Angeles",
    "streams": [{
        "id": "p", "name": "p", "enabled": True, "seed": SEED,
        "protocol": {"type": "poisson", "mean_gap_minutes": 90},
        "quiet_zones": [{"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
                         "from": "23:00", "to": "07:30"}],
        "survey": {"id": "s", "version": 1},
    }],
}


def resolved_window(days):
    out = []
    for day in days:
        for r in resolve_day([CFG], "p", day):
            out.append({
                "sample": f"p|{fmt_utc(r.scheduled_utc)}",
                "stream": "p",
                "scheduled_utc": fmt_utc(r.scheduled_utc),
                "suppressed_reason": r.suppressed_reason,
            })
    return out


def test_backfill_idempotent_and_complete():
    days = [date(2026, 8, 18) + timedelta(days=d) for d in range(3)]
    samples = resolved_window(days)
    now = parse_utc("2026-08-21T12:00:00Z")

    # Devices were off the whole window: one device fired a few samples only.
    fired = [
        {"ev": "fired", "t": s["scheduled_utc"], "dev": "phone-a3f2c1d0",
         "sample": s["sample"], "stream": "p", "config_v": 1,
         "scheduled": s["scheduled_utc"]}
        for s in samples[:3] if not s["suppressed_reason"]
    ]
    quiet_history = [{"t": "2026-08-19T00:00:00Z", "quiet_until": "2026-08-19T04:00:00Z"}]

    first = backfill(samples, fired, quiet_history, now, "laptop-9c11aa00", 1,
                     {"p": 60})
    # Completeness: every past sample now has at least one classifying event.
    known = fired + first
    by_sample = {}
    for ev in known:
        by_sample.setdefault(ev["sample"], []).append(("f", 0, ev))
    for s in samples:
        if parse_utc(s["scheduled_utc"]) >= now:
            continue
        row = fold_sample(by_sample[s["sample"]], 60)
        assert row["status"] in {"expired", "unobserved", "suppressed"}, s["sample"]
        if s["suppressed_reason"]:
            assert row["status"] == "suppressed"

    # Idempotence: a second run over the same window emits nothing.
    second = backfill(samples, known, quiet_history, now, "laptop-9c11aa00", 1, {"p": 60})
    assert second == []

    # Determinism across devices: another device's backfill folds identically.
    other = backfill(samples, fired, quiet_history, now, "phone-a3f2c1d0", 1, {"p": 60})
    for ev_l, ev_p in zip(first, other):
        assert (ev_l["sample"], ev_l["ev"], ev_l.get("reason")) == \
               (ev_p["sample"], ev_p["ev"], ev_p.get("reason"))


def test_quiet_mode_window_classified_suppressed():
    days = [date(2026, 8, 19)]
    samples = resolved_window(days)
    now = parse_utc("2026-08-21T12:00:00Z")
    # Quiet mode covers the whole LA local day 2026-08-19 (07:00Z .. 07:00Z).
    quiet_history = [{"t": "2026-08-18T00:00:00Z", "quiet_until": "indefinite"},
                     {"t": "2026-08-20T12:00:00Z", "quiet_until": None}]
    out = backfill(samples, [], quiet_history, now, "laptop-9c11aa00", 1, {"p": 60})
    assert out, "window generated no samples"
    for ev in out:
        assert ev["ev"] == "suppressed"
        assert ev["reason"] in {"quiet_mode", "quiet_zone"}
