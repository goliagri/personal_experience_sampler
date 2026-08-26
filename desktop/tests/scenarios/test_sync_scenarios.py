"""Sync and merge scenarios (test plan §2, "Sync and merge")."""

from __future__ import annotations

from pes.core.timeutil import parse_utc

from tests.scenarios.conftest import base_config, fixed_stream

SAMPLE = "st|2026-08-24T16:00:00Z"


def ticked_to(devices, clock, iso: str) -> None:
    target = parse_utc(iso)
    while clock.now() < target:
        clock.advance(min(240, target - clock.now()))
        for dev in devices:
            dev.engine.tick()


def test_snooze_on_phone_answer_on_laptop_merges(mkdevice, clock):
    phone = mkdevice("phone-cccc0001")
    laptop = mkdevice("laptop-cccc0002")
    phone.boot(base_config([fixed_stream()]))
    laptop.boot(base_config([fixed_stream()]))

    ticked_to([phone, laptop], clock, "2026-08-24T16:00:00Z")
    assert SAMPLE in phone.notifier.active()
    assert phone.engine.snooze(SAMPLE) is None

    phone.syncer.sync()
    laptop.syncer.sync()  # imports phone's fired + snoozed
    row = laptop.db.sample_row(SAMPLE)
    assert row["status"] == "pending" and row["snoozes"] == 1

    laptop.engine.answer(SAMPLE, {"tags": ["merged"]})
    laptop.syncer.sync()
    phone.syncer.sync()  # imports the answer; cancels the local notification

    for dev in (phone, laptop):
        row = dev.db.sample_row(SAMPLE)
        assert row["status"] == "answered"
        assert row["snoozes"] == 1
        assert row["answered_on"] == "laptop-cccc0002"
        assert sorted(row["fired_on"]) == ["laptop-cccc0002", "phone-cccc0001"]
        assert row["latency_s"] == 0  # clock never advanced past 16:00
    assert SAMPLE not in phone.notifier.active()


def test_answered_never_downgraded(mkdevice, clock):
    phone = mkdevice("phone-cccc0003")
    laptop = mkdevice("laptop-cccc0004")
    phone.boot(base_config([fixed_stream()]))
    laptop.boot(base_config([fixed_stream()]))

    ticked_to([phone, laptop], clock, "2026-08-24T16:05:00Z")
    laptop.engine.answer(SAMPLE, {"tags": ["done"]})
    laptop.syncer.sync()

    # Phone, offline, expires the sample locally; then skips it for good
    # measure; then finally syncs.
    ticked_to([phone], clock, "2026-08-24T17:05:00Z")
    assert phone.db.sample_row(SAMPLE)["status"] == "expired"
    phone.syncer.sync()
    assert phone.db.sample_row(SAMPLE)["status"] == "answered"

    laptop.syncer.sync()  # imports phone's expired; answer still wins
    assert laptop.db.sample_row(SAMPLE)["status"] == "answered"


def test_month_upload_only_when_unsynced_and_byte_identical(mkdevice, clock, cloud):
    dev = mkdevice("laptop-cccc0005")
    dev.boot(base_config([fixed_stream()]))
    ticked_to([dev], clock, "2026-08-24T16:00:00Z")

    dev.syncer.sync()
    path = "events/laptop-cccc0005/2026-08.jsonl"
    first = cloud.get(path)
    assert first is not None and first.endswith(b"\n")

    puts: list[str] = []
    original_put = cloud.put
    cloud.put = lambda p, d: (puts.append(p), original_put(p, d))[1]
    dev.syncer.sync()  # nothing new -> month not re-uploaded
    assert not [p for p in puts if p.startswith("events/")]
    assert cloud.get(path) == first

    dev.engine.snooze(SAMPLE)
    puts.clear()
    dev.syncer.sync()
    assert [p for p in puts if p.startswith("events/")] == [path]
    assert cloud.get(path).startswith(first)  # append-only growth


def test_sync_is_noop_against_unchanged_cloud(mkdevice, clock, cloud):
    dev = mkdevice("laptop-cccc0006")
    dev.boot(base_config([fixed_stream()]))
    ticked_to([dev], clock, "2026-08-24T16:00:00Z")
    dev.engine.answer(SAMPLE, {"tags": ["x"]})

    dev.syncer.sync()
    snapshot = {p: cloud.get(p) for p in cloud.list("")}
    events_before = len(dev.db.all_sample_events())

    result = dev.syncer.sync()
    assert {p: cloud.get(p) for p in cloud.list("")} == snapshot
    assert len(dev.db.all_sample_events()) == events_before
    assert result["imported"] == [] and result["exported"] == []
    assert result["backfilled"] == 0


def test_export_regenerated_only_on_change(mkdevice, clock, cloud):
    dev = mkdevice("laptop-cccc0007")
    dev.boot(base_config([fixed_stream()]))
    ticked_to([dev], clock, "2026-08-24T16:00:00Z")
    dev.engine.answer(SAMPLE, {"tags": ["one"], "note": "first"})

    result = dev.syncer.sync()
    assert result["exported"] == ["st"]
    csv1 = cloud.get("exports/st.csv")
    assert b"one" in csv1
    assert cloud.get("exports/st.columns.json") is not None

    assert dev.syncer.sync()["exported"] == []  # unchanged rows -> no regen

    ticked_to([dev], clock, "2026-08-24T22:00:00Z")  # second ping fires
    dev.engine.answer("st|2026-08-24T22:00:00Z", {"tags": ["two"]})
    result = dev.syncer.sync()
    assert result["exported"] == ["st"]
    csv2 = cloud.get("exports/st.csv")
    assert b"one" in csv2 and b"two" in csv2


def test_devices_doc_written_with_primary_role(mkdevice, clock, cloud):
    laptop = mkdevice("laptop-cccc0008")
    phone = mkdevice("phone-cccc0009")
    laptop.boot(base_config([fixed_stream()]))
    phone.boot(base_config([fixed_stream()]))

    laptop.syncer.sync()
    phone.syncer.sync()
    import json

    laptop_doc = json.loads(cloud.get("devices/laptop-cccc0008.json"))
    phone_doc = json.loads(cloud.get("devices/phone-cccc0009.json"))
    assert laptop_doc["role"] == "primary"  # first claimant keeps it
    assert phone_doc["role"] == ""
