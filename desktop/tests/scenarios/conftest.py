"""Harness for scenario tests (test plan §2): multiple simulated devices
sharing one local-folder cloud, driven by a single fake clock."""

from __future__ import annotations

import pytest

from pes.clock import FakeClock
from pes.core.timeutil import parse_utc
from pes.engine import Engine
from pes.notify import RecordingNotifier
from pes.store import Db, LocalFolderStore
from pes.sync import Syncer

SEED = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"
TZ = "America/Los_Angeles"
T0 = parse_utc("2026-08-24T15:00:00Z")  # Monday 08:00 in America/Los_Angeles

SURVEY = {
    "id": "s1",
    "version": 1,
    "title": "Test survey",
    "fields": [
        {"id": "tags", "type": "tags", "label": "Tags", "quick": True},
        {"id": "note", "type": "text", "label": "Note", "multiline": False},
    ],
}


def fixed_stream(times=("09:00", "15:00"), sid="st", **extra) -> dict:
    return {
        "id": sid,
        "name": "Test stream",
        "enabled": True,
        "seed": SEED,
        "protocol": {"type": "fixed_times", "times_local": list(times)},
        "quiet_zones": [],
        "survey": {"id": "s1", "version": 1},
        **extra,
    }


def base_config(
    streams,
    version=1,
    base_version=0,
    effective_from="2026-08-01T00:00:00Z",
    written_at="2026-08-01T00:00:00Z",
    written_by="test",
    timezone=TZ,
    defaults=None,
) -> dict:
    return {
        "version": version,
        "base_version": base_version,
        "written_by": written_by,
        "written_at": written_at,
        "effective_from": effective_from,
        "timezone": timezone,
        "defaults": defaults
        or {
            "snooze_minutes": 10,
            "max_snoozes": 3,
            "expiry_minutes": 60,
            "backlog_hours": 12,
            "location": "off",
        },
        "streams": streams,
    }


class SimDevice:
    def __init__(self, name: str, tmp_path, cloud, clock):
        self.name = name
        self.db = Db(tmp_path / f"{name}.sqlite")
        self.db.kv_set("device", "device_id", name)
        self.notifier = RecordingNotifier()
        self.engine = Engine(self.db, name, self.notifier, clock)
        self.syncer = Syncer(self.engine, cloud)

    def boot(self, config: dict | None = None) -> None:
        if config is not None:
            self.engine.apply_config(config)
        self.db.upsert_survey(SURVEY)
        self.engine.start()

    def sample_events(self, sample_id: str) -> list[dict]:
        return [ev for _f, _l, ev in self.db.events_for_sample(sample_id)]

    def own_events(self, ev_type: str | None = None) -> list[dict]:
        out = [ev for ev in self.db.all_sample_events() if ev["dev"] == self.name]
        if ev_type:
            out = [ev for ev in out if ev["ev"] == ev_type]
        return out


@pytest.fixture
def clock():
    return FakeClock(T0)


@pytest.fixture
def cloud(tmp_path):
    return LocalFolderStore(tmp_path / "cloud")


@pytest.fixture
def mkdevice(tmp_path, cloud, clock):
    def make(name: str) -> SimDevice:
        return SimDevice(name, tmp_path, cloud, clock)

    return make
