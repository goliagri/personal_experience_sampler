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


def test_open_window_still_fires_after_restart(mkdevice, clock):
    """Like the short-sleep case, but via the *start* path (reboot, TIME_SET,
    app relaunch): re-materialization must not drop a sample whose active
    window is still open, or it silently becomes `unobserved` instead of
    firing late."""
    dev = mkdevice("laptop-aaaa0003")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()

    clock.set(parse_utc("2026-08-24T16:30:00Z"))  # relaunched 30 min late; expiry 60
    dev.engine.start()
    dev.engine.tick()
    events = dev.sample_events(DAY1_0900)
    assert [ev["ev"] for ev in events] == ["fired"]
    assert events[0]["t"] == "2026-08-24T16:30:00Z"
    assert DAY1_0900 in dev.notifier.active()

    # Past the window, the same path classifies it instead of firing.
    clock.set(T0)
    dev2 = mkdevice("laptop-aaaa0004")
    dev2.boot(base_config([fixed_stream()]))
    dev2.engine.tick()
    clock.set(parse_utc("2026-08-24T17:30:00Z"))
    dev2.engine.start()
    dev2.engine.tick()
    assert [ev["ev"] for ev in dev2.sample_events(DAY1_0900)] == ["unobserved"]


def test_retroactive_expiry_cancels_notification(mkdevice, clock):
    """Fired, then the clock jumps past expiry and the app *restarts*: the
    expiry is logged by backfill, and the notification must come down."""
    dev = mkdevice("laptop-aaaa0005")
    dev.boot(base_config([fixed_stream()]))
    clock.set(parse_utc("2026-08-24T16:00:00Z"))
    dev.engine.tick()
    assert DAY1_0900 in dev.notifier.active()

    clock.set(parse_utc("2026-08-24T18:00:00Z"))
    dev.engine.start()
    assert [ev["ev"] for ev in dev.sample_events(DAY1_0900)] == ["fired", "expired"]
    assert DAY1_0900 not in dev.notifier.active()


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


def test_backward_jump_does_not_refire_an_unobserved_ping(mkdevice, clock):
    """Tier 3 charter C3 F1: `unobserved` is terminal. A clock that goes
    backwards re-opens the active window, and the sample used to fire again —
    leaving one generated ping recorded as both `unobserved` and
    `observed: true`, plus a permanent alarm for a ping already accounted for."""
    dev = mkdevice("laptop-aaaa0005")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()

    clock.advance(24 * 3600)  # both of Monday's pings pass with nothing running
    dev.engine.tick()
    assert [ev["ev"] for ev in dev.sample_events(DAY1_0900)] == ["unobserved"]

    # Clock goes backwards to inside the 09:00 window, then forward a little.
    clock.set(parse_utc(DAY1_0900.split("|", 1)[1]) + 60)
    dev.engine.tick()
    clock.advance(60)
    dev.engine.tick()

    assert [ev["ev"] for ev in dev.sample_events(DAY1_0900)] == ["unobserved"]
    row = dev.db.sample_row(DAY1_0900)
    assert row["status"] == "unobserved" and row["observed"] is False
    assert DAY1_0900 not in dev.notifier.active()
    # ...and it is no longer a candidate for the next exact alarm.
    assert dev.engine._next_wake(dev.engine.clock.now()) != parse_utc(
        DAY1_0900.split("|", 1)[1]
    )


def test_stale_clock_keeps_the_existing_horizon(mkdevice, clock):
    """Tier 3 charter C3 F3: a reboot can restore an RTC days in the past
    before network time lands. Rebuilding the horizon around that instant would
    leave the device with an empty schedule and no armed alarm; keep what we
    have until the clock is corrected."""
    dev = mkdevice("laptop-aaaa0006")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()
    planned = [r["sample"] for r in dev.db.due_schedule("9999")]
    assert planned, "nothing scheduled to begin with"
    wake_before = dev.engine._next_wake(dev.engine.clock.now())

    clock.set(dev.engine.clock.now() - 3 * 24 * 3600)  # RTC three days behind
    dev.engine.materialize()

    assert [r["sample"] for r in dev.db.due_schedule("9999")] == planned
    assert dev.engine._next_wake(clock.now()) == wake_before

    # Once the clock is corrected the horizon rebuilds normally.
    clock.set(dev.engine.clock.now() + 3 * 24 * 3600)
    dev.engine.materialize()
    assert dev.db.due_schedule("9999")
