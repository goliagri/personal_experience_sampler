"""On-device scenario harness (TEST_PLAN §2b): drives the real Android app on
the headless emulator through `android/tools/emu.sh`, with deterministic
time (the device clock is pinned per test) and assertions made on the pulled
SQLite DB using the *desktop* implementation — every check doubles as a
cross-implementation conformance check.

Run: `cd android/tests/device && python -m pytest` (emulator auto-starts;
pass `--no-install` to skip the Gradle build of the debug APK).
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
EMU = str(ROOT / "android" / "tools" / "emu.sh")
OUT = ROOT / ".emu" / "device-tests"
sys.path.insert(0, str(ROOT / "desktop"))
sys.path.insert(0, str(ROOT / "android" / "tools"))

from emu_seed import DEVICE_ID, PKG, Seed, build_db, push
from pes.clock import FakeClock
from pes.core.timeutil import fmt_utc, parse_utc
from pes.engine import Engine
from pes.notify import RecordingNotifier
from pes.store import Db

# Canonical pinned scenario: Tuesday 11:55 America/Los_Angeles; the fixed
# stream's 12:00 ping is five minutes out, expiry 60 min, snooze 10 min.
NOW = "2026-09-01T18:55:00Z"
PING = "fixed|2026-09-01T19:00:00Z"
PING_EPOCH = parse_utc("2026-09-01T19:00:00Z")
NOW_EPOCH = parse_utc(NOW)


def iso(epoch: int) -> str:
    return fmt_utc(epoch)


def pytest_addoption(parser):
    parser.addoption("--no-install", action="store_true", help="skip gradle installDebug")


# -- adb / emulator plumbing --------------------------------------------------


@dataclass
class Notification:
    id: int
    title: str
    text: str
    actions: int


@dataclass
class Node:
    text: str
    desc: str
    rid: str
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


class Device:
    """Thin, explicit wrapper over emu.sh; every method is one adb round-trip
    unless it says it waits."""

    def __init__(self):
        OUT.mkdir(parents=True, exist_ok=True)
        self.seed_path = str(OUT / "seed.sqlite")

    # raw
    def emu(self, *args: str, check: bool = True) -> str:
        r = subprocess.run([EMU, *args], capture_output=True, text=True, check=False)
        if check and r.returncode != 0:
            raise RuntimeError(f"emu.sh {' '.join(args)} failed: {r.stderr or r.stdout}")
        return r.stdout

    def shell(self, cmd: str, check: bool = True) -> str:
        return self.emu("shell", cmd, check=check)

    # time
    def now(self) -> int:
        return int(self.shell("date +%s").strip())

    def settime(self, when: str | int) -> None:
        """Pin the device clock (UTC). Fires TIME_SET → BootReceiver.start()
        and any RTC alarms now in the past, exactly like a real clock jump."""
        epoch = when if isinstance(when, int) else parse_utc(when)
        stamp = datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        self.shell(f"date -u -s '{stamp}'")
        assert abs(self.now() - epoch) <= 2, "device clock did not take"

    # app lifecycle
    def dismiss_system_dialogs(self) -> None:
        """Clear an ANR dialog if one is up.

        Driving the clock around upsets the Pixel Launcher, and its "isn't
        responding" dialog takes window focus — so every UI assertion fails
        with the app running perfectly well underneath. Close the offender
        rather than waiting for it.
        """
        for n in self.nodes(_retry=False):
            if n.rid.endswith("aerr_close"):
                self.tap(*n.center)
                time.sleep(1)
                break
        # It ANRs again as soon as it is drawn; stop it rather than play whack-a-mole.
        self.shell("am force-stop com.google.android.apps.nexuslauncher", check=False)

    def launch(self) -> None:
        # One retry: after a reboot (or with the shade or an ANR dialog left
        # over by a previous test) the first `am start` can be swallowed, and
        # every screen test then fails for a reason that is not the app's.
        self.close_shade()
        self.emu("launch")
        try:
            self.wait(lambda: self.ui_has(text="Experience Sampler"), 15, "app did not show")
        except AssertionError:
            self.dismiss_system_dialogs()
            self.home_key()
            self.emu("launch")
            self.wait(lambda: self.ui_has(text="Experience Sampler"), 20, "app did not show (2 tries)")

    def force_stop(self) -> None:
        """Note: puts the app in Android's *stopped state*, which also cancels
        its alarms — use kill() to model 'swiped away from recents'."""
        self.shell(f"am force-stop {PKG}")

    def kill(self) -> None:
        """Kill the background process like a swipe-away / LMK; alarms survive."""
        self.home_key()
        time.sleep(1)
        self.shell(f"su root kill -9 $(pidof {PKG})", check=False)
        self.wait(lambda: not self.shell(f"pidof {PKG}", check=False).strip(), 10, "process still alive")

    def home_key(self) -> None:
        self.emu("key", "HOME")

    def back(self) -> None:
        self.emu("key", "BACK")

    def reboot(self) -> None:
        """Waits for boot. The guest clock resets toward host time on reboot;
        callers re-pin it with settime()."""
        self.emu("reboot")
        self.shell("settings put global auto_time 0")
        self.wait(lambda: PKG in self.shell("dumpsys alarm", check=False), 90, "app never ran after boot")

    def doze(self) -> None:
        self.shell("dumpsys battery unplug")
        self.shell("dumpsys deviceidle enable")
        self.shell("dumpsys deviceidle force-idle")

    def undoze(self) -> None:
        self.shell("dumpsys deviceidle unforce", check=False)
        self.shell("dumpsys battery reset")

    # seeding
    def seed(self, seed: Seed | None = None, now: str = NOW) -> None:
        """Pin the clock, install a fresh DB, relaunch, wait for the first tick."""
        seed = seed or Seed()
        seed.now_iso = seed.now_iso or now
        self.undoze()
        self.settime(now)
        build_db(self.seed_path, seed)
        push(self.seed_path, EMU, launch=True)
        self.wait(lambda: self.alarms() != [] or self.shell("dumpsys alarm").count(PKG) > 0, 20, "no first tick")
        self.wait(lambda: self.ui_has(text="Experience Sampler"), 15, "app did not show")
        self.clear_notifications_of_others()

    # alarms
    def alarms(self) -> list[tuple[int, int]]:
        """[(origWhen epoch seconds, window ms)] for this app's RTC alarms."""
        out = self.shell("dumpsys alarm")
        found = []
        for m in re.finditer(r"Alarm\{\w+ type (\d) origWhen (\d+) whenElapsed \d+ " + re.escape(PKG) + r"\}(.*?)(?=Alarm\{|\Z)",
                             out, re.DOTALL):
            typ, when, body = int(m.group(1)), int(m.group(2)), m.group(3)
            if typ not in (0, 1):
                continue
            w = re.search(r"window=(\+?[^ ]+)", body)
            window = _parse_window_ms(w.group(1)) if w else -1
            found.append((when // 1000, window))
        return found

    def next_alarm(self) -> tuple[int, int]:
        a = self.alarms()
        assert a, "no RTC alarm armed"
        return min(a)

    # notifications
    def notifications(self) -> list[Notification]:
        out = self.shell("dumpsys notification --noredact")
        recs = []
        for m in re.finditer(r"NotificationRecord\(.*?pkg=" + re.escape(PKG) + r" .*?id=(-?\d+).*?actions=(\d+).*?\n(.*?)(?=NotificationRecord\(|\Z)",
                             out, re.DOTALL):
            body = m.group(3)
            t = re.search(r"android\.title=String \((.*?)\)\n", body)
            x = re.search(r"android\.text=String \((.*?)\)\n", body)
            recs.append(Notification(int(m.group(1)), t.group(1) if t else "", x.group(1) if x else "", int(m.group(2))))
        return recs

    def clear_notifications_of_others(self) -> None:
        # The emulator posts "serial console" / "keyboard" notices that
        # clutter the shade and can collapse ours into a group.
        for pkg in ("com.android.systemui", "android"):
            self.shell(f"cmd notification cancel_all {pkg}", check=False)  # best effort

    def wait_notification(self, title_contains: str, timeout: float = 20) -> Notification:
        got: list[Notification] = []

        def ok():
            got[:] = [n for n in self.notifications() if title_contains in n.title]
            return bool(got)

        self.wait(ok, timeout, f"no notification with title containing {title_contains!r}")
        return got[0]

    def wait_no_notification(self, timeout: float = 15) -> None:
        self.wait(lambda: not self.notifications(), timeout, "notification still present")

    # shade
    def open_shade(self) -> None:
        self.shell("cmd statusbar expand-notifications")
        self.wait(lambda: self.ui_has(text="Experience Sampler") or self.ui_has(text="Snooze"), 5, "shade")
        # A grouped notification hides its actions until expanded.
        if not self.ui_has(text="Snooze"):
            hdr = self.find(text="Experience Sampler")
            if hdr:
                self.tap(*hdr.center)
                self.wait(lambda: self.ui_has(text="Snooze"), 5, "shade group did not expand")

    def close_shade(self) -> None:
        self.shell("cmd statusbar collapse")
        time.sleep(0.5)

    def shade_actions(self) -> list[str]:
        return [n.text for n in self.nodes() if n.text in ("Open", "Snooze", "Skip", "Reply tags")]

    def shade_tap(self, action: str) -> None:
        self.open_shade()
        self.tap_text(action)
        self.close_shade()

    def shade_reply(self, text: str) -> None:
        self.open_shade()
        self.tap_text("Reply tags")
        self.wait(lambda: self.find(rid="com.android.systemui:id/remote_input_text") is not None, 5, "no reply box")
        self.type(text)
        self.tap(*self.find(rid="com.android.systemui:id/remote_input_send").center)
        time.sleep(1.5)
        self.close_shade()

    # ui
    def uixml(self) -> str:
        for _ in range(3):
            xml = self.emu("uixml", check=False)
            if "<hierarchy" in xml:
                return xml[xml.index("<?xml") if "<?xml" in xml else xml.index("<hierarchy"):]
            time.sleep(0.5)
        return "<hierarchy/>"

    def nodes(self, _retry: bool = True) -> list[Node]:
        out = []
        for el in ET.fromstring(self.uixml()).iter("node"):
            b = re.findall(r"\d+", el.get("bounds", ""))
            if len(b) != 4:
                continue
            out.append(Node(el.get("text", ""), el.get("content-desc", ""), el.get("resource-id", ""),
                            tuple(int(v) for v in b)))
        # Driving the clock upsets the Pixel Launcher, and its "isn't
        # responding" dialog takes window focus — every assertion then fails
        # with the app running perfectly well behind it. Every UI read goes
        # through here, so heal it once here rather than at each call site.
        if _retry and any(n.rid.endswith("aerr_close") for n in out):
            self.dismiss_system_dialogs()
            return self.nodes(_retry=False)
        return out

    def texts(self) -> list[str]:
        return [n.text for n in self.nodes() if n.text]

    def find(self, text: str | None = None, contains: str | None = None, rid: str | None = None,
             desc: str | None = None) -> Node | None:
        for n in self.nodes():
            if text is not None and n.text != text:
                continue
            if contains is not None and contains not in n.text:
                continue
            if rid is not None and n.rid != rid:
                continue
            if desc is not None and n.desc != desc:
                continue
            return n
        return None

    def ui_has(self, **kw) -> bool:
        return self.find(**kw) is not None

    def tap(self, x: int, y: int) -> None:
        self.emu("tap", str(x), str(y))
        time.sleep(0.8)

    def tap_text(self, text: str | None = None, **kw) -> None:
        n = self.wait_find(text=text, **kw)
        self.tap(*n.center)

    def wait_find(self, timeout: float = 8, scroll: bool = True, **kw) -> Node:
        """Find a node, scrolling the page down once if it is below the fold
        (the answer screen is one scrolling page; nothing else scrolls)."""
        holder: list[Node] = []

        def ok():
            n = self.find(**kw)
            if n:
                holder.append(n)
            return bool(n)

        try:
            self.wait(ok, timeout, f"ui node {kw} not found; visible: {self.texts()}")
        except AssertionError:
            if not scroll:
                raise
            self.hide_keyboard()
            # Up to three swipes: the answer page is one long scroll, and how
            # far a field sits down it depends on the survey and on the field
            # types (radio/checkbox rows are taller than chips).
            for attempt in range(3):
                self.shell("input swipe 540 1100 540 380 300")
                time.sleep(0.8)
                if ok():
                    return holder[0]
            self.wait(ok, 3, f"ui node {kw} not found after scrolling; visible: {self.texts()}")
        return holder[0]

    def page_texts(self) -> list[str]:
        """All texts on a one-page scrolling screen, top to bottom."""
        self.hide_keyboard()
        seen = self.texts()
        for _ in range(3):
            self.shell("input swipe 540 1100 540 380 300")
            time.sleep(0.6)
            more = [t for t in self.texts() if t not in seen]
            if not more:
                break
            seen += more
        for _ in range(3):
            self.shell("input swipe 540 600 540 1800 300")
        time.sleep(0.5)
        return seen

    def go_home(self) -> None:
        for _ in range(4):
            if self.ui_has(text="No active ping.") or self.ui_has(text="Answer") and self.ui_has(text="Streams"):
                return
            self.back()
            time.sleep(0.5)
        self.wait_find(text="Streams", scroll=False)

    def hide_keyboard(self) -> None:
        # ESCAPE closes most IMEs; the numeric keypad ignores it, and BACK is
        # what actually dismisses that one (BACK is safe here — while the IME
        # is up it is consumed by the IME, not by the activity).
        for key in ("ESCAPE", "BACK"):
            if "mInputShown=true" not in self.shell("dumpsys input_method", check=False):
                return
            self.emu("key", key)
            time.sleep(0.5)

    def type(self, text: str) -> None:
        self.shell("input text " + _shell_quote(text.replace(" ", "%s")))
        time.sleep(0.5)

    def type_into(self, label: str, text: str) -> None:
        """Tap the field whose label/hint is `label`, then type."""
        n = self.wait_find(text=label)
        self.tap(*n.center)
        self.type(text)
        self.hide_keyboard()

    def screenshot(self, name: str) -> Path:
        p = OUT / f"{name}.png"
        with open(p, "wb") as f:
            subprocess.run([EMU, "shell", "screencap -p"], stdout=f, check=True)
        return p

    # db
    def pull_db(self) -> Db:
        local = OUT / "pulled.sqlite"
        for suffix in ("", "-wal", "-shm"):
            if (OUT / f"pulled.sqlite{suffix}").exists():
                os.remove(OUT / f"pulled.sqlite{suffix}")
        # Checkpoint WAL so the main file is complete, then copy it out.
        self.shell(f"run-as {PKG} sh -c 'echo \"PRAGMA wal_checkpoint(TRUNCATE);\" > /dev/null'", check=False)
        for suffix in ("", "-wal"):
            r = subprocess.run([EMU, "shell", f"su root cat /data/data/{PKG}/files/pes.sqlite{suffix}"],
                               capture_output=True, check=False)
            if r.returncode == 0 and r.stdout:
                with open(f"{local}{suffix}", "wb") as f:
                    f.write(r.stdout)
        return Db(local)

    def events(self, sample: str) -> list[dict]:
        db = self.pull_db()
        try:
            return [ev for _f, _l, ev in db.events_for_sample(sample)]
        finally:
            db.close()

    def ev_types(self, sample: str) -> list[str]:
        return [e["ev"] for e in self.events(sample)]

    def sample(self, sample: str) -> dict | None:
        db = self.pull_db()
        try:
            return db.sample_row(sample)
        finally:
            db.close()

    def samples(self, **kw) -> list[dict]:
        db = self.pull_db()
        try:
            return db.sample_rows(**kw)
        finally:
            db.close()

    def kv(self, ns: str, key: str) -> str | None:
        db = self.pull_db()
        try:
            return db.kv_get(ns, key)
        finally:
            db.close()

    def crash(self) -> str:
        return self.shell(f"su root cat /data/data/{PKG}/files/last_crash.txt", check=False)

    # util
    @staticmethod
    def wait(pred, timeout: float, what: str, interval: float = 0.7) -> None:
        end = time.time() + timeout
        while time.time() < end:
            if pred():
                return
            time.sleep(interval)
        raise AssertionError(f"timeout ({timeout}s) waiting: {what}")


def _parse_window_ms(s: str) -> int:
    if s in ("0", "+0"):
        return 0
    total = 0
    for val, unit in re.findall(r"(\d+)(d|h|m|s|ms)", s):
        total += int(val) * {"d": 86_400_000, "h": 3_600_000, "m": 60_000, "s": 1000, "ms": 1}[unit]
    return total


def _shell_quote(s: str) -> str:
    return "'" + s.replace("'", "'\\''") + "'"


# -- desktop-side oracle -------------------------------------------------------


def desktop_engine(seed_db: str, now_epoch: int, boot_at: int | None = None) -> tuple[Engine, RecordingNotifier, Db]:
    """The desktop engine over a *copy* of the seed DB, clock pinned: the
    reference implementation for what the phone should have computed.
    `boot_at` first-runs the engine at an earlier instant (the phone's seed
    time) so that a later start() has a past to backfill."""
    ref = OUT / "oracle.sqlite"
    for suffix in ("", "-wal", "-shm"):
        p = Path(f"{ref}{suffix}")
        if p.exists():
            p.unlink()
    shutil.copy(seed_db, ref)
    db = Db(ref)
    notifier = RecordingNotifier()
    clock = FakeClock(boot_at if boot_at is not None else now_epoch)
    engine = Engine(db, DEVICE_ID, notifier, clock)
    engine.start()
    if boot_at is not None and boot_at != now_epoch:
        engine.tick()
        clock.set(now_epoch)
        engine.start()
        engine.tick()
    return engine, notifier, db


# -- fixtures ------------------------------------------------------------------


@pytest.fixture(scope="session")
def emulator(request):
    subprocess.run([EMU, "start"], check=True, capture_output=True)
    d = Device()
    installed = PKG in d.shell("pm list packages pes.app", check=False)
    # connectedAndroidTest uninstalls the app when it finishes, so --no-install
    # only skips the rebuild when the package is actually present.
    if not request.config.getoption("--no-install") or not installed:
        r = subprocess.run([EMU, "install"], capture_output=True, text=True, check=False)
        assert r.returncode == 0, r.stdout + r.stderr
    d.emu("root")  # settime / DB push / kill need root (google_apis image)
    d.shell("settings put global auto_time 0")
    d.shell("settings put global auto_time_zone 0")
    d.shell("cmd appops set pes.app SCHEDULE_EXACT_ALARM allow")
    d.shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    yield d
    # Leave the emulator on wall-clock time so ad-hoc use afterwards is sane.
    d.undoze()
    d.settime(int(time.time()))


@pytest.fixture
def dev(emulator: Device, request):
    d = emulator
    d.undoze()
    d.close_shade()
    d.shell(f"cmd notification cancel_all {PKG}", check=False)
    yield d
    if request.node.rep_call.failed if hasattr(request.node, "rep_call") else False:
        d.screenshot(f"FAIL-{request.node.name}")
        (OUT / f"FAIL-{request.node.name}.txt").write_text(
            "ui:\n" + "\n".join(d.texts()) + "\n\nnotifs:\n" + json.dumps([n.__dict__ for n in d.notifications()])
            + "\n\ncrash:\n" + d.crash()
        )
    d.close_shade()


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    setattr(item, f"rep_{rep.when}", rep)
