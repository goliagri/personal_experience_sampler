"""Drive-store tests (test plan §2, Milestone 3): CloudStore contract over
the fake HTTP layer, root-folder lifecycle, modifiedTime gating, interrupted
uploads, and a full cross-device sync scenario running on Drive."""

from __future__ import annotations

import pytest
from pes.store import Db, DriveStore, MultipleRootsError
from pes.store.drive import FOLDER_MIME, ROOT_FOLDER_NAME
from pes.sync import Syncer

from tests.scenarios.conftest import T0, SimDevice, base_config, fixed_stream
from tests.scenarios.drive_fake import FakeDrive


@pytest.fixture
def fake():
    return FakeDrive()


def mkstore(fake, tmp_path, name="store") -> DriveStore:
    return DriveStore(fake, Db(tmp_path / f"{name}.sqlite"))


# -- CloudStore contract --------------------------------------------------


def test_roundtrip_contract(fake, tmp_path):
    store = mkstore(fake, tmp_path)
    assert store.get("a/b/c.txt") is None
    assert store.metadata("a/b/c.txt") is None
    assert store.list("a") == []

    store.put("a/b/c.txt", b"one")
    assert store.get("a/b/c.txt") == b"one"
    meta1 = store.metadata("a/b/c.txt")
    assert meta1["size"] == 3

    store.put("a/b/c.txt", b"two!")  # overwrite updates in place
    assert store.get("a/b/c.txt") == b"two!"
    meta2 = store.metadata("a/b/c.txt")
    assert meta2["etag"] != meta1["etag"]
    assert meta2["size"] == 4
    assert len(fake.by_name("c.txt")) == 1

    assert store.put_if_absent("a/b/c.txt", b"three") is False
    assert store.get("a/b/c.txt") == b"two!"
    assert store.put_if_absent("a/d.txt", b"new") is True

    store.put("a/b/e.txt", b"x")
    assert store.list("a") == ["a/b/c.txt", "a/b/e.txt", "a/d.txt"]
    assert store.list("a/b") == ["a/b/c.txt", "a/b/e.txt"]
    assert store.list("missing") == []

    with pytest.raises(ValueError):
        store.put("../escape.txt", b"no")


# -- root folder lifecycle (§8.5) -----------------------------------------


def test_root_created_once_then_adopted_by_id(fake, tmp_path):
    store = mkstore(fake, tmp_path, "dev1")
    store.put("config/current.json", b"{}")
    assert len(fake.by_name(ROOT_FOLDER_NAME)) == 1
    root_id = fake.by_name(ROOT_FOLDER_NAME)[0]["id"]

    # Rename in Drive is harmless: the store addresses the root by stored ID.
    fake.files[root_id]["name"] = "Renamed by hand"
    store2 = DriveStore(fake, store.db)  # fresh instance, same local cache
    assert store2.get("config/current.json") == b"{}"
    store2.put("state.json", b"{}")
    assert len(fake.by_name(ROOT_FOLDER_NAME)) == 0  # no duplicate root created


def test_second_device_adopts_existing_root(fake, tmp_path):
    mkstore(fake, tmp_path, "dev1").put("config/current.json", b"{}")
    store2 = mkstore(fake, tmp_path, "dev2")
    assert store2.get("config/current.json") == b"{}"
    assert len(fake.by_name(ROOT_FOLDER_NAME)) == 1


def test_two_roots_refuses(fake, tmp_path):
    fake.add(ROOT_FOLDER_NAME, FOLDER_MIME, [])
    fake.add(ROOT_FOLDER_NAME, FOLDER_MIME, [])
    with pytest.raises(MultipleRootsError):
        mkstore(fake, tmp_path).get("config/current.json")


def test_root_recreated_after_deletion_clears_stale_ids(fake, tmp_path):
    store = mkstore(fake, tmp_path)
    store.put("a/b.txt", b"x")
    for record in fake.files.values():
        record["trashed"] = True
    store2 = DriveStore(fake, store.db)
    assert store2.get("a/b.txt") is None
    store2.put("a/b.txt", b"y")  # rebuilt from a fresh root
    assert store2.get("a/b.txt") == b"y"
    assert len(fake.by_name(ROOT_FOLDER_NAME)) == 1


# -- sync over Drive -------------------------------------------------------


def drive_device(name, tmp_path, fake, clock) -> SimDevice:
    device = SimDevice(name, tmp_path, None, clock)
    store = DriveStore(fake, device.db)
    device.syncer = Syncer(device.engine, store)
    return device


def test_cross_device_answer_merges_over_drive(fake, tmp_path, clock):
    config = base_config([fixed_stream()])
    phone = drive_device("phone", tmp_path, fake, clock)
    laptop = drive_device("laptop", tmp_path, fake, clock)
    phone.boot(config)
    phone.syncer.sync()
    laptop.boot()
    laptop.syncer.sync()
    assert laptop.db.latest_config()["version"] == 1

    clock.set(T0 + 3600)  # 09:00 local: the fixed ping fires on both
    phone.engine.tick()
    laptop.engine.tick()
    sample_id = phone.engine.active_samples()[0]["sample"]
    laptop.engine.answer(sample_id, {"tags": ["work"]})
    laptop.syncer.sync()
    phone.syncer.sync()

    row = phone.db.sample_row(sample_id)
    assert row["status"] == "answered"
    assert ("cancel", sample_id) in phone.notifier.log


def test_modified_time_gates_downloads(fake, tmp_path, clock):
    config = base_config([fixed_stream()])
    phone = drive_device("phone", tmp_path, fake, clock)
    laptop = drive_device("laptop", tmp_path, fake, clock)
    phone.boot(config)
    clock.set(T0 + 3600)
    phone.engine.tick()
    phone.syncer.sync()
    laptop.boot()
    laptop.syncer.sync()  # imports phone's events

    fake.calls.clear()
    laptop.syncer.sync()  # nothing changed in the cloud
    phone_month = [
        f["id"] for f in fake.files.values() if f["name"].endswith(".jsonl")
    ]
    downloaded = fake.media_downloads()
    assert not [i for i in phone_month if i in downloaded]


def test_interrupted_upload_retried_without_corruption(fake, tmp_path, clock):
    config = base_config([fixed_stream()])
    phone = drive_device("phone", tmp_path, fake, clock)
    phone.boot(config)
    clock.set(T0 + 3600)
    phone.engine.tick()

    fake.fail_after = len(fake.calls) + 3  # dies partway through the sync
    with pytest.raises(OSError, match="simulated"):
        phone.syncer.sync()
    fake.fail_after = None
    phone.syncer.sync()  # retried on the next trigger (§8.4 idempotence)

    month = phone.db.unsynced_months("phone")
    assert month == []  # upload completed
    stored = [f for f in fake.files.values() if f["name"].endswith(".jsonl")]
    assert len(stored) == 1
    lines = [line for line in stored[0]["content"].decode().splitlines() if line]
    expected = phone.db.month_lines("phone", stored[0]["name"].removesuffix(".jsonl"))
    assert lines == expected
