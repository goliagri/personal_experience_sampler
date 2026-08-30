"""Milestone 5 scenarios (test plan §2 / §5.5): restore procedure (§8.6),
weekly/monthly snapshots with retention and the primary-role handoff (§9),
and Drive recovery from a deleted sub-folder."""

from __future__ import annotations

import io
import json
import shutil
import zipfile

from pes.core.timeutil import fmt_utc, parse_utc
from pes.store import Db, DriveStore
from pes.store.drive import FOLDER_MIME
from pes.sync import Syncer, _last_sunday_0300

from tests.scenarios.conftest import base_config, fixed_stream
from tests.scenarios.drive_fake import FakeDrive
from tests.scenarios.test_audit_regressions import ticked_to

SAMPLE = "st|2026-08-24T16:00:00Z"


def _wipe(cloud) -> None:
    shutil.rmtree(cloud.root)
    cloud.root.mkdir()


# -- restore (§8.6) ---------------------------------------------------------


def test_restore_rebuilds_lost_folder_from_cache(mkdevice, clock, cloud):
    """[H] After the cloud folder is deleted, one device's restore brings
    back its own log, the other device's log (from the import cache), the
    config lineage and surveys — and the other device re-adopts cleanly."""
    laptop = mkdevice("laptop-ff01")
    phone = mkdevice("phone-ff02")
    laptop.boot(base_config([fixed_stream()]))
    phone.boot(base_config([fixed_stream()]))
    ticked_to([laptop, phone], clock, "2026-08-24T16:00:00Z")
    phone.engine.answer(SAMPLE, {"tags": ["a"]})
    phone.syncer.sync()
    laptop.syncer.sync()  # laptop now caches the phone's file
    assert laptop.db.sample_row(SAMPLE)["status"] == "answered"

    _wipe(cloud)
    result = laptop.syncer.restore()
    assert set(result["uploaded"]) == {
        "events/laptop-ff01/2026-08.jsonl",
        "events/phone-ff02/2026-08.jsonl",
    }
    assert result["restored"] == []
    assert "config/current.json" in result["docs"]
    assert "surveys/s1/v1.json" in result["docs"]
    assert cloud.get("config/history/config_v0001.json")
    assert cloud.get("manifest.json")
    assert b'"answered"' in cloud.get("events/phone-ff02/2026-08.jsonl")

    # A third device joining sees the full history.
    tablet = mkdevice("tablet-ff03")
    tablet.boot()
    tablet.syncer.sync()
    assert tablet.db.sample_row(SAMPLE)["status"] == "answered"
    # Idempotent: a second restore has nothing to do.
    again = laptop.syncer.restore()
    assert again["uploaded"] == [] and again["docs"] == []


def test_restore_never_overwrites_other_devices_files(mkdevice, clock, cloud):
    """[H] The cloud has an older copy of the phone's log: its missing
    lines go to restored/, the file itself is untouched, and the fold on
    any device still sees them."""
    laptop = mkdevice("laptop-ff04")
    phone = mkdevice("phone-ff05")
    laptop.boot(base_config([fixed_stream()]))
    phone.boot(base_config([fixed_stream()]))
    ticked_to([laptop, phone], clock, "2026-08-24T16:00:00Z")
    phone.syncer.sync()
    older = cloud.get("events/phone-ff05/2026-08.jsonl")
    phone.engine.answer(SAMPLE, {"tags": ["a"]})
    phone.syncer.sync()
    laptop.syncer.sync()

    cloud.put("events/phone-ff05/2026-08.jsonl", older)  # cloud rolled back
    result = laptop.syncer.restore()
    assert cloud.get("events/phone-ff05/2026-08.jsonl") == older
    assert result["restored"] == ["restored/laptop-ff04/phone-ff05/2026-08.jsonl"]
    fragment = cloud.get("restored/laptop-ff04/phone-ff05/2026-08.jsonl").decode()
    assert fragment.count("\n") == 1 and '"answered"' in fragment

    tablet = mkdevice("tablet-ff06")
    tablet.boot()
    tablet.syncer.sync()
    assert tablet.db.sample_row(SAMPLE)["status"] == "answered"


def test_restore_reuploads_own_log_when_cloud_copy_is_stale(mkdevice, clock, cloud):
    dev = mkdevice("laptop-ff07")
    dev.boot(base_config([fixed_stream()]))
    ticked_to([dev], clock, "2026-08-24T16:00:00Z")
    dev.syncer.sync()
    cloud.put(f"events/{dev.name}/2026-08.jsonl", b"")  # truncated in the cloud
    result = dev.syncer.restore()
    assert result["uploaded"] == [f"events/{dev.name}/2026-08.jsonl"]
    assert b'"fired"' in cloud.get(f"events/{dev.name}/2026-08.jsonl")


# -- snapshots (§9) ---------------------------------------------------------


def test_last_sunday_0300():
    tz = "America/Los_Angeles"
    assert _last_sunday_0300(parse_utc("2026-08-24T15:00:00Z"), tz) == "2026-08-23"  # Mon
    assert _last_sunday_0300(parse_utc("2026-08-23T09:59:00Z"), tz) == "2026-08-16"  # Sun 02:59
    assert _last_sunday_0300(parse_utc("2026-08-23T10:00:00Z"), tz) == "2026-08-23"  # Sun 03:00
    assert _last_sunday_0300(parse_utc("2026-08-29T23:00:00Z"), tz) == "2026-08-23"  # Sat


def test_weekly_snapshot_promotion_and_retention(mkdevice, clock, cloud):
    """[H] The primary zips the folder once per Sunday-03:00 window, the
    month's first weekly zip is promoted to monthly, and retention keeps
    12 of each. Non-primary devices never snapshot."""
    laptop = mkdevice("laptop-ff08")
    phone = mkdevice("phone-ff09")
    laptop.boot(base_config([fixed_stream()]))
    phone.boot(base_config([fixed_stream()]))
    ticked_to([laptop, phone], clock, "2026-08-24T16:00:00Z")
    r1 = laptop.syncer.sync()
    assert r1["role"] == "primary"
    assert r1["snapshot"] == "snapshots/weekly/2026-08-23.zip"
    assert r1["snapshot_monthly"] == "snapshots/monthly/2026-08.zip"
    with zipfile.ZipFile(io.BytesIO(cloud.get("snapshots/weekly/2026-08-23.zip"))) as zf:
        names = zf.namelist()
        assert "events/laptop-ff08/2026-08.jsonl" in names
        assert "config/current.json" in names
        assert not any(n.startswith("snapshots/") for n in names)

    # Same week: nothing new. Non-primary phone: nothing at all.
    clock.advance(3600)
    assert "snapshot" not in laptop.syncer.sync()
    assert "snapshot" not in phone.syncer.sync()
    assert cloud.list("snapshots") == [
        "snapshots/monthly/2026-08.zip",
        "snapshots/weekly/2026-08-23.zip",
    ]

    # Next Sunday 03:00 local (10:00Z): a new weekly, no new monthly.
    clock.set(parse_utc("2026-08-30T10:00:00Z"))
    r2 = laptop.syncer.sync()
    assert r2["snapshot"] == "snapshots/weekly/2026-08-30.zip"
    assert "snapshot_monthly" not in r2
    # First snapshot of September promotes.
    clock.set(parse_utc("2026-09-06T10:00:00Z"))
    r3 = laptop.syncer.sync()
    assert r3["snapshot_monthly"] == "snapshots/monthly/2026-09.zip"

    # Retention: 14 weekly zips present -> pruned to the newest 12.
    for i in range(14):
        cloud.put(f"snapshots/weekly/2025-{(i % 12) + 1:02d}-{(i // 12) + 1:02d}.zip", b"old")
    laptop.syncer.sync()
    weekly = [p for p in cloud.list("snapshots/weekly")]
    assert len(weekly) == 12
    assert "snapshots/weekly/2026-09-06.zip" in weekly  # newest always kept
    assert "snapshots/weekly/2025-01-01.zip" not in weekly


# -- primary role handoff (§9) ---------------------------------------------


def test_primary_tie_break_lower_device_id_keeps(mkdevice, clock, cloud):
    a = mkdevice("laptop-ff10")
    b = mkdevice("phone-ff11")
    a.boot(base_config([fixed_stream()]))
    b.boot(base_config([fixed_stream()]))
    # Both claim before ever seeing each other (simultaneous first runs).
    a.db.kv_set("device", "role", "primary")
    b.db.kv_set("device", "role", "primary")
    assert b.syncer.sync()["role"] == "primary"  # a's doc not there yet
    assert a.syncer.sync()["role"] == "primary"  # laptop-ff10 < phone-ff11
    assert b.syncer.sync()["role"] == ""  # b sees a and yields
    assert json.loads(cloud.get("devices/phone-ff11.json"))["role"] == ""
    assert json.loads(cloud.get("devices/laptop-ff10.json"))["role"] == "primary"


def test_secondary_claims_primary_after_14_silent_days(mkdevice, clock, cloud):
    laptop = mkdevice("laptop-ff12")
    phone = mkdevice("phone-ff13")
    laptop.boot(base_config([fixed_stream()]))
    phone.boot(base_config([fixed_stream()]))
    phone.syncer = Syncer(phone.engine, cloud, platform="android")
    assert laptop.syncer.sync()["role"] == "primary"
    assert phone.syncer.sync()["role"] == ""
    clock.advance(13 * 86400)
    assert phone.syncer.sync()["role"] == ""  # not yet
    clock.advance(2 * 86400)
    assert phone.syncer.sync()["role"] == "primary"  # laptop silent 15 days
    assert "snapshot" in phone.syncer.sync() or cloud.list("snapshots/weekly")
    # The laptop comes back: it yields to the phone... unless it wins the
    # tie-break — laptop-ff12 < phone-ff13, so the laptop keeps it and the
    # phone yields at its next sync.
    assert laptop.syncer.sync()["role"] == "primary"
    assert phone.syncer.sync()["role"] == ""


def test_device_doc_records_platform(mkdevice, clock, cloud):
    dev = mkdevice("phone-ff14")
    dev.boot(base_config([fixed_stream()]))
    dev.syncer = Syncer(dev.engine, cloud, platform="android")
    dev.syncer.sync()
    doc = json.loads(cloud.get("devices/phone-ff14.json"))
    assert doc["platform"] == "android"
    assert doc["last_sync"] == fmt_utc(clock.now())


# -- Drive: deleted sub-folder recovery ------------------------------------


def test_drive_recovers_from_deleted_subfolder(tmp_path):
    fake = FakeDrive()
    db = Db(tmp_path / "d.sqlite")
    store = DriveStore(fake, db)
    store.put("events/dev-a/2026-08.jsonl", b"one\n")
    folder_id = next(f["id"] for f in fake.by_name("dev-a") if f["mimeType"] == FOLDER_MIME)
    fake.trash(folder_id)  # user deleted the sub-folder (and its file) in Drive

    fresh = DriveStore(fake, db)  # new instance, stale ID cache
    assert fresh.get("events/dev-a/2026-08.jsonl") is None
    fresh.put("events/dev-a/2026-08.jsonl", b"two\n")
    assert fresh.get("events/dev-a/2026-08.jsonl") == b"two\n"
    assert len([f for f in fake.by_name("dev-a") if f["mimeType"] == FOLDER_MIME]) == 1
    assert fresh.list("events") == ["events/dev-a/2026-08.jsonl"]
    fresh.delete("events/dev-a/2026-08.jsonl")
    assert fresh.list("events") == []
    assert fresh.metadata("events/dev-a/2026-08.jsonl") is None
