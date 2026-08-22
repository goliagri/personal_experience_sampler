"""Config change and conflict scenarios (test plan §2, "Config")."""

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


def test_future_effective_config_regenerates_piecewise(mkdevice, clock):
    dev = mkdevice("laptop-dddd0001")
    dev.boot(base_config([fixed_stream()]))
    ticked_to([dev], clock, "2026-08-24T16:00:00Z")  # 09:00 ping fires under v1
    assert [ev["ev"] for ev in dev.sample_events(SAMPLE)] == ["fired"]

    # New times from Tuesday 00:00 UTC (still Monday locally; Monday's
    # remaining 15:00 ping is generated under v1, Tuesday's under v2).
    errors = dev.engine.stage_new_config(
        streams=[fixed_stream(times=("10:00", "18:00"))],
        defaults=dev.db.latest_config()["defaults"],
        timezone="America/Los_Angeles",
        effective_from="2026-08-25T07:00:00Z",  # Tuesday local midnight
    )
    assert errors == []

    planned = {
        s: v
        for s, _st, sch, v in dev.db.conn.execute(
            "SELECT sample, stream, scheduled_utc, config_v FROM schedule"
        ).fetchall()
        for s in [sch]
    }
    assert planned.get("2026-08-24T22:00:00Z") == 1  # Monday 15:00 stays, v1
    assert planned.get("2026-08-25T17:00:00Z") == 2  # Tuesday 10:00, v2
    assert "2026-08-25T16:00:00Z" not in planned  # Tuesday 09:00 gone

    # The already-fired sample is undisturbed.
    assert [ev["ev"] for ev in dev.sample_events(SAMPLE)] == ["fired"]


def test_concurrent_config_edit_conflict(mkdevice, clock, cloud):
    laptop = mkdevice("laptop-dddd0002")
    phone = mkdevice("phone-dddd0003")
    laptop.boot(base_config([fixed_stream()]))
    phone.boot(base_config([fixed_stream()]))
    laptop.syncer.sync()
    phone.syncer.sync()

    # Both edit from base v1 without syncing in between.
    assert (
        laptop.engine.stage_new_config(
            streams=[fixed_stream(times=("10:00",))],
            defaults=laptop.db.latest_config()["defaults"],
            timezone="America/Los_Angeles",
            effective_from="2026-08-25T07:00:00Z",
        )
        == []
    )
    clock.advance(60)  # phone's edit has the later written_at
    assert (
        phone.engine.stage_new_config(
            streams=[fixed_stream(times=("11:00",))],
            defaults=phone.db.latest_config()["defaults"],
            timezone="America/Los_Angeles",
            effective_from="2026-08-25T07:00:00Z",
        )
        == []
    )

    laptop.syncer.sync()  # uploads laptop's v2
    result = phone.syncer.sync()  # detects the branch; phone's v2 is later
    assert len(result["conflicts"]) == 1
    assert result["warnings"]

    result = laptop.syncer.sync()  # adopts phone's v2; sees the warning
    assert result["warnings"]

    for dev in (laptop, phone):
        latest = dev.db.latest_config()
        assert latest["version"] == 2
        assert latest["written_by"] == "phone-dddd0003"
        assert latest["streams"][0]["protocol"]["times_local"] == ["11:00"]

    conflicts = cloud.list("config/conflicts")
    assert len(conflicts) == 1
    rejected = json.loads(cloud.get(conflicts[0]))
    assert rejected["written_by"] == "laptop-dddd0002"
    assert rejected["streams"][0]["protocol"]["times_local"] == ["10:00"]
    assert json.loads(cloud.get("config/current.json"))["written_by"] == "phone-dddd0003"
