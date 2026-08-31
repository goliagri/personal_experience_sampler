"""Notification content and actions on the real shade (spec §6.5, §10.3;
TEST_PLAN §4 Android item 4)."""
from __future__ import annotations

from conftest import PING, PING_EPOCH, Device, Seed, iso, parse_utc

FIXED_ONLY = Seed(poisson=False)


def fire(dev: Device, seed: Seed = FIXED_ONLY):
    dev.seed(seed)
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    return dev.wait_notification("Fixed times")


def test_notification_content_and_actions(dev: Device):
    n = fire(dev)
    assert n.title == "Fixed times"
    assert n.text == "Ping at 12:00 - answer now"
    dev.open_shade()
    try:
        # Android shows at most three actions; the inline reply must be one of them.
        assert dev.shade_actions() == ["Reply tags", "Snooze", "Skip"]
    finally:
        dev.close_shade()


def test_skip_from_shade(dev: Device):
    fire(dev)
    dev.shade_tap("Skip")
    dev.wait_no_notification()
    assert dev.ev_types(PING) == ["fired", "skipped"]
    assert dev.sample(PING)["status"] == "skipped"
    dev.launch()
    dev.wait_find(text="No active ping.")


def test_snooze_from_shade_then_refires(dev: Device):
    fire(dev)
    dev.shade_tap("Snooze")
    dev.wait_no_notification()
    evs = dev.events(PING)
    assert [e["ev"] for e in evs] == ["fired", "snoozed"]
    assert evs[1]["n"] == 1
    until = evs[1]["until"]
    # now + 10 min, where "now" is the device clock at the tap (a few real
    # seconds after the pinned fire time).
    assert PING_EPOCH + 3 + 600 <= parse_utc(until) <= PING_EPOCH + 3 + 600 + 90
    when, window = dev.next_alarm()
    assert iso(when) == until and window == 0
    dev.settime(parse_utc(until) + 2)
    n = dev.wait_notification("snoozed")
    assert n.title == "Fixed times (snoozed x1)"
    assert n.text == "Ping at 12:00 - answer now", "original time must stay in the body"


def test_snooze_refused_at_max_snoozes(dev: Device):
    fire(dev)
    for n in (1, 2, 3):
        dev.shade_tap("Snooze")
        dev.wait_no_notification()
        snoozes = [e for e in dev.events(PING) if e["ev"] == "snoozed"]
        assert len(snoozes) == n and snoozes[-1]["n"] == n
        dev.settime(parse_utc(snoozes[-1]["until"]) + 2)
        dev.wait_notification(f"snoozed x{n}")
    dev.shade_tap("Snooze")  # 4th: refused (max_snoozes 3)
    assert dev.ev_types(PING).count("snoozed") == 3
    assert dev.notifications(), "refused snooze must leave the notification up"
    assert dev.sample(PING)["status"] == "pending"


def test_snooze_refused_near_expiry(dev: Device):
    fire(dev)
    dev.settime(PING_EPOCH + 55 * 60)  # 5 min left < snooze_minutes 10
    dev.shade_tap("Snooze")
    assert dev.ev_types(PING) == ["fired"]
    assert dev.notifications()


def test_inline_reply_logs_partial_answer(dev: Device):
    fire(dev)
    dev.shade_reply("coding focused")
    dev.wait(lambda: "answered" in dev.ev_types(PING), 10, "no answered event")
    evs = dev.events(PING)
    ans = next(e for e in evs if e["ev"] == "answered")
    assert ans["partial"] is True
    assert ans["answers"] == {"tags": ["coding", "focused"]}
    assert ans["survey"] == {"id": "dev", "version": 1}
    dev.wait_no_notification()
    row = dev.sample(PING)
    assert row["status"] == "answered" and row["partial"] is True and row["late"] is False


def test_inline_reply_with_no_valid_tags_is_ignored(dev: Device):
    fire(dev)
    dev.shade_reply("!!! @@@")
    dev.wait(lambda: True, 2, "")
    assert dev.ev_types(PING) == ["fired"], "an empty reply must not create an answered event"
    assert dev.sample(PING)["status"] == "pending"


def test_inline_reply_not_offered_when_another_field_required(dev: Device):
    fire(dev, Seed(poisson=False, required_mood=True))
    dev.open_shade()
    try:
        assert dev.shade_actions() == ["Open", "Snooze", "Skip"]
    finally:
        dev.close_shade()


def test_two_streams_firing_together_are_distinguishable(dev: Device):
    dev.seed(Seed(poisson=False, fixed2=["12:00"]))
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.wait_notification("Second stream")
    ns = dev.notifications()
    assert len(ns) == 2 and len({n.id for n in ns}) == 2
    assert {n.text for n in ns} == {"Ping at 12:00 - answer now"}
    assert dev.ev_types(PING) == ["fired"]
    assert dev.ev_types("second|2026-09-01T19:00:00Z") == ["fired"]
