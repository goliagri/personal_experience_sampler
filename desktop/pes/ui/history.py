"""History screen (spec §10.2): all samples, filterable; answer late / edit /
retract from here."""

from __future__ import annotations

import json
import tkinter as tk
from tkinter import simpledialog, ttk

from ..core.timeutil import parse_utc
from . import theme

STATUSES = [
    "",
    "answered",
    "skipped",
    "expired",
    "suppressed",
    "unobserved",
    "pending",
    "retracted",
]


class HistoryScreen(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app
        self.stream_var = tk.StringVar()
        self.status_var = tk.StringVar()
        self._built = False

    def _build(self) -> None:
        bar = ttk.Frame(self)
        bar.pack(fill="x", pady=(0, 6))
        ttk.Label(bar, text="Stream:").pack(side="left")
        self.stream_box = ttk.Combobox(
            bar, textvariable=self.stream_var, state="readonly", width=16
        )
        self.stream_box.pack(side="left", padx=(2, 10))
        ttk.Label(bar, text="Status:").pack(side="left")
        status_box = ttk.Combobox(
            bar, textvariable=self.status_var, state="readonly", values=STATUSES, width=12
        )
        status_box.pack(side="left", padx=2)
        for box in (self.stream_box, status_box):
            box.bind("<<ComboboxSelected>>", lambda e: self.refresh())

        columns = ("time", "stream", "status", "late", "answers")
        self.tree = ttk.Treeview(self, columns=columns, show="headings", height=18)
        for col, width in zip(columns, (150, 90, 90, 40, 320)):
            self.tree.heading(col, text=col)
            self.tree.column(col, width=width, anchor="w")
        for status, color in theme.STATUS_COLORS.items():
            self.tree.tag_configure(status, foreground=color)
        self.tree.pack(fill="both", expand=True)
        self.tree.bind("<Double-1>", lambda e: self._view())

        actions = ttk.Frame(self)
        actions.pack(fill="x", pady=6)
        ttk.Button(actions, text="View", command=self._view).pack(side="left")
        ttk.Button(actions, text="Answer / edit", command=self._answer).pack(
            side="left", padx=6
        )
        ttk.Button(actions, text="Retract", command=self._retract).pack(side="left")
        self._built = True

    def refresh(self) -> None:
        if not self._built:
            self._build()
        config = self.app.engine.db.latest_config() or {"streams": []}
        self.stream_box.configure(
            values=[""] + [s["id"] for s in config["streams"]]
        )
        for item in self.tree.get_children():
            self.tree.delete(item)
        rows = self.app.engine.db.sample_rows(
            stream=self.stream_var.get() or None,
            statuses=[self.status_var.get()] if self.status_var.get() else None,
        )
        for row in reversed(rows):  # newest first
            answers = "; ".join(
                f"{k}={v}" for k, v in (row.get("answers") or {}).items()
            )
            status = row["status"]
            if status == "retracted":
                answers = f"[retracted] {answers}"
            self.tree.insert(
                "",
                "end",
                iid=row["sample"],
                values=(
                    self.app.local_str(parse_utc(row["scheduled_utc"])),
                    row["stream"],
                    status,
                    "late" if row.get("late") else "",
                    answers,
                ),
                tags=(status,),
            )

    def _selected(self) -> str | None:
        selection = self.tree.selection()
        return selection[0] if selection else None

    def _view(self) -> None:
        sample = self._selected()
        if not sample:
            return
        row = self.app.engine.db.sample_row(sample)
        window = tk.Toplevel(self)
        window.title(sample)
        text = tk.Text(window, width=70, height=24, wrap="word")
        text.pack(fill="both", expand=True)
        text.insert("1.0", json.dumps(row, indent=2))
        text.configure(state="disabled")

    def _answer(self) -> None:
        sample = self._selected()
        if not sample:
            return
        row = self.app.engine.db.sample_row(sample) or {}
        supersedes = None
        if row.get("status") == "answered":
            # Editing: supersede the winning chain's effective answer.
            events = [ev for _f, _l, ev in self.app.engine.db.events_for_sample(sample)]
            answered = [ev for ev in events if ev["ev"] == "answered"]
            if answered:
                supersedes = max(answered, key=lambda ev: ev["t"])["t"]
        self.app.open_answer(sample, supersedes=supersedes)

    def _retract(self) -> None:
        sample = self._selected()
        if not sample:
            return
        note = simpledialog.askstring(
            "Retract", "Optional note (why):", parent=self
        )
        self.app.engine.retract(sample, note or None)
        self.app.set_status(f"Retracted {sample}")
        self.refresh()
