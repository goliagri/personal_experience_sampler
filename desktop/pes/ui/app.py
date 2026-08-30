"""Main application window: navigation, tick loop, sync worker, ping queue.

Threading model: the Tk main thread owns the engine and its Db connection and
runs the tick loop via ``after``. Sync runs on a worker thread with its own
Db connection over the same SQLite file (WAL); results are posted back with
``after``. The ping/answer path never waits on sync (local-first).
"""

from __future__ import annotations

import threading
import tkinter as tk
from datetime import UTC, datetime
from pathlib import Path
from tkinter import ttk
from zoneinfo import ZoneInfo

from ..core.timeutil import fmt_utc
from ..engine import Engine
from ..notify import DesktopNotifier
from ..store import AuthorizedSession, Db, DriveAuth, DriveStore, LocalFolderStore
from ..sync import Syncer
from . import theme
from .answer import AnswerWindow

SYNC_INTERVAL_S = 30 * 60  # §8.4: desktop periodic sync while running


class App:
    def __init__(self, data_dir: Path, cloud_dir: Path, device_id: str):
        self.data_dir = data_dir
        self.cloud_dir = cloud_dir
        self.db = Db(data_dir / "pes.sqlite")
        self.device_id = device_id
        self.notifier = DesktopNotifier(on_ping=self._on_ping)
        self.engine = Engine(self.db, device_id, self.notifier)
        self.ping_queue: list[str] = []
        self.answer_open = False
        self._sync_lock = threading.Lock()

        self.root = tk.Tk()
        self.root.title("Personal Experience Sampler")
        self.root.geometry("760x560")
        self.theme_name = self.db.kv_get("device", "theme") or "light"
        self.colors = theme.THEMES[self.theme_name]
        self._style()
        self._build()

    # -- construction -----------------------------------------------------

    def _style(self) -> None:
        # "clam" is the one built-in ttk engine that honors color settings on
        # every platform; the Windows/mac native engines ignore most of them.
        style = ttk.Style(self.root)
        style.theme_use("clam")
        colors = self.colors
        self.root.configure(bg=colors["bg"])

        style.configure(
            ".",
            background=colors["bg"],
            foreground=colors["fg"],
            fieldbackground=colors["entry_bg"],
            troughcolor=colors["card"],
            bordercolor=colors["muted"],
            lightcolor=colors["bg"],
            darkcolor=colors["bg"],
            insertcolor=colors["fg"],
            selectbackground=colors["accent"],
            selectforeground=colors["bg"],
        )
        style.configure("TFrame", background=colors["bg"])
        style.configure("TLabel", background=colors["bg"], foreground=colors["fg"])
        style.configure("Card.TFrame", background=colors["card"])
        style.configure("Card.TLabel", background=colors["card"], foreground=colors["fg"])
        style.configure("TButton", background=colors["card"], foreground=colors["fg"])
        style.map(
            "TButton",
            background=[("active", colors["accent"]), ("pressed", colors["accent"])],
            foreground=[("active", colors["bg"]), ("pressed", colors["bg"])],
        )
        for kind in ("TCheckbutton", "TRadiobutton"):
            style.configure(kind, background=colors["bg"], foreground=colors["fg"])
            style.map(kind, background=[("active", colors["bg"])])
        for kind in ("TEntry", "TCombobox", "TSpinbox"):
            style.configure(
                kind,
                fieldbackground=colors["entry_bg"],
                foreground=colors["fg"],
                insertcolor=colors["fg"],
            )
            style.map(kind, fieldbackground=[("readonly", colors["card"])])
        style.configure(
            "Treeview",
            background=colors["card"],
            fieldbackground=colors["card"],
            foreground=colors["fg"],
        )
        style.configure(
            "Heading", background=colors["bg"], foreground=colors["fg"]
        )
        style.map("Treeview", background=[("selected", colors["accent"])])
        style.configure("TNotebook", background=colors["bg"])
        style.configure(
            "TNotebook.Tab", background=colors["card"], foreground=colors["fg"]
        )
        style.map("TNotebook.Tab", background=[("selected", colors["bg"])])
        for kind in ("Vertical.TScrollbar", "Horizontal.TScrollbar"):
            style.configure(
                kind, background=colors["card"], troughcolor=colors["bg"]
            )
        style.configure("TSeparator", background=colors["muted"])

        # Classic (non-ttk) widgets — Text, Listbox, Canvas, Scale, dialogs —
        # take their defaults from the option database. Only affects widgets
        # created afterwards; theme switches rebuild the screens anyway.
        for pattern, value in (
            ("*Text.background", colors["entry_bg"]),
            ("*Text.foreground", colors["fg"]),
            ("*Text.insertBackground", colors["fg"]),
            ("*Listbox.background", colors["entry_bg"]),
            ("*Listbox.foreground", colors["fg"]),
            ("*Canvas.background", colors["bg"]),
            ("*Scale.background", colors["bg"]),
            ("*Scale.foreground", colors["fg"]),
            ("*Scale.troughColor", colors["card"]),
            ("*Scale.highlightBackground", colors["bg"]),
            ("*Toplevel.background", colors["bg"]),
            ("*Label.background", colors["bg"]),
            ("*Label.foreground", colors["fg"]),
            ("*selectBackground", colors["accent"]),
            ("*selectForeground", colors["bg"]),
        ):
            self.root.option_add(pattern, value)

    def apply_theme(self, name: str) -> None:
        """Switch themes live: restyle, then rebuild every screen."""
        self.theme_name = name
        self.colors = theme.THEMES[name]
        self._style()
        for screen in self.screens.values():
            screen.refresh()

    def _build(self) -> None:
        from .backlog import BacklogScreen
        from .history import HistoryScreen
        from .home import HomeScreen
        from .settings import SettingsScreen
        from .streams import StreamsScreen
        from .surveys import SurveysScreen

        nav = ttk.Frame(self.root, padding=(8, 6))
        nav.pack(fill="x")
        self.container = ttk.Frame(self.root)
        self.container.pack(fill="both", expand=True)
        self.status_var = tk.StringVar(value="")
        ttk.Label(self.root, textvariable=self.status_var, padding=(8, 3)).pack(
            fill="x", side="bottom"
        )

        self.screens = {
            "Home": HomeScreen(self.container, self),
            "Backlog": BacklogScreen(self.container, self),
            "History": HistoryScreen(self.container, self),
            "Streams": StreamsScreen(self.container, self),
            "Surveys": SurveysScreen(self.container, self),
            "Settings": SettingsScreen(self.container, self),
        }
        for name in self.screens:
            ttk.Button(nav, text=name, command=lambda n=name: self.show(n)).pack(
                side="left", padx=2
            )
        self.current: str | None = None
        self.show("Home")

    # -- navigation -------------------------------------------------------

    def show(self, name: str) -> None:
        if self.current:
            self.screens[self.current].pack_forget()
        self.current = name
        screen = self.screens[name]
        screen.pack(fill="both", expand=True)
        screen.refresh()

    def refresh(self) -> None:
        if self.current:
            self.screens[self.current].refresh()

    def set_status(self, text: str) -> None:
        self.status_var.set(text)

    def local_str(self, epoch: int) -> str:
        config = self.engine.config_at(epoch) or {"timezone": "UTC"}
        tz = ZoneInfo(config["timezone"])
        return datetime.fromtimestamp(epoch, UTC).astimezone(tz).strftime(
            "%a %Y-%m-%d %H:%M"
        )

    # -- ping handling ----------------------------------------------------

    def _on_ping(self, sample_id: str) -> None:
        # Called from the tick loop (main thread). If a survey is open, queue
        # and present after submit (§6.5).
        if self.answer_open:
            if sample_id not in self.ping_queue:
                self.ping_queue.append(sample_id)
        else:
            self.open_answer(sample_id)

    def open_answer(
        self,
        sample_id: str,
        supersedes: str | None = None,
        prefill: dict | None = None,
    ) -> None:
        self.answer_open = True
        window = AnswerWindow(self, sample_id, supersedes=supersedes, prefill=prefill)
        self._active_answer = window
        window.protocol("WM_DELETE_WINDOW", lambda: self._answer_closed(window))
        window.bind("<Destroy>", lambda e: self._answer_closed(window) if e.widget is window else None)

    def _answer_closed(self, window) -> None:
        # Single drain point: however the window went away (submit, skip,
        # snooze, Escape, or the close button), queued pings are presented
        # next — closing without submitting must not strand them.
        if getattr(self, "_active_answer", None) is not window:
            return
        self._active_answer = None
        self.answer_open = False
        if window.winfo_exists():
            window.destroy()
        self.show_next_queued()

    def show_next_queued(self) -> None:
        if not self.answer_open and self.ping_queue:
            self.open_answer(self.ping_queue.pop(0))

    # -- tick loop --------------------------------------------------------

    def start(self) -> None:
        self.engine.ensure_config(self._guess_timezone())
        self.engine.start()
        self.root.after(500, self._tick)
        self.root.after(3000, self.sync_async)
        self.root.after(SYNC_INTERVAL_S * 1000, self._periodic_sync)

    @staticmethod
    def _guess_timezone() -> str:
        try:
            # Cross-platform (the only reliable IANA name source on Windows).
            from tzlocal import get_localzone_name

            name = get_localzone_name()
            if name:
                return name
        except Exception:  # noqa: BLE001, S110 - optional dependency probing
            pass
        try:
            tzfile = Path("/etc/timezone")
            if tzfile.is_file():
                return tzfile.read_text().strip()
        except OSError:
            pass
        return "UTC"

    def _tick(self) -> None:
        try:
            next_wake = self.engine.tick()
        except Exception as exc:  # noqa: BLE001 - keep the loop alive; surface it
            self.set_status(f"tick error: {exc}")
            next_wake = None
        if self.current == "Home":
            self.refresh()
        now = self.engine.clock.now()
        delay = 30 if next_wake is None else max(1, min(next_wake - now, 30))
        self.root.after(delay * 1000, self._tick)

    # -- sync -------------------------------------------------------------

    def cloud_backend(self) -> str:
        return self.db.kv_get("device", "cloud_backend") or "folder"

    def drive_auth(self) -> DriveAuth:
        secret = self.db.kv_get("device", "drive_client_secret") or ""
        return DriveAuth(secret, self.data_dir / "drive_token.json")

    def _make_store(self, db: Db):
        """Cloud store for a sync run; ``db`` is the worker's own connection."""
        if (db.kv_get("device", "cloud_backend") or "folder") == "drive":
            secret = db.kv_get("device", "drive_client_secret") or ""
            auth = DriveAuth(secret, self.data_dir / "drive_token.json")
            return DriveStore(AuthorizedSession(auth), db)
        return LocalFolderStore(self.cloud_dir)

    def _periodic_sync(self) -> None:
        self.sync_async()
        self.root.after(SYNC_INTERVAL_S * 1000, self._periodic_sync)

    def sync_async(self) -> None:
        self._run_sync("Syncing...", lambda syncer: syncer.sync())

    def restore_async(self) -> None:
        """§8.6: rebuild the cloud folder from this device's cache, then sync."""

        def work(syncer: Syncer) -> dict:
            result = syncer.restore()
            n = len(result["uploaded"]) + len(result["restored"]) + len(result["docs"])
            sync_result = result["sync"]
            sync_result["warnings"] = [f"restored {n} file(s)", *sync_result["warnings"]]
            return sync_result

        self._run_sync("Restoring cloud folder...", work)

    def _run_sync(self, status: str, job) -> None:
        if not self._sync_lock.acquire(blocking=False):
            return  # a sync is already running
        self.set_status(status)

        def work() -> None:
            try:
                db = Db(self.data_dir / "pes.sqlite")
                engine = Engine(db, self.device_id, self.notifier)
                engine.clock = self.engine.clock
                result = job(Syncer(engine, self._make_store(db)))
                message = f"Synced {fmt_utc(engine.clock.now())}"
                if result["warnings"]:
                    message += " - " + "; ".join(result["warnings"])
                db.close()
            except Exception as exc:  # noqa: BLE001 - report, retry next trigger
                message = f"Sync failed: {exc}"
            finally:
                self._sync_lock.release()
            self.root.after(0, lambda: (self.set_status(message), self.refresh()))

        threading.Thread(target=work, daemon=True, name="pes-sync").start()

    def run(self) -> None:
        self.start()
        self.root.mainloop()
