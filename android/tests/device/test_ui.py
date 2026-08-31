"""Screens driven through the accessibility tree (spec §10.2–10.4,
PROJECT_PREFERENCES: fast answer flow, old samples unmistakable)."""
from __future__ import annotations

from conftest import NOW, PING, PING_EPOCH, Device, Seed, parse_utc

FIXED_ONLY = Seed(poisson=False)


def fire_and_open(dev: Device, seed: Seed = FIXED_ONLY):
    dev.seed(seed)
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.launch()
    dev.wait_find(contains="Fixed times — ping at 12:00")


def test_home_shows_active_card_after_resume(dev: Device):
    """Home was open when the ping fired; returning to it must show the
    card (regression: stale 'No active ping')."""
    dev.seed(FIXED_ONLY)
    dev.wait_find(text="No active ping.")
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.launch()
    dev.wait_find(contains="Fixed times — ping at 12:00")
    assert dev.ui_has(text="Answer") and dev.ui_has(text="Snooze") and dev.ui_has(text="Skip")


def test_answer_screen_layout(dev: Device):
    fire_and_open(dev)
    dev.tap_text("Answer")
    dev.wait_find(text="Scheduled 2026-09-01 12:00", scroll=False)
    texts = dev.page_texts()
    assert "Fixed times" in texts
    assert "Scheduled 2026-09-01 12:00" in texts
    assert not any(t.startswith("LATE") for t in texts)
    # Secondary actions at top, fields in schema order, submit with the time.
    order = [t for t in texts if t in ("Snooze", "Skip", "What are you doing? (space-separated)", "Mood (1-7)", "Where", "With")]
    assert order == ["Snooze", "Skip", "What are you doing? (space-separated)", "Mood (1-7)", "Where", "With"]
    assert "for ping at 2026-09-01 12:00" in texts or dev.ui_has(contains="for ping at")


def test_answer_flow_full_survey(dev: Device):
    fire_and_open(dev)
    dev.tap_text("Answer")
    dev.type_into("What are you doing? (space-separated)", "coding deep_work")
    dev.type_into("Mood (1-7)", "5")
    dev.tap_text("home")
    dev.tap_text("alone")
    dev.tap_text("friends")
    dev.tap_text("Submit")
    dev.wait_find(text="No active ping.")  # returns to Home instantly
    evs = dev.events(PING)
    assert [e["ev"] for e in evs] == ["fired", "answered"]
    ans = evs[1]
    assert ans["partial"] is False
    assert ans["answers"]["tags"] == ["coding", "deep_work"]
    assert ans["answers"]["mood"] == 5
    assert ans["answers"]["where"] == "home"
    assert sorted(ans["answers"]["with"]) == ["alone", "friends"]
    assert "note" not in ans["answers"] or ans["answers"]["note"] in ("", None)
    row = dev.sample(PING)
    assert row["status"] == "answered" and row["late"] is False
    assert 3 <= row["latency_s"] <= 180  # device clock keeps running during the flow
    dev.wait_find(contains="Fixed times   1 / 1 / 0")
    assert dev.notifications() == []


def test_required_field_blocks_submit(dev: Device):
    fire_and_open(dev, Seed(poisson=False, required_mood=True))
    dev.tap_text("Answer")
    dev.tap_text("Submit")
    dev.wait_find(text="Required")
    assert dev.ev_types(PING) == ["fired"]
    dev.type_into("Mood (1-7)", "3")
    dev.tap_text("Submit")
    dev.wait_find(text="No active ping.")
    assert dev.ev_types(PING) == ["fired", "answered"]


def test_skip_and_snooze_from_home(dev: Device):
    fire_and_open(dev)
    dev.tap_text("Snooze")
    dev.wait(lambda: "snoozed" in dev.ev_types(PING), 10, "no snoozed event")
    dev.wait_no_notification()
    # A snoozed sample is still pending inside its window, so Home keeps its
    # card (you may answer early); the re-fire comes from the alarm.
    dev.wait_find(contains="Fixed times — ping at 12:00")
    until = next(e for e in dev.events(PING) if e["ev"] == "snoozed")["until"]
    dev.settime(parse_utc(until) + 2)
    dev.wait_notification("snoozed x1")
    dev.launch()
    dev.tap_text("Skip")
    dev.wait_find(text="No active ping.")
    # A re-fire is a `fired` event too (fired.t = re-fire time; latency is
    # still measured from the original scheduled time by the fold).
    assert dev.ev_types(PING) == ["fired", "snoozed", "fired", "skipped"]


def test_late_answer_from_backlog_is_unmistakable(dev: Device):
    """§10.4: expired samples reachable only via Backlog/History, with the
    LATE banner and the original time; the fold marks the answer late."""
    dev.seed(FIXED_ONLY)
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.settime(PING_EPOCH + 3 * 3600)
    dev.wait_no_notification()
    dev.launch()
    assert not dev.ui_has(text="Answer")
    dev.tap_text(contains="Backlog: 1 unanswered")
    dev.wait_find(text="These pings have expired. Answers will be marked late.")
    assert dev.ui_has(text="2026-09-01 12:00")
    dev.tap_text("Answer")
    banner = dev.wait_find(contains="LATE — originally 2026-09-01 12:00", scroll=False)
    assert "3 h" in banner.text
    texts = dev.page_texts()
    assert "Snooze" not in texts and "Skip" not in texts
    assert "for ping at 2026-09-01 12:00" in texts, "scheduled time repeated next to Submit (§10.4)"
    dev.type_into("What are you doing? (space-separated)", "late_tag")
    dev.tap_text("Submit")
    dev.wait_find(text="Backlog is empty.")  # answering from Backlog returns to Backlog
    dev.go_home()
    dev.wait_find(text="No active ping.")
    row = dev.sample(PING)
    assert row["status"] == "answered" and row["late"] is True and row["observed"] is True
    assert 3 * 3600 <= row["latency_s"] <= 3 * 3600 + 300


def test_quiet_mode_suppresses_and_is_logged(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.tap_text("Until turned off")
    dev.wait_find(contains="Quiet: on until turned off")
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait(lambda: dev.sample(PING) is not None, 15, "no suppression logged")
    assert dev.notifications() == []
    evs = dev.events(PING)
    assert [e["ev"] for e in evs] == ["suppressed"] and evs[0]["reason"] == "quiet_mode"
    dev.launch()
    dev.tap_text("Turn off")
    dev.wait_find(text="Quiet: off")


def test_fire_test_ping(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.tap_text("Streams")
    dev.wait_find(text="Fire test ping now")
    dev.tap_text("Fire test ping now")
    n = dev.wait_notification("Fixed times")
    assert n.text.startswith("Ping at 11:55")
    tests = [r for r in dev.samples() if r["sample"].startswith("fixed|2026-09-01T18:55")]
    assert len(tests) == 1
    fired = next(e for e in dev.events(tests[0]["sample"]) if e["ev"] == "fired")
    assert fired["test"] is True
    assert "disabled" in " ".join(dev.texts()).lower()  # disabled stream listed, no button


def test_history_lists_and_filters(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.home_key()
    dev.settime(PING_EPOCH + 3)
    dev.wait_notification("Fixed times")
    dev.shade_tap("Skip")
    dev.wait_no_notification()
    dev.launch()
    dev.tap_text("History")
    dev.wait_find(text="skipped")
    dev.tap_text("answered")  # filter chip
    dev.wait_find(text="Nothing yet.")
    dev.tap_text("skipped")
    dev.wait_find(contains="12:00")
    # Skipped a minute after it fired: still inside its window, so answering it
    # is not late and must not be dressed up as such (Tier 3 charter C2 F1).
    assert dev.ui_has(text="Answer"), dev.texts()
    assert not dev.ui_has(text="Answer late")

    # Past expiry the same row becomes a genuine late answer.
    dev.settime(PING_EPOCH + 2 * 3600)
    dev.launch()
    dev.tap_text("History")
    dev.wait_find(text="Answer late")
    dev.tap_text("Answer late")
    dev.wait_find(contains="LATE — originally")


def test_permissions_checklist_tracks_grants(dev: Device):
    dev.seed(FIXED_ONLY)
    dev.tap_text("Settings")
    dev.wait_find(text="✓ Notifications")
    assert dev.ui_has(text="✓ Exact alarms")
    dev.shell("pm revoke pes.app android.permission.POST_NOTIFICATIONS")  # kills the app
    dev.shell("cmd appops set pes.app SCHEDULE_EXACT_ALARM deny")
    dev.launch()
    dev.tap_text("Settings")
    dev.wait_find(text="✗ Notifications")
    # The revoked row also names the consequence (Tier 3 charter C5 F5).
    assert dev.ui_has(contains="✗ Exact alarms — off: pings arrive minutes late")
    # Degraded scheduling: the alarm is re-armed inexact rather than crashing.
    dev.force_stop()
    dev.launch()
    _, window = dev.next_alarm()
    assert window > 0
    dev.shell("pm grant pes.app android.permission.POST_NOTIFICATIONS")
    dev.shell("cmd appops set pes.app SCHEDULE_EXACT_ALARM allow")
    dev.force_stop()
    dev.launch()
    _, window = dev.next_alarm()
    assert window == 0
    dev.tap_text("Settings")
    dev.wait_find(text="✓ Exact alarms")


def test_home_refreshes_while_it_is_on_screen(dev):
    """Tier 3 charter C4 F4: a ping arriving with Home in the foreground used
    to leave "No active ping." on screen until the activity was resumed."""
    d = dev
    d.seed(now=NOW)
    d.launch()
    d.wait_find(text="Experience Sampler", scroll=False)
    assert d.ui_has(contains="No active ping")

    d.settime(PING_EPOCH + 3)  # the 12:00 ping fires; nobody touches the app
    d.wait(lambda: d.ui_has(contains="Fixed times — ping at"), 30,
           "Home to pick up the new ping without being resumed")
    assert d.ui_has(text="Answer")
