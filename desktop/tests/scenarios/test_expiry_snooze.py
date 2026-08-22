"""Expiry, snooze, and backlog scenarios (test plan §2)."""

from __future__ import annotations

from pes.core.timeutil import parse_utc
from tests.scenarios.conftest import base_config, fixed_stream

SAMPLE = "st|2026-08-24T16:00:00Z"  # 09:00 local


def ticked_to(dev, clock, iso: str) -> None:
    """Advance in sub-jump steps so live firing (not backfill) handles pings."""
    target = parse_utc(iso)
    while clock.now() < target:
        clock.advance(min(240, target - clock.now()))
        dev.engine.tick()


def test_fire_expire_backlog(mkdevice, clock):
    dev = mkdevice("laptop-bbbb0001")
    dev.boot(base_config([fixed_stream()]))
    dev.engine.tick()

    ticked_to(dev, clock, "2026-08-24T16:00:00Z")
    events = dev.sample_events(SAMPLE)
    assert [ev["ev"] for ev in events] == ["fired"]
    assert not events[0]["test"]
    assert SAMPLE in dev.notifier.active()
    assert [r["sample"] for r in dev.engine.active_samples()] == [SAMPLE]
    assert dev.engine.backlog() == []

    # At the expiry instant: expired logged, notification cancelled,
    # sample moves to backlog.
    ticked_to(dev, clock, "2026-08-24T17:00:00Z")
    row = dev.db.sample_row(SAMPLE)
    assert row["status"] == "expired"
    assert SAMPLE not in dev.notifier.active()
    assert dev.engine.active_samples() == []
    assert [r["sample"] for r in dev.engine.backlog()] == [SAMPLE]

    # Backlog excludes samples older than backlog_hours (12 h default).
    ticked_to(dev, clock, "2026-08-25T05:00:00Z")
    assert SAMPLE not in {r["sample"] for r in dev.engine.backlog()}


def test_snooze_refires_and_counts(mkdevice, clock):
    dev = mkdevice("laptop-bbbb0002")
    dev.boot(base_config([fixed_stream()]))
    ticked_to(dev, clock, "2026-08-24T16:00:00Z")

    assert dev.engine.snooze(SAMPLE) is None
    assert SAMPLE not in dev.notifier.active()

    # Re-fires 10 minutes later with a second fired event.
    ticked_to(dev, clock, "2026-08-24T16:10:00Z")
    kinds = [ev["ev"] for ev in dev.sample_events(SAMPLE)]
    assert kinds == ["fired", "snoozed", "fired"]
    assert SAMPLE in dev.notifier.active()
    _, title, _ = dev.notifier.shown[-1]
    assert "snoozed x1" in title

    row = dev.db.sample_row(SAMPLE)
    assert row["status"] == "pending" and row["snoozes"] == 1


def test_snooze_refused_near_expiry(mkdevice, clock):
    dev = mkdevice("laptop-bbbb0003")
    dev.boot(base_config([fixed_stream()]))
    ticked_to(dev, clock, "2026-08-24T16:52:00Z")  # 8 min left, snooze is 10

    assert dev.engine.snooze(SAMPLE) == "near_expiry"
    assert [ev["ev"] for ev in dev.sample_events(SAMPLE)] == ["fired"]


def test_snooze_refused_at_max(mkdevice, clock):
    dev = mkdevice("laptop-bbbb0004")
    config = base_config([fixed_stream(overrides={"expiry_minutes": 120})])
    dev.boot(config)
    ticked_to(dev, clock, "2026-08-24T16:00:00Z")

    for _ in range(3):  # max_snoozes default 3
        assert dev.engine.snooze(SAMPLE) is None
        clock.advance(60)
        dev.engine.tick()
    assert dev.engine.snooze(SAMPLE) == "max_snoozes"


def test_answer_measures_latency_from_original_time(mkdevice, clock):
    dev = mkdevice("laptop-bbbb0005")
    dev.boot(base_config([fixed_stream()]))
    ticked_to(dev, clock, "2026-08-24T16:00:00Z")
    assert dev.engine.snooze(SAMPLE) is None
    ticked_to(dev, clock, "2026-08-24T16:12:00Z")

    row = dev.engine.answer(SAMPLE, {"tags": ["work.writing"], "note": "hi"})
    assert row["status"] == "answered"
    assert row["latency_s"] == 12 * 60  # from the original scheduled time
    assert row["late"] is False
    assert row["snoozes"] == 1
    assert SAMPLE not in dev.notifier.active()
    # Tag vocabulary picked up for autocomplete.
    assert dev.db.suggest_tags("s1.tags", "work") == ["work.writing"]


def test_late_answer_from_backlog(mkdevice, clock):
    dev = mkdevice("laptop-bbbb0006")
    dev.boot(base_config([fixed_stream()]))
    ticked_to(dev, clock, "2026-08-24T17:30:00Z")  # fired at 16:00, expired 17:00

    assert dev.db.sample_row(SAMPLE)["status"] == "expired"
    row = dev.engine.answer(SAMPLE, {"tags": ["late"]})
    assert row["status"] == "answered"  # answered outranks expired
    assert row["late"] is True
    assert row["latency_s"] == 90 * 60
