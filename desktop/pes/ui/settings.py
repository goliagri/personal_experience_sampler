"""Settings screen (spec §10.2): timezone, defaults, device name, theme,
"Show schedule" (deliberate action; next-ping times are otherwise hidden)."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from zoneinfo import ZoneInfo

from ..core.timeutil import parse_utc
from . import theme
from .configedit import stage_config_change


class SettingsScreen(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app

    def refresh(self) -> None:
        for child in self.winfo_children():
            child.destroy()
        engine = self.app.engine
        config = engine.db.latest_config() or {}
        defaults = config.get("defaults", {})

        ttk.Label(self, text="Settings", font=theme.FONT_BIG).pack(anchor="w", pady=(0, 8))

        self.vars: dict[str, tk.StringVar] = {}

        def entry(label: str, key: str, value) -> None:
            row = ttk.Frame(self)
            row.pack(fill="x", pady=2)
            ttk.Label(row, text=label, width=24, anchor="w").pack(side="left")
            var = tk.StringVar(value="" if value is None else str(value))
            self.vars[key] = var
            ttk.Entry(row, textvariable=var, width=28).pack(side="left")

        entry("Timezone", "timezone", config.get("timezone", "UTC"))
        entry("Default snooze (min)", "snooze_minutes", defaults.get("snooze_minutes", 10))
        entry("Default max snoozes", "max_snoozes", defaults.get("max_snoozes", 3))
        entry("Default expiry (min)", "expiry_minutes", defaults.get("expiry_minutes", 60))
        entry("Backlog window (h)", "backlog_hours", defaults.get("backlog_hours", 12))
        ttk.Button(self, text="Save (new config version)", command=self._save_config).pack(
            anchor="w", pady=6
        )

        ttk.Separator(self).pack(fill="x", pady=8)

        name_row = ttk.Frame(self)
        name_row.pack(fill="x", pady=2)
        ttk.Label(name_row, text="Device name", width=24, anchor="w").pack(side="left")
        self.name_var = tk.StringVar(
            value=engine.db.kv_get("device", "name") or engine.device_id
        )
        ttk.Entry(name_row, textvariable=self.name_var, width=28).pack(side="left")
        ttk.Button(name_row, text="Set", command=self._save_name).pack(side="left", padx=4)
        ttk.Label(
            self,
            text=f"Device id: {engine.device_id}   |   role: "
            f"{engine.db.kv_get('device', 'role') or '(unset)'}",
            foreground=self.app.colors["muted"],
        ).pack(anchor="w", pady=2)

        theme_row = ttk.Frame(self)
        theme_row.pack(fill="x", pady=2)
        ttk.Label(theme_row, text="Theme", width=24, anchor="w").pack(side="left")
        self.theme_var = tk.StringVar(value=self.app.theme_name)
        box = ttk.Combobox(
            theme_row,
            textvariable=self.theme_var,
            state="readonly",
            values=["light", "dark"],
            width=10,
        )
        box.pack(side="left")
        box.bind("<<ComboboxSelected>>", lambda e: self._set_theme())

        ttk.Separator(self).pack(fill="x", pady=8)
        ttk.Label(
            self,
            text=f"Data: {self.app.data_dir}\nCloud folder: {self.app.cloud_dir}",
            foreground=self.app.colors["muted"],
        ).pack(anchor="w")

        ttk.Button(self, text="Show schedule (next 48 h)...", command=self._show_schedule).pack(
            anchor="w", pady=8
        )
        ttk.Label(
            self,
            text="Restore cloud from local cache: arrives with sync hardening"
            " (milestone 5).",
            foreground=self.app.colors["muted"],
        ).pack(anchor="w")

    def _save_config(self) -> None:
        timezone = self.vars["timezone"].get().strip()
        try:
            ZoneInfo(timezone)
        except (KeyError, ValueError, TypeError, OSError):
            self.app.set_status(f"Unknown timezone: {timezone}")
            return
        defaults = {"location": "off"}
        for key in ("snooze_minutes", "max_snoozes", "expiry_minutes", "backlog_hours"):
            try:
                defaults[key] = int(self.vars[key].get())
            except ValueError:
                self.app.set_status(f"{key} must be a whole number")
                return
        if stage_config_change(self.app, self, defaults=defaults, timezone=timezone):
            self.refresh()

    def _save_name(self) -> None:
        self.app.engine.db.kv_set("device", "name", self.name_var.get().strip())
        self.app.set_status("Device name saved (uploaded at next sync)")

    def _set_theme(self) -> None:
        self.app.db.kv_set("device", "theme", self.theme_var.get())
        self.app.apply_theme(self.theme_var.get())
        self.app.set_status(f"Theme: {self.theme_var.get()}")

    def _show_schedule(self) -> None:
        window = tk.Toplevel(self)
        window.title("Schedule - next 48 h")
        text = tk.Text(window, width=56, height=28)
        text.pack(fill="both", expand=True)
        rows = self.app.engine.db.conn.execute(
            "SELECT stream, scheduled_utc, suppressed_reason, state FROM schedule"
            " ORDER BY scheduled_utc"
        ).fetchall()
        lines = []
        for stream, scheduled, reason, state in rows:
            mark = f"  [{reason}]" if reason else ""
            lines.append(
                f"{self.app.local_str(parse_utc(scheduled))}  {stream}{mark}  ({state})"
            )
        text.insert("1.0", "\n".join(lines) or "Nothing scheduled.")
        text.configure(state="disabled")
