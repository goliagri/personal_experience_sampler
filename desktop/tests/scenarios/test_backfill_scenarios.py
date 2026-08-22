"""Backfill and clock scenarios (test plan §2, "Backfill and clock")."""

from __future__ import annotations

from pes.core.timeutil import fmt_utc, parse_utc
from tests.scenarios.conftest import T0, base_config, fixed_stream

DAY1_0900 = "st|2026-08-24T16:00:00Z"  # 09:00 local
DAY1_1500 = "st|2026-08-24T22:00:00Z"  # 15:00 local


def test_overnight_off_classified_once(mkdevice, clock):
    """All devices off overnight: on wake exactly one row per candidate,
    watermark advances, a second run adds nothing."""
    dev = mkdevice("laptop-aaaa0001")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()  # nothing due yet

    clock.advance(24 * 3600)  # sleep through both of Monday's pings
    dev.engine.tick()  # jump detected -> backfill

    for sample in (DAY1_0900, DAY1_1500):
        events = dev.sample_events(sample)
        assert [ev["ev"] for ev in events] == ["unobserved"], sample
        assert dev.db.sample_row(sample)["status"] == "unobserved"

    # Idempotent: further runs emit nothing.
    assert dev.engine.backfill_now() == []
    dev.engine.tick()
    for sample in (DAY1_0900, DAY1_1500):
        assert len(dev.sample_events(sample)) == 1


def test_clock_jump_window_covered_once(mkdevice, clock):
    dev = mkdevice("laptop-aaaa0002")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()

    clock.advance(2 * 3600)  # hibernate through the 09:00 ping + its expiry
    dev.engine.tick()
    assert [ev["ev"] for ev in dev.sample_events(DAY1_0900)] == ["unobserved"]

    clock.advance(2 * 3600)  # second jump; already-covered window untouched
    dev.engine.tick()
    assert len(dev.sample_events(DAY1_0900)) == 1


def test_open_window_still_fires_after_short_sleep(mkdevice, clock):
    """A sample whose active window is still open when the device wakes is
    fired late (fired.t lags scheduled), not written off as unobserved."""
    dev = mkdevice("laptop-aaaa0003")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()

    clock.set(parse_utc("2026-08-24T16:30:00Z"))  # woke 30 min late; expiry 60
    dev.engine.tick()
    events = dev.sample_events(DAY1_0900)
    assert [ev["ev"] for ev in events] == ["fired"]
    assert events[0]["scheduled"] == "2026-08-24T16:00:00Z"
    assert events[0]["t"] == "2026-08-24T16:30:00Z"
    assert DAY1_0900 in dev.notifier.active()


def test_quiet_mode_window_backfills_suppressed(mkdevice, clock):
    dev = mkdevice("laptop-aaaa0004")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.set_quiet("indefinite")
    dev.engine.tick()

    clock.advance(24 * 3600)
    dev.engine.tick()
    for sample in (DAY1_0900, DAY1_1500):
        events = dev.sample_events(sample)
        assert [ev["ev"] for ev in events] == ["suppressed"], sample
        assert events[0]["reason"] == "quiet_mode"


def test_quiet_zone_candidate_suppressed_with_reason(mkdevice, clock):
    stream = fixed_stream()
    stream["quiet_zones"] = [
        {"days": ["mon"], "from": "08:30", "to": "10:00"}  # covers 09:00 local
    ]
    dev = mkdevice("laptop-aaaa0005")
    dev.boot(base_config([stream]))
    dev.engine.tick()

    clock.advance(24 * 3600)
    dev.engine.tick()
    events = dev.sample_events(DAY1_0900)
    assert [(ev["ev"], ev.get("reason")) for ev in events] == [("suppressed", "quiet_zone")]
    assert [ev["ev"] for ev in dev.sample_events(DAY1_1500)] == ["unobserved"]


def test_watermark_advances(mkdevice, clock):
    dev = mkdevice("laptop-aaaa0006")
    dev.boot(base_config([fixed_stream()]))
    clock.advance(24 * 3600)
    dev.engine.tick()
    wm = dev.db.kv_get("sync_meta", "last_materialized_at")
    # Held back exactly one expiry (60 min) behind now.
    assert wm == fmt_utc(T0 + 24 * 3600 - 3600)
