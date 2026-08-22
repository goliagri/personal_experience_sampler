"""Shared config-editing helpers: effective_from choice (§8.1) and staging a
new config version from edited pieces."""

from __future__ import annotations

import tkinter as tk
from datetime import UTC, datetime, timedelta
from tkinter import ttk
from zoneinfo import ZoneInfo

from ..core.timeutil import fmt_utc, parse_utc


def next_top_of_hour(now: int) -> int:
    """Default effective_from: next top-of-hour >= now + 5 min (§8.1)."""
    floor = now + 5 * 60
    return floor + (-floor) % 3600


def next_local_midnight(now: int, timezone: str) -> int:
    tz = ZoneInfo(timezone)
    local = datetime.fromtimestamp(now, UTC).astimezone(tz)
    midnight = datetime.combine(local.date() + timedelta(days=1), datetime.min.time())
    return int(midnight.replace(tzinfo=tz, fold=0).timestamp())


def ask_effective_from(parent, now: int, timezone: str) -> str | None:
    """Modal choice: next hour / next midnight / custom UTC instant."""
    dialog = tk.Toplevel(parent)
    dialog.title("Apply from…")
    dialog.transient(parent.winfo_toplevel())
    dialog.grab_set()
    choice = tk.StringVar(value="hour")
    hour = fmt_utc(next_top_of_hour(now))
    midnight = fmt_utc(next_local_midnight(now, timezone))
    ttk.Radiobutton(
        dialog, text=f"Next hour ({hour})", variable=choice, value="hour"
    ).pack(anchor="w", padx=12, pady=(12, 2))
    ttk.Radiobutton(
        dialog, text=f"Next local midnight ({midnight})", variable=choice, value="midnight"
    ).pack(anchor="w", padx=12, pady=2)
    row = ttk.Frame(dialog)
    row.pack(anchor="w", padx=12, pady=2)
    ttk.Radiobutton(row, text="Custom (UTC):", variable=choice, value="custom").pack(
        side="left"
    )
    custom = ttk.Entry(row, width=22)
    custom.insert(0, hour)
    custom.pack(side="left", padx=4)

    result: list[str | None] = [None]

    def confirm() -> None:
        value = {"hour": hour, "midnight": midnight}.get(choice.get(), custom.get().strip())
        try:
            if parse_utc(value) < now:
                raise ValueError
        except ValueError:
            error.configure(text="Must be a future UTC time like 2026-08-21T15:00:00Z")
            return
        result[0] = value
        dialog.destroy()

    error = ttk.Label(dialog, text="", foreground="#c62828")
    error.pack(anchor="w", padx=12)
    buttons = ttk.Frame(dialog)
    buttons.pack(pady=10)
    ttk.Button(buttons, text="Apply", command=confirm).pack(side="left", padx=4)
    ttk.Button(buttons, text="Cancel", command=dialog.destroy).pack(side="left")
    parent.wait_window(dialog)
    return result[0]


def stage_config_change(
    app,
    parent,
    streams: list[dict] | None = None,
    defaults: dict | None = None,
    timezone: str | None = None,
) -> bool:
    """Ask for effective_from, then stage a new config version. Returns
    success; validation errors go to the status line."""
    engine = app.engine
    current = engine.db.latest_config() or {}
    timezone = timezone or current.get("timezone", "UTC")
    effective = ask_effective_from(parent, engine.clock.now(), timezone)
    if effective is None:
        return False
    errors = engine.stage_new_config(
        streams=streams if streams is not None else current.get("streams", []),
        defaults=defaults if defaults is not None else current.get("defaults", {}),
        timezone=timezone,
        effective_from=effective,
    )
    if errors:
        app.set_status("Config invalid: " + ", ".join(errors))
        return False
    app.set_status(f"Config v{engine.db.latest_config()['version']} staged; effective {effective}")
    app.sync_async()
    return True
