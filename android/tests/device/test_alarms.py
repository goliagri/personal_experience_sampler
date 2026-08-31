"""Exact-alarm scheduling on the real platform (spec §11, TEST_PLAN §4
Android items 1 and 3): one RTC_WAKEUP alarm at the engine's next wake,
firing through Doze, app-killed, reboot and clock jumps."""
from __future__ import annotations

import pytest
from conftest import NOW_EPOCH, PING, PING_EPOCH, Device, Seed, desktop_engine, iso

FIXED_ONLY = Seed(poisson=False)


def test_exact_alarm_armed_at_next_ping(dev: Device):
    dev.seed(FIXED_ONLY)
    when, window = dev.next_alarm()
    assert when == PING_EPOCH, f"alarm at {iso(when)}, expected {PING}"
    assert window == 0, "alarm is not exact"


def test_alarm_matches_desktop_engine_next_wake(dev: Device):
    """The phone's single alarm must sit exactly where the reference engine
    says the next wake is (cross-implementation check of nextWake)."""
    dev.seed(Seed())  # Poisson + fixed: the interesting case
    engine, _, db = desktop_engine(dev.seed_path, NOW_EPOCH)
    expected = engine.tick()
    db.close()
    when, window = dev.next_alarm()
    assert when == expected, f"phone {iso(when)} vs desktop {iso(expected)}"
    assert window == 0


def test_ping_fires_on_schedule(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    n = dev.wait_notification("Fixed times")
    assert n.text == "Ping at 12:00 - answer now"
    evs = dev.events(PING)
    assert [e["ev"] for e in evs] == ["fired"]
    assert evs[0]["scheduled"] == "2026-09-01T19:00:00Z"
    assert evs[0]["test"] is False
    # Next alarm is the expiry of this ping (nothing else within 60 min).
    when, _ = dev.next_alarm()
    assert when == PING_EPOCH + 60 * 60


def test_fires_in_doze_with_app_killed(dev: Device):
    """§4: 'ping fires within seconds of schedule with app swiped away,
    screen off, in Doze'."""
    dev.seed(FIXED_ONLY)
    dev.kill()
    dev.doze()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    assert dev.ev_types(PING) == ["fired"]


def test_expiry_cancels_notification_and_moves_to_backlog(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.settime(PING_EPOCH + 60 * 60 + 3)
    dev.wait_no_notification()
    assert dev.ev_types(PING) == ["fired", "expired"]
    row = dev.sample(PING)
    assert row["status"] == "expired"
    dev.launch()
    dev.wait_find(contains="Backlog: 1 unanswered")
    assert not dev.ui_has(text="Answer"), "expired sample must not be on the Home active card"


def test_time_set_backwards_does_not_fire(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.settime(NOW_EPOCH - 30 * 60)
    assert dev.notifications() == []
    when, _ = dev.next_alarm()
    assert when == PING_EPOCH
    assert dev.sample(PING) is None


def test_disabled_stream_generates_nothing(dev: Device):
    dev.seed(Seed())
    db = dev.pull_db()
    try:
        rows = db.conn.execute("SELECT sample FROM schedule WHERE stream = 'off'").fetchall()
    finally:
        db.close()
    assert rows == []
    assert not [r for r in dev.samples() if r["stream"] == "off"]


def test_tick_after_long_gap_backfills_unobserved(dev: Device):
    """Alarm fires 3 h late (e.g. the device was off): the ping is past its
    window, so it is classified `unobserved`, never fired stale (§6.4)."""
    dev.seed(FIXED_ONLY)
    dev.force_stop()
    dev.settime(PING_EPOCH + 3 * 3600)
    dev.launch()
    dev.wait(lambda: dev.sample(PING) is not None, 15, "no classification")
    assert dev.ev_types(PING) == ["unobserved"]
    assert dev.notifications() == []
    dev.wait_find(contains="Backlog: 1 unanswered")


@pytest.mark.slow
def test_reboot_within_window_fires_late(dev: Device):
    """§4 BOOT_COMPLETED: reboot across a scheduled ping. Within the active
    window the ping fires late (fired.t after scheduled), and the alarm is
    re-armed without the app being opened."""
    dev.seed(FIXED_ONLY)
    dev.reboot()
    dev.settime(PING_EPOCH + 20 * 60)
    n = dev.wait_notification("Fixed times", timeout=40)
    assert n.text == "Ping at 12:00 - answer now"
    evs = dev.events(PING)
    assert [e["ev"] for e in evs] == ["fired"]
    assert evs[0]["t"] >= iso(PING_EPOCH + 20 * 60)
    when, window = dev.next_alarm()
    assert when == PING_EPOCH + 60 * 60 and window == 0


@pytest.mark.slow
def test_reboot_past_window_classifies_unobserved(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.reboot()
    dev.settime(PING_EPOCH + 2 * 3600)
    dev.wait(lambda: dev.sample(PING) is not None, 40, "boot receiver did not backfill")
    assert dev.ev_types(PING) == ["unobserved"]
    assert dev.notifications() == []
    when, _ = dev.next_alarm()
    assert when > PING_EPOCH + 2 * 3600
