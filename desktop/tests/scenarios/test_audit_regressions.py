"""Regressions from the 2026-08 code audit: sync-procedure edge cases that
the design requires but the original scenarios missed."""

from __future__ import annotations

import json

from pes.core.timeutil import parse_utc

from tests.scenarios.conftest import base_config, fixed_stream

SAMPLE = "st|2026-08-24T16:00:00Z"


def ticked_to(devices, clock, iso: str) -> None:
    target = parse_utc(iso)
    while clock.now() < target:
        clock.advance(min(240, target - clock.now()))
        for dev in devices:
            dev.engine.tick()


def test_retroactive_expiry_after_unobserved(mkdevice, clock):
    """[H] fired on one device + unobserved on another folds to unobserved
    (precedence), but §8.4 step 3 must still close it out as expired — the
    decision is made on event types, not the folded status."""
    phone = mkdevice("phone-ee01")
    laptop = mkdevice("laptop-ee02")
    phone.boot(base_config([fixed_stream()]))
    ticked_to([phone], clock, "2026-08-24T16:00:00Z")  # phone fires, then dies
    assert [e["ev"] for e in phone.sample_events(SAMPLE)] == ["fired"]

    clock.set(parse_utc("2026-08-24T18:00:00Z"))
    laptop.boot(base_config([fixed_stream()]))  # laptop was off all along
    laptop.db.kv_set("sync_meta", "last_materialized_at", "2026-08-24T15:00:00Z")
    laptop.engine.backfill_now()
    assert [e["ev"] for e in laptop.sample_events(SAMPLE)] == ["unobserved"]

    phone.syncer.sync()
    laptop.syncer.sync()  # imports the fired; window long past -> expired
    assert laptop.db.sample_row(SAMPLE)["status"] == "expired"


def test_conflict_updates_history_to_winner(mkdevice, clock, cloud):
    """After a same-version conflict, the history file must record the
    winner, not whichever loser uploaded it first."""
    a = mkdevice("laptop-ee03")
    b = mkdevice("phone-ee04")
    a.boot(base_config([fixed_stream()]))
    b.boot(base_config([fixed_stream()]))
    a.syncer.sync()
    b.syncer.sync()
    defaults = a.db.latest_config()["defaults"]
    a.engine.stage_new_config(
        [fixed_stream(times=("10:00",))], defaults, "America/Los_Angeles", "2026-08-25T07:00:00Z"
    )
    clock.advance(60)  # b's edit has the later written_at and wins
    b.engine.stage_new_config(
        [fixed_stream(times=("11:00",))], defaults, "America/Los_Angeles", "2026-08-25T07:00:00Z"
    )
    a.syncer.sync()
    b.syncer.sync()
    a.syncer.sync()
    assert json.loads(cloud.get("config/history/config_v0002.json"))["written_by"] == "phone-ee04"
    assert json.loads(cloud.get("config/current.json"))["written_by"] == "phone-ee04"


def test_lineage_moved_past_local_version_is_archived(mkdevice, clock, cloud):
    """A local unsynced v2 overtaken by another device's v2->v3 chain must be
    archived as a conflict and replaced by the cloud lineage — never silently
    kept as a divergent history (§8.2)."""
    a = mkdevice("laptop-ee05")
    b = mkdevice("phone-ee06")
    a.boot(base_config([fixed_stream()]))
    b.boot(base_config([fixed_stream()]))
    a.syncer.sync()
    b.syncer.sync()
    defaults = a.db.latest_config()["defaults"]
    a.engine.stage_new_config(
        [fixed_stream(times=("10:00",))], defaults, "America/Los_Angeles", "2026-08-25T07:00:00Z"
    )  # a: v2, never uploaded
    b.engine.stage_new_config(
        [fixed_stream(times=("11:00",))], defaults, "America/Los_Angeles", "2026-08-25T07:00:00Z"
    )
    b.syncer.sync()  # cloud: b's v2
    b.engine.stage_new_config(
        [fixed_stream(times=("12:00",))], defaults, "America/Los_Angeles", "2026-08-25T07:00:00Z"
    )
    b.syncer.sync()  # cloud: v3

    result = a.syncer.sync()
    assert result["conflicts"] and result["warnings"]
    assert cloud.list("config/conflicts")
    history = {c["version"]: c["written_by"] for c in a.db.config_history()}
    assert history[2] == "phone-ee06"  # a's divergent v2 replaced, not kept
    assert history[3] == "phone-ee06"


def test_upload_race_keeps_new_events_unsynced(mkdevice, clock, cloud):
    """Events appended while a month upload is in flight must not be marked
    synced by that upload."""
    dev = mkdevice("laptop-ee07")
    dev.boot(base_config([fixed_stream()]))
    ticked_to([dev], clock, "2026-08-24T16:00:00Z")

    original_put = cloud.put

    def racy_put(path, data):
        original_put(path, data)
        if path.startswith("events/"):
            cloud.put = original_put  # only race the first upload
            dev.engine.skip(SAMPLE)

    cloud.put = racy_put
    dev.syncer.sync()
    dev.syncer.sync()  # next trigger must upload the skipped event
    assert b'"skipped"' in cloud.get(f"events/{dev.name}/2026-08.jsonl")


def test_malformed_cloud_lines_do_not_block_sync(mkdevice, clock, cloud):
    """Blank lines are tolerated; a truly malformed file is skipped with a
    warning and other files still import."""
    dev = mkdevice("laptop-ee08")
    dev.boot(base_config([fixed_stream()]))
    good = (
        '{"ev":"skipped","t":"2026-08-24T16:00:00Z","dev":"phone-zz1",'
        '"sample":"st|2026-08-24T16:00:00Z","stream":"st"}'
    )
    cloud.put("events/phone-zz1/2026-08.jsonl", (good + "\n\n").encode())  # trailing blank
    cloud.put("events/phone-zz0/2026-08.jsonl", b"not json at all\n")
    # An Android text answer may contain raw U+2028 (str.splitlines would
    # split on it mid-JSON); the line must survive desktop line-splitting.
    weird_ev = {
        "ev": "skipped", "t": "2026-08-24T22:05:00Z", "dev": "phone-zz2",
        "sample": "st|2026-08-24T22:00:00Z", "stream": "st", "note": "a\u2028b",
    }
    cloud.put(
        "events/phone-zz2/2026-08.jsonl",
        (json.dumps(weird_ev, ensure_ascii=False) + "\n").encode(),
    )

    result = dev.syncer.sync()
    assert any("phone-zz0" in w for w in result["warnings"])
    assert "events/phone-zz1/2026-08.jsonl" in result["imported"]
    assert "events/phone-zz2/2026-08.jsonl" in result["imported"]
    assert dev.db.sample_row(SAMPLE)["status"] == "skipped"
    assert dev.db.sample_row("st|2026-08-24T22:00:00Z")["status"] == "skipped"

    # The bad file is retried (etag not remembered), still without blocking.
    result = dev.syncer.sync()
    assert any("phone-zz0" in w for w in result["warnings"])
