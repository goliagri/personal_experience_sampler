"""Streams screen and editor (spec §10.2): list, form editor with per-protocol
params, dry-run preview, test ping."""

from __future__ import annotations

import secrets
import tkinter as tk
from datetime import UTC, datetime, timedelta
from tkinter import ttk
from zoneinfo import ZoneInfo

from ..core.scheduler import resolve_day
from . import theme
from .configedit import stage_config_change

PROTOCOL_PARAMS = {
    "poisson": [("mean_gap_minutes", True), ("min_gap_minutes", False)],
    "stratified": [("interval_minutes", True), ("pings_per_interval", True)],
    "fixed_interval": [("every_minutes", True), ("anchor_local", True)],
    "fixed_times": [("times_local", True), ("days", False)],
}


class StreamsScreen(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app

    def refresh(self) -> None:
        for child in self.winfo_children():
            child.destroy()
        config = self.app.engine.db.latest_config() or {"streams": []}
        ttk.Label(self, text="Streams", font=theme.FONT_BIG).pack(anchor="w")
        for stream in config["streams"]:
            row = ttk.Frame(self, style="Card.TFrame", padding=8)
            row.pack(fill="x", pady=3)
            protocol = stream["protocol"]
            summary = protocol["type"] + " " + ", ".join(
                f"{k}={v}" for k, v in protocol.items() if k != "type"
            )
            state = "" if stream.get("enabled", True) else "  [off]"
            ttk.Label(
                row, text=f"{stream['name']}{state}", font=theme.FONT_BOLD, style="Card.TLabel"
            ).pack(side="left")
            ttk.Label(row, text=summary, style="Card.TLabel").pack(side="left", padx=10)
            ttk.Button(
                row, text="Edit", command=lambda s=stream: StreamEditor(self.app, s)
            ).pack(side="right")
            ttk.Button(
                row,
                text="Fire test ping",
                command=lambda s=stream: self._test_ping(s["id"]),
            ).pack(side="right", padx=4)
        ttk.Button(self, text="New stream...", command=lambda: StreamEditor(self.app, None)).pack(
            anchor="w", pady=8
        )

    def _test_ping(self, stream_id: str) -> None:
        sample = self.app.engine.fire_test_ping(stream_id)
        self.app.set_status(f"Test ping fired: {sample}")


class StreamEditor(tk.Toplevel):
    def __init__(self, app, stream: dict | None):
        super().__init__(app.root)
        self.app = app
        self.existing = stream
        self.title("Edit stream" if stream else "New stream")
        self.geometry("520x640")
        stream = stream or {}

        form = ttk.Frame(self, padding=12)
        form.pack(fill="both", expand=True)
        self.vars: dict[str, tk.Variable] = {}

        def entry(label: str, key: str, value, width=32) -> ttk.Entry:
            row = ttk.Frame(form)
            row.pack(fill="x", pady=3)
            ttk.Label(row, text=label, width=18, anchor="w").pack(side="left")
            var = tk.StringVar(value="" if value is None else str(value))
            self.vars[key] = var
            widget = ttk.Entry(row, textvariable=var, width=width)
            widget.pack(side="left", fill="x", expand=True)
            return widget

        id_entry = entry("Id (slug)", "id", stream.get("id", ""))
        if self.existing:
            id_entry.configure(state="disabled")
        entry("Name", "name", stream.get("name", ""))

        enabled_var = tk.BooleanVar(value=stream.get("enabled", True))
        self.vars["enabled"] = enabled_var
        ttk.Checkbutton(form, text="Enabled", variable=enabled_var).pack(anchor="w")

        seed_row = ttk.Frame(form)
        seed_row.pack(fill="x", pady=3)
        ttk.Label(seed_row, text="Seed", width=18, anchor="w").pack(side="left")
        seed_var = tk.StringVar(value=stream.get("seed") or secrets.token_hex(16))
        self.vars["seed"] = seed_var
        ttk.Entry(seed_row, textvariable=seed_var, width=36).pack(side="left")
        ttk.Button(
            seed_row,
            text="New",
            command=lambda: seed_var.set(secrets.token_hex(16)),
            width=5,
        ).pack(side="left", padx=4)

        # Protocol + params.
        protocol = stream.get("protocol", {"type": "poisson"})
        proto_row = ttk.Frame(form)
        proto_row.pack(fill="x", pady=(8, 3))
        ttk.Label(proto_row, text="Protocol", width=18, anchor="w").pack(side="left")
        self.proto_var = tk.StringVar(value=protocol["type"])
        box = ttk.Combobox(
            proto_row,
            textvariable=self.proto_var,
            state="readonly",
            values=list(PROTOCOL_PARAMS),
        )
        box.pack(side="left")
        box.bind("<<ComboboxSelected>>", lambda e: self._render_params({}))
        self.params_frame = ttk.Frame(form)
        self.params_frame.pack(fill="x")
        self._render_params(protocol)

        # Quiet zones: one per line, "mon,tue,wed 23:00-07:30".
        ttk.Label(form, text="Quiet zones (days HH:MM-HH:MM, one per line)").pack(
            anchor="w", pady=(8, 2)
        )
        self.zones_text = tk.Text(form, height=3)
        self.zones_text.pack(fill="x")
        for zone in stream.get("quiet_zones", []):
            self.zones_text.insert(
                "end", f"{','.join(zone['days'])} {zone['from']}-{zone['to']}\n"
            )

        survey_row = ttk.Frame(form)
        survey_row.pack(fill="x", pady=(8, 3))
        ttk.Label(survey_row, text="Survey id@version", width=18, anchor="w").pack(side="left")
        surveys = sorted(self.app.engine.db.all_surveys())
        ref = stream.get("survey", {})
        current = f"{ref.get('id')}@{ref.get('version')}" if ref else ""
        self.survey_var = tk.StringVar(value=current)
        ttk.Combobox(
            survey_row,
            textvariable=self.survey_var,
            values=[f"{sid}@{v}" for sid, v in surveys],
        ).pack(side="left")

        entry("Full survey every n", "full_survey_every_n", stream.get("full_survey_every_n", 1))
        overrides = stream.get("overrides", {})
        entry("Override expiry min", "ov_expiry", overrides.get("expiry_minutes"))
        entry("Override snooze min", "ov_snooze", overrides.get("snooze_minutes"))
        entry("Override max snoozes", "ov_max_snoozes", overrides.get("max_snoozes"))

        self.error = ttk.Label(form, text="", foreground="#c62828", wraplength=480)
        self.error.pack(anchor="w", pady=4)

        buttons = ttk.Frame(form)
        buttons.pack(fill="x", pady=8)
        ttk.Button(buttons, text="Save", command=self._save).pack(side="left")
        ttk.Button(buttons, text="Preview next 24 h", command=self._preview).pack(
            side="left", padx=6
        )
        ttk.Button(buttons, text="Cancel", command=self.destroy).pack(side="right")

    # -- protocol params --------------------------------------------------

    def _render_params(self, values: dict) -> None:
        for child in self.params_frame.winfo_children():
            child.destroy()
        self.param_vars: dict[str, tk.StringVar] = {}
        for key, required in PROTOCOL_PARAMS[self.proto_var.get()]:
            row = ttk.Frame(self.params_frame)
            row.pack(fill="x", pady=2)
            label = key + ("" if required else " (opt.)")
            ttk.Label(row, text=label, width=22, anchor="w").pack(side="left", padx=(20, 0))
            raw = values.get(key)
            if isinstance(raw, list):
                raw = ",".join(str(v) for v in raw)
            var = tk.StringVar(value="" if raw is None else str(raw))
            self.param_vars[key] = var
            ttk.Entry(row, textvariable=var, width=20).pack(side="left")

    def _protocol_doc(self) -> dict:
        doc: dict = {"type": self.proto_var.get()}
        for key, var in self.param_vars.items():
            raw = var.get().strip()
            if not raw:
                continue
            if key in ("times_local", "days"):
                doc[key] = [part.strip() for part in raw.split(",") if part.strip()]
            elif key == "anchor_local":
                doc[key] = raw
            else:
                try:
                    doc[key] = int(raw) if float(raw) == int(float(raw)) else float(raw)
                except ValueError:
                    doc[key] = raw  # validation reports it
        return doc

    # -- assemble / save --------------------------------------------------

    def _zones(self) -> list[dict]:
        zones = []
        for line in self.zones_text.get("1.0", "end").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                days_part, window = line.split()
                start, end = window.split("-")
            except ValueError:
                zones.append({"days": [], "from": line, "to": ""})  # invalid; caught
                continue
            zones.append(
                {"days": days_part.split(","), "from": start, "to": end}
            )
        return zones

    def _stream_doc(self) -> dict:
        survey_ref = {}
        if "@" in self.survey_var.get():
            sid, _, version = self.survey_var.get().partition("@")
            try:
                survey_ref = {"id": sid, "version": int(version)}
            except ValueError:
                survey_ref = {"id": sid, "version": version}
        doc = {
            "id": self.vars["id"].get().strip(),
            "name": self.vars["name"].get().strip(),
            "enabled": bool(self.vars["enabled"].get()),
            "seed": self.vars["seed"].get().strip(),
            "protocol": self._protocol_doc(),
            "quiet_zones": self._zones(),
            "survey": survey_ref,
        }
        try:
            n = int(self.vars["full_survey_every_n"].get())
        except ValueError:
            n = self.vars["full_survey_every_n"].get()
        doc["full_survey_every_n"] = n
        overrides = {}
        for key, name in (
            ("ov_expiry", "expiry_minutes"),
            ("ov_snooze", "snooze_minutes"),
            ("ov_max_snoozes", "max_snoozes"),
        ):
            raw = self.vars[key].get().strip()
            if raw:
                try:
                    overrides[name] = int(raw)
                except ValueError:
                    overrides[name] = raw
        if overrides:
            doc["overrides"] = overrides
        return doc

    def _save(self) -> None:
        doc = self._stream_doc()
        config = self.app.engine.db.latest_config() or {"streams": []}
        streams = [dict(s) for s in config["streams"]]
        for i, existing in enumerate(streams):
            if existing["id"] == doc["id"]:
                streams[i] = doc
                break
        else:
            streams.append(doc)
        if stage_config_change(self.app, self, streams=streams):
            self.destroy()
            self.app.refresh()

    # -- dry-run preview (not persisted) ----------------------------------

    def _preview(self) -> None:
        doc = self._stream_doc()
        config = self.app.engine.db.latest_config()
        if config is None:
            self.error.configure(text="No config yet.")
            return
        trial = {**config, "streams": [doc], "effective_from": "1970-01-01T00:00:00Z"}
        tz = ZoneInfo(config["timezone"])
        now = self.app.engine.clock.now()
        today = datetime.fromtimestamp(now, UTC).astimezone(tz).date()
        lines = []
        try:
            for day in (today, today + timedelta(days=1)):
                for r in resolve_day([trial], doc["id"], day):
                    if now <= r.scheduled_utc < now + 24 * 3600:
                        mark = f"  [{r.suppressed_reason}]" if r.suppressed_reason else ""
                        lines.append(self.app.local_str(r.scheduled_utc) + mark)
        except Exception as exc:  # noqa: BLE001 - any bad param should show, not crash
            self.error.configure(text=f"Preview failed: {exc}")
            return
        window = tk.Toplevel(self)
        window.title("Next 24 h (dry run)")
        text = tk.Text(window, width=44, height=min(30, max(6, len(lines) + 2)))
        text.pack(fill="both", expand=True)
        text.insert("1.0", "\n".join(lines) or "No pings in the next 24 h.")
        text.configure(state="disabled")
