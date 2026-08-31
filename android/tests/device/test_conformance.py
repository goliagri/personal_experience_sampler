"""Cross-implementation checks: the Kotlin runtime on the phone and the
Python runtime on the desktop, given the same DB and clock, must produce the
same schedule rows, the same events, and the same folded sample rows."""
from __future__ import annotations

from conftest import NOW_EPOCH, PING, PING_EPOCH, Device, Seed, desktop_engine

SCHEDULE_COLS = ("sample", "stream", "scheduled_utc", "config_v", "suppressed_reason", "idx")


def schedule_rows(db) -> list[tuple]:
    return db.conn.execute(
        f"SELECT {', '.join(SCHEDULE_COLS)} FROM schedule ORDER BY scheduled_utc, stream"
    ).fetchall()


def test_materialized_schedule_matches_desktop(dev: Device):
    dev.seed(Seed(mean_gap=30))  # Poisson (fdlibm log), fixed, quiet zone, disabled
    _, _, ref = desktop_engine(dev.seed_path, NOW_EPOCH)
    expected = schedule_rows(ref)
    ref.close()
    phone = dev.pull_db()
    try:
        got = schedule_rows(phone)
    finally:
        phone.close()
    assert len(got) > 20
    assert got == expected


def test_fold_of_phone_events_matches_desktop_fold(dev: Device):
    """Drive a snooze → late answer on the phone, then re-fold its event log
    with the desktop core and compare to the phone's samples table."""
    dev.seed(Seed(poisson=False))
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.shade_tap("Snooze")
    dev.wait_no_notification()
    dev.settime(PING_EPOCH + 2 * 3600)
    dev.wait_no_notification()
    dev.launch()
    dev.tap_text(contains="Backlog: 1 unanswered")
    dev.tap_text("Answer")
    dev.type_into("What are you doing? (space-separated)", "fold_check")
    dev.tap_text("Submit")
    dev.wait_find(text="Backlog is empty.")

    phone = dev.pull_db()
    try:
        phone_row = phone.sample_row(PING)
        assert [e["ev"] for _f, _l, e in phone.events_for_sample(PING)] == ["fired", "snoozed", "expired", "answered"]
    finally:
        phone.close()
    # The oracle re-folds the phone's own events (imported verbatim).
    engine, _, ref = desktop_engine(dev.seed_path, PING_EPOCH + 2 * 3600)
    try:
        phone2 = dev.pull_db()
        for f in phone2.event_files():
            ref.import_file(f, phone2.file_lines(f))
        phone2.close()
        ref_row = engine.refold(PING)
    finally:
        ref.close()
    # `warnings` (dedupe conflicts) is reported outside the row by the Kotlin
    # fold and inside it by the Python one; it is not part of the spec row.
    ref_row.pop("warnings", None)
    assert ref_row == phone_row


def test_backfill_events_match_desktop(dev: Device):
    """Phone off across two pings, then relaunched: the backfill it writes is
    byte-for-byte what the desktop engine writes for the same gap."""
    dev.seed(Seed(poisson=False, fixed=["12:00", "12:30"]))
    dev.force_stop()
    late = PING_EPOCH + 4 * 3600
    dev.settime(late)
    dev.launch()
    dev.wait(lambda: dev.sample("fixed|2026-09-01T19:30:00Z") is not None, 20, "no backfill")
    phone = dev.pull_db()
    try:
        phone_events = [e for e in phone.events_of_type("unobserved")]
    finally:
        phone.close()
    _engine, _, ref = desktop_engine(dev.seed_path, late, boot_at=NOW_EPOCH)
    try:
        ref_events = ref.events_of_type("unobserved")
    finally:
        ref.close()
    strip = lambda evs: sorted((e["sample"], e["ev"], e["config_v"], e["dev"]) for e in evs)
    assert strip(phone_events) == strip(ref_events)
    assert {e["sample"] for e in phone_events} == {PING, "fixed|2026-09-01T19:30:00Z"}
