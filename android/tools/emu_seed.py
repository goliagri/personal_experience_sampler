#!/usr/bin/env python3
"""Build an isolated dev DB with the desktop code and (optionally) push it to
the emulator.

The DB is written with `pes.store.Db` (same schema as the Kotlin Db), so the
on-device state can later be checked against the desktop engine's own
materialization and fold. Nothing here touches the owner's real data or
Drive folder; the emulator keeps its own device id (`emu-pes`).

CLI: emu_seed.py [--push] [--now ISO] [--fixed HH:MM,..] [--no-poisson]
                 [--mean-gap MIN] [--expiry MIN] [--required-mood] [--tz ZONE]
Library: `build_db(path, Seed(...))` and `push(seed_path, emu_sh)`.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "desktop"))
from pes.store import Db

SEED = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"
PKG = "pes.app"
DEVICE_ID = "emu-pes"
DEFAULT_TZ = "America/Los_Angeles"


def survey(required_mood: bool = False) -> dict:
    return {
        "id": "dev", "version": 1, "title": "Dev survey",
        "fields": [
            {"id": "tags", "type": "tags", "label": "What are you doing?", "quick": True},
            {"id": "mood", "type": "number", "label": "Mood (1-7)", "min": 1, "max": 7,
             "integer": True, "required": required_mood},
            {"id": "where", "type": "choice", "label": "Where", "cardinality": "single",
             "options": ["home", "work", "out", {"value": "other", "label": "Somewhere else"}]},
            {"id": "with", "type": "choice", "label": "With", "cardinality": "multi",
             "options": ["alone", "partner", "friends", "coworkers"]},
            {"id": "note", "type": "text", "label": "Note", "multiline": True},
        ],
    }


@dataclass
class Seed:
    now_iso: str | None = None          # config effective_from; default = wall clock
    tz: str = DEFAULT_TZ
    poisson: bool = True
    mean_gap: int = 45
    fixed: list[str] = field(default_factory=lambda: ["12:00", "20:00"])
    fixed2: list[str] = field(default_factory=list)   # second fixed stream (simultaneous pings)
    expiry: int = 60
    snooze: int = 10
    max_snoozes: int = 3
    backlog_hours: int = 12
    required_mood: bool = False
    quiet_zone: bool = True             # 23:00-07:30 on the Poisson stream

    def config(self) -> dict:
        now_iso = self.now_iso or utc_now_iso()
        streams = []
        if self.poisson:
            streams.append({
                "id": "day", "name": "Daytime (Poisson)", "enabled": True, "seed": SEED,
                "protocol": {"type": "poisson", "mean_gap_minutes": self.mean_gap, "min_gap_minutes": 5},
                "quiet_zones": [{"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
                                 "from": "23:00", "to": "07:30"}] if self.quiet_zone else [],
                "survey": {"id": "dev", "version": 1}})
        if self.fixed:
            streams.append({
                "id": "fixed", "name": "Fixed times", "enabled": True, "seed": SEED + "01",
                "protocol": {"type": "fixed_times", "times_local": list(self.fixed)},
                "quiet_zones": [], "survey": {"id": "dev", "version": 1}})
        if self.fixed2:
            streams.append({
                "id": "second", "name": "Second stream", "enabled": True, "seed": SEED + "03",
                "protocol": {"type": "fixed_times", "times_local": list(self.fixed2)},
                "quiet_zones": [], "survey": {"id": "dev", "version": 1}})
        streams.append({
            "id": "off", "name": "Disabled stream", "enabled": False, "seed": SEED + "02",
            "protocol": {"type": "fixed_interval", "interval_minutes": 60},
            "quiet_zones": [], "survey": {"id": "dev", "version": 1}})
        return {
            "version": 2, "base_version": 1, "written_by": "desktop-seed",
            "written_at": now_iso, "effective_from": now_iso, "timezone": self.tz,
            "defaults": {"snooze_minutes": self.snooze, "max_snoozes": self.max_snoozes,
                         "expiry_minutes": self.expiry, "backlog_hours": self.backlog_hours,
                         "location": "off"},
            "streams": streams,
        }


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def build_db(path: str, seed: Seed) -> str:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for f in (path, path + "-wal", path + "-shm"):
        if os.path.exists(f):
            os.remove(f)
    db = Db(path)
    db.kv_set("device", "device_id", DEVICE_ID)
    db.upsert_survey(survey(seed.required_mood))
    db.upsert_config(seed.config())
    db.close()
    return path


def push(seed_path: str, emu: str, launch: bool = True) -> None:
    """Replace the app's DB on the emulator (needs `adb root`) and relaunch."""
    dst = f"/data/data/{PKG}/files/pes.sqlite"
    run = lambda *c: subprocess.run([emu, *c], check=True, stdout=subprocess.DEVNULL)
    run("shell", "am", "force-stop", PKG)
    run("root")
    run("push", seed_path, "/data/local/tmp/seed.sqlite")
    run("shell", "rm", "-f", dst, dst + "-wal", dst + "-shm", f"/data/data/{PKG}/files/last_crash.txt")
    run("shell", "mkdir", "-p", os.path.dirname(dst))
    run("shell", "cp", "/data/local/tmp/seed.sqlite", dst)
    owner = subprocess.check_output([emu, "shell", "stat", "-c", "%U", f"/data/data/{PKG}"]).decode().strip()
    run("shell", "chown", f"{owner}:{owner}", dst)
    run("shell", "chmod", "600", dst)
    run("shell", "restorecon", dst)
    run("shell", "cmd", "appops", "set", PKG, "SCHEDULE_EXACT_ALARM", "allow")
    run("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    if launch:
        run("launch")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--now")
    ap.add_argument("--tz", default=DEFAULT_TZ)
    ap.add_argument("--fixed", default="12:00,20:00", help="comma list, '' for none")
    ap.add_argument("--no-poisson", action="store_true")
    ap.add_argument("--mean-gap", type=int, default=45)
    ap.add_argument("--expiry", type=int, default=60)
    ap.add_argument("--required-mood", action="store_true")
    a = ap.parse_args()
    seed = Seed(now_iso=a.now, tz=a.tz, poisson=not a.no_poisson, mean_gap=a.mean_gap,
                fixed=[t for t in a.fixed.split(",") if t], expiry=a.expiry,
                required_mood=a.required_mood)
    out = build_db(os.path.join(ROOT, ".emu", "seed.sqlite"), seed)
    print("wrote", out)
    if a.push:
        push(out, os.path.join(ROOT, "android", "tools", "emu.sh"))
        print("pushed + relaunched")


if __name__ == "__main__":
    main()
