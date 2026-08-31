"""Home screen (spec §10.2): active card, backlog link, stream summaries,
quiet toggle, sync line, ping calendar. Next-ping times stay hidden."""

from __future__ import annotations

import tkinter as tk
from datetime import UTC, date, datetime, timedelta
from tkinter import simpledialog, ttk
from zoneinfo import ZoneInfo

from ..core.timeutil import fmt_utc, parse_utc
from . import theme


class HomeScreen(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app

    def refresh(self) -> None:
        for child in self.winfo_children():
            child.destroy()
        engine = self.app.engine
        now = engine.clock.now()

        # Active sample card.
        active = engine.active_samples(now)
        if active:
            card = ttk.Frame(self, style="Card.TFrame", padding=10)
            card.pack(fill="x", pady=(0, 8))
            row = active[0]
            ttk.Label(
                card,
                text=f"{self._stream_name(row['stream'])} - {self.app.local_str(parse_utc(row['scheduled_utc']))}",
                font=theme.FONT_BIG,
                style="Card.TLabel",
            ).pack(anchor="w")
            buttons = ttk.Frame(card, style="Card.TFrame")
            buttons.pack(anchor="w", pady=(6, 0))
            sample = row["sample"]
            ttk.Button(
                buttons, text="Answer", command=lambda: self.app.open_answer(sample)
            ).pack(side="left")
            ttk.Button(buttons, text="Snooze", command=lambda: self._snooze(sample)).pack(
                side="left", padx=6
            )
            ttk.Button(buttons, text="Skip", command=lambda: self._skip(sample)).pack(
                side="left"
            )
            if len(active) > 1:
                ttk.Label(card, text=f"+{len(active) - 1} more waiting", style="Card.TLabel").pack(
                    anchor="w"
                )
        else:
            ttk.Label(self, text="No active ping.", foreground=self.app.colors["muted"]).pack(
                anchor="w", pady=(0, 8)
            )

        # Backlog link.
        backlog = engine.backlog(now)
        link = ttk.Frame(self)
        link.pack(fill="x")
        ttk.Label(link, text=f"Backlog: {len(backlog)} sample(s)").pack(side="left")
        if backlog:
            ttk.Button(link, text="Open backlog", command=lambda: self.app.show("Backlog")).pack(
                side="left", padx=8
            )

        # Quiet mode toggle.
        quiet = ttk.Frame(self)
        quiet.pack(fill="x", pady=8)
        state = engine.quiet_state()
        if engine.quiet_active(now):
            until = state.get("quiet_until")
            text = "Quiet: on" + ("" if until == "indefinite" else f" until {until}")
            ttk.Label(quiet, text=text).pack(side="left")
            ttk.Button(quiet, text="Turn off", command=lambda: self._set_quiet(None)).pack(
                side="left", padx=8
            )
        else:
            ttk.Label(quiet, text="Quiet: off").pack(side="left")
            ttk.Button(
                quiet, text="Until turned off", command=lambda: self._set_quiet("indefinite")
            ).pack(side="left", padx=8)
            ttk.Button(quiet, text="For H:MM...", command=self._quiet_for).pack(side="left")

        # Streams summary with today's counts (fired / answered / expired).
        config = engine.db.latest_config()
        streams = [s for s in (config or {}).get("streams", []) if s.get("enabled", True)]
        box = ttk.Frame(self)
        box.pack(fill="x", pady=(4, 8))
        ttk.Label(box, text="Streams", font=theme.FONT_BOLD).pack(anchor="w")
        if not streams:
            ttk.Label(
                box,
                text="No streams yet - create one under Streams.",
                foreground=self.app.colors["muted"],
            ).pack(anchor="w")
        for stream in streams:
            fired, answered, expired = self._today_counts(stream["id"], now)
            ttk.Label(
                box,
                text=(
                    f"  {stream['name']}  -  today: {fired} fired,"
                    f" {answered} answered, {expired} expired"
                ),
            ).pack(anchor="w")

        # Sync status line.
        last_sync = engine.db.kv_get("sync_meta", "last_sync")
        sync_row = ttk.Frame(self)
        sync_row.pack(fill="x", pady=(0, 8))
        ttk.Label(
            sync_row,
            text=f"Last sync: {last_sync or 'never'}",
            foreground=self.app.colors["muted"],
        ).pack(side="left")
        ttk.Button(sync_row, text="Sync now", command=self.app.sync_async).pack(
            side="left", padx=8
        )

        # Take what this build understands and say what it cannot, rather than
        # letting a stream that the client cannot compute look like a stream
        # that simply went quiet (Tier 3 charter C5 F2/F6).
        issues = self.app.engine.config_issues(now)
        if issues:
            ttk.Label(
                self,
                text=(
                    "Config problems on this device - the rest of the config"
                    " still runs:\n• " + "\n• ".join(issues)
                ),
                foreground=self.app.colors["warn_fg"],
                justify="left",
                wraplength=520,
            ).pack(anchor="w", pady=(0, 6))

        self._calendar(now)

    # -- helpers ----------------------------------------------------------

    def _stream_name(self, stream_id: str) -> str:
        stream = self.app.engine.stream_config(stream_id, self.app.engine.clock.now())
        return stream["name"] if stream else stream_id

    def _snooze(self, sample: str) -> None:
        refused = self.app.engine.snooze(sample)
        if refused:
            self.app.set_status(f"Snooze refused: {refused}")
        self.refresh()

    def _skip(self, sample: str) -> None:
        self.app.engine.skip(sample)
        self.refresh()

    def _set_quiet(self, value) -> None:
        self.app.engine.set_quiet(value)
        self.refresh()

    def _quiet_for(self) -> None:
        raw = simpledialog.askstring("Quiet mode", "Duration (H:MM):", parent=self)
        if not raw:
            return
        try:
            hours, minutes = raw.split(":")
            seconds = int(hours) * 3600 + int(minutes) * 60
        except ValueError:
            self.app.set_status("Enter a duration like 1:30")
            return
        self.app.engine.set_quiet(fmt_utc(self.app.engine.clock.now() + seconds))
        self.refresh()

    def _tz(self):
        config = self.app.engine.db.latest_config()
        return ZoneInfo(config["timezone"]) if config else ZoneInfo("UTC")

    def _today_counts(self, stream_id: str, now: int) -> tuple[int, int, int]:
        tz = self._tz()
        today = datetime.fromtimestamp(now, UTC).astimezone(tz).date()
        fired = answered = expired = 0
        for row in self.app.engine.db.sample_rows(stream=stream_id):
            local_day = (
                datetime.fromtimestamp(parse_utc(row["scheduled_utc"]), UTC)
                .astimezone(tz)
                .date()
            )
            if local_day != today:
                continue
            if row.get("observed"):
                fired += 1
            if row["status"] == "answered":
                answered += 1
            elif row["status"] == "expired":
                expired += 1
        return fired, answered, expired

    def _calendar(self, now: int) -> None:
        """Last 8 weeks, one cell per day, colored by answer rate."""
        tz = self._tz()
        today = datetime.fromtimestamp(now, UTC).astimezone(tz).date()
        start = today - timedelta(days=today.weekday() + 7 * 7)  # 8 week rows
        per_day: dict[date, list[str]] = {}
        for row in self.app.engine.db.sample_rows():
            day = (
                datetime.fromtimestamp(parse_utc(row["scheduled_utc"]), UTC)
                .astimezone(tz)
                .date()
            )
            per_day.setdefault(day, []).append(row["status"])

        ttk.Label(self, text="Last 8 weeks", font=theme.FONT_BOLD).pack(anchor="w")
        cell, pad = 14, 3
        canvas = tk.Canvas(
            self,
            width=7 * (cell + pad) + pad,
            height=8 * (cell + pad) + pad,
            bg=self.app.colors["bg"],
            highlightthickness=0,
        )
        canvas.pack(anchor="w", pady=4)
        day = start
        for week in range(8):
            for weekday in range(7):
                statuses = per_day.get(day, [])
                answerable = [s for s in statuses if s not in ("suppressed", "retracted")]
                if not statuses:
                    color = self.app.colors["card"]
                elif not answerable:
                    color = theme.STATUS_COLORS["suppressed"]
                else:
                    rate = sum(s == "answered" for s in answerable) / len(answerable)
                    color = ("#c8e6c9", "#81c784", "#4caf50", "#2e7d32")[
                        min(3, int(rate * 4))
                    ]
                x = pad + weekday * (cell + pad)
                y = pad + week * (cell + pad)
                outline = self.app.colors["accent"] if day == today else ""
                canvas.create_rectangle(
                    x, y, x + cell, y + cell, fill=color, outline=outline
                )
                day += timedelta(days=1)
