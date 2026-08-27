"""Answer window (spec §10.3–10.4): one scrolling page, keyboard-first,
no animations. Submit writes locally and closes instantly.

Late samples (opened from Backlog/History) show the LATE banner and repeat
the original time next to Submit; Snooze/Skip appear only while active.
"""

from __future__ import annotations

import tkinter as tk
from datetime import UTC, datetime
from tkinter import ttk
from zoneinfo import ZoneInfo

from ..core.quick import presented_fields
from ..core.timeutil import parse_utc
from . import theme
from .widgets import ScrollFrame, TagEntry


class FieldWidget:
    """One rendered survey field; knows how to read and validate itself."""

    def __init__(self, field: dict):
        self.field = field
        self.error_label: ttk.Label | None = None

    def value(self):  # None = unanswered
        raise NotImplementedError

    def validate(self) -> str | None:
        value = self.value()
        if value is None and self.field.get("required"):
            return "required"
        return self.check(value) if value is not None else None

    def check(self, value) -> str | None:
        return None

    def focus_target(self) -> tk.Widget:
        raise NotImplementedError

    def prefill(self, value) -> None:  # editing an existing answer
        pass


class TextField(FieldWidget):
    def __init__(self, parent, field):
        super().__init__(field)
        if field.get("multiline"):
            self.widget = tk.Text(parent, height=4, wrap="word", undo=True)
        else:
            self.widget = ttk.Entry(parent)
        self.widget.pack(fill="x")

    def value(self):
        if isinstance(self.widget, tk.Text):
            text = self.widget.get("1.0", "end-1c")
        else:
            text = self.widget.get()
        return text if text.strip() else None

    def prefill(self, value) -> None:
        if isinstance(self.widget, tk.Text):
            self.widget.insert("1.0", str(value))
        else:
            self.widget.insert(0, str(value))

    def check(self, value):
        max_len = self.field.get("max_len")
        if max_len and len(value) > max_len:
            return f"too long ({len(value)}/{max_len})"
        return None

    def focus_target(self):
        return self.widget


class NumberField(FieldWidget):
    def __init__(self, parent, field):
        super().__init__(field)
        self.var = tk.StringVar()
        lo, hi = field.get("min"), field.get("max")
        if field.get("display") == "slider" and lo is not None and hi is not None:
            row = ttk.Frame(parent)
            row.pack(fill="x")
            labels = field.get("end_labels") or [str(lo), str(hi)]
            ttk.Label(row, text=labels[0]).pack(side="left")
            resolution = 1 if field.get("integer") else (hi - lo) / 100
            self.scale_var = tk.DoubleVar(value=lo)
            self.touched = False
            self.widget = tk.Scale(
                row,
                from_=lo,
                to=hi,
                orient="horizontal",
                resolution=resolution,
                variable=self.scale_var,
                showvalue=True,
                command=lambda _v: setattr(self, "touched", True),
            )
            self.widget.pack(side="left", fill="x", expand=True, padx=6)
            ttk.Label(row, text=labels[1]).pack(side="left")
            self.entry = None
        else:
            self.entry = ttk.Entry(parent, textvariable=self.var, width=12)
            self.entry.pack(anchor="w")
            self.widget = self.entry

    def value(self):
        if self.entry is None:
            if not self.touched and not self.field.get("required"):
                return None
            raw = self.scale_var.get()
            return int(raw) if self.field.get("integer") else raw
        text = self.var.get().strip()
        if not text:
            return None
        try:
            return int(text) if self.field.get("integer") else float(text)
        except ValueError:
            return text  # caught by check()

    def prefill(self, value) -> None:
        if self.entry is None:
            self.scale_var.set(value)
            self.touched = True
        else:
            self.var.set(str(value))

    def check(self, value):
        if isinstance(value, str):
            return "not a whole number" if self.field.get("integer") else "not a number"
        lo, hi = self.field.get("min"), self.field.get("max")
        if lo is not None and value < lo:
            return f"minimum {lo}"
        if hi is not None and value > hi:
            return f"maximum {hi}"
        return None

    def focus_target(self):
        return self.widget


class TagsField(FieldWidget):
    def __init__(self, parent, field, survey_id, suggest_fn):
        super().__init__(field)
        vocab = field.get("vocab") or f"{survey_id}.{field['id']}"
        self.widget = TagEntry(
            parent,
            suggest=lambda prefix: suggest_fn(vocab, prefix),
            curated=field.get("curated"),
        )
        self.widget.pack(fill="x")

    def value(self):
        tags = self.widget.get()
        return tags or None

    def prefill(self, value) -> None:
        self.widget.set(list(value))

    def check(self, _value):
        return self.widget.errors()

    def focus_target(self):
        return self.widget.entry


class ChoiceField(FieldWidget):
    def __init__(self, parent, field):
        super().__init__(field)
        self.options = [
            o if isinstance(o, dict) else {"value": o} for o in field["options"]
        ]
        self.multi = field.get("cardinality") == "multi"
        display = field.get("display") or ("checkbox" if self.multi else "radio")
        box = ttk.Frame(parent)
        box.pack(fill="x")
        self.first_widget: tk.Widget = box
        if display == "dropdown":
            self.var = tk.StringVar()
            combo = ttk.Combobox(
                box,
                textvariable=self.var,
                state="readonly",
                values=[""] + [o["value"] for o in self.options],
            )
            combo.pack(anchor="w")
            self.first_widget = combo
            self.vars = None
        elif self.multi:
            self.vars = {}
            side = "left" if display == "chips" else "top"
            for opt in self.options:
                var = tk.BooleanVar()
                check = ttk.Checkbutton(
                    box, text=opt.get("label", opt["value"]), variable=var
                )
                check.pack(side=side, anchor="w", padx=(0, 8))
                self.vars[opt["value"]] = var
                if self.first_widget is box:
                    self.first_widget = check
        else:
            self.var = tk.StringVar()
            side = "left" if display in ("chips", "yesno") else "top"
            for opt in self.options:
                radio = ttk.Radiobutton(
                    box,
                    text=opt.get("label", opt["value"]),
                    variable=self.var,
                    value=opt["value"],
                )
                radio.pack(side=side, anchor="w", padx=(0, 8))
                if self.first_widget is box:
                    self.first_widget = radio
            self.vars = None

    def value(self):
        if self.vars is not None:
            chosen = [v for v, var in self.vars.items() if var.get()]
            return chosen or None
        return self.var.get() or None

    def prefill(self, value) -> None:
        if self.vars is not None:
            for v in value if isinstance(value, list) else [value]:
                if v in self.vars:
                    self.vars[v].set(True)
        else:
            self.var.set(value[0] if isinstance(value, list) else value)

    def focus_target(self):
        return self.first_widget


def make_field_widget(parent, field, survey_id, suggest_fn) -> FieldWidget:
    kind = field["type"]
    if kind == "text":
        return TextField(parent, field)
    if kind == "number":
        return NumberField(parent, field)
    if kind == "tags":
        return TagsField(parent, field, survey_id, suggest_fn)
    if kind == "choice":
        return ChoiceField(parent, field)
    raise ValueError(f"unknown field type {kind}")


class AnswerWindow(tk.Toplevel):
    def __init__(self, app, sample_id: str, supersedes: str | None = None, prefill: dict | None = None):
        super().__init__(app.root)
        self.app = app
        self.engine = app.engine
        self.sample_id = sample_id
        self.supersedes = supersedes
        self.prefill_answers = prefill or {}
        stream_id, scheduled_iso = sample_id.split("|", 1)
        self.scheduled = parse_utc(scheduled_iso)
        stream = (
            self.engine.stream_config(stream_id, self.scheduled)
            or self.engine.stream_config(stream_id, self.engine.clock.now())
            or {}
        )
        self.stream_name = stream.get("name", stream_id)
        ref = stream.get("survey", {})
        self.survey = self.engine.db.survey(ref.get("id", ""), ref.get("version", 0))
        self.widgets: list[FieldWidget] = []
        self.title(f"Answer - {self.stream_name}")
        self.geometry("560x640")

        now = self.engine.clock.now()
        settings = self.engine.effective_settings(stream_id, self.scheduled)
        self.late = now > self.scheduled + settings["expiry_minutes"] * 60

        self._build(now)
        self.bind("<Control-Return>", lambda e: self.submit())
        self.bind("<Escape>", lambda e: self.destroy())

    def _local_str(self, epoch: int) -> str:
        config = self.engine.config_at(epoch) or {"timezone": "UTC"}
        tz = ZoneInfo(config["timezone"])
        return datetime.fromtimestamp(epoch, UTC).astimezone(tz).strftime(
            "%a %Y-%m-%d %H:%M"
        )

    def _build(self, now: int) -> None:
        colors = self.app.colors
        header = ttk.Frame(self, padding=(12, 10, 12, 4))
        header.pack(fill="x")
        ttk.Label(header, text=self.stream_name, font=theme.FONT_BIG).pack(anchor="w")
        ttk.Label(
            header,
            text=f"Scheduled {self._local_str(self.scheduled)}",
            font=theme.FONT_TIME,
        ).pack(anchor="w")

        if self.late:
            ago_h = (now - self.scheduled) / 3600
            ago = f"{ago_h:.1f} h ago" if ago_h < 48 else f"{ago_h / 24:.0f} days ago"
            banner = tk.Label(
                self,
                text=(
                    f"LATE - originally {self._local_str(self.scheduled)}, {ago}."
                    " This answer will be marked late."
                ),
                bg=colors["warn_bg"],
                fg=colors["warn_fg"],
                anchor="w",
                padx=12,
                pady=6,
            )
            banner.pack(fill="x")
        else:
            actions = ttk.Frame(self, padding=(12, 0))
            actions.pack(fill="x")
            ttk.Button(actions, text="Snooze", command=self._snooze).pack(side="left")
            ttk.Button(actions, text="Skip", command=self._skip).pack(
                side="left", padx=6
            )

        page = ScrollFrame(self)
        page.pack(fill="both", expand=True, padx=4)
        body = page.inner

        if self.survey is None:
            ttk.Label(body, text="Survey not found for this stream.").pack(pady=20)
            return

        full = self.engine.is_full_survey(self.sample_id) or self.supersedes is not None
        fields = presented_fields(self.survey, full)
        self.widgets: list[FieldWidget] = []
        for field in fields:
            block = ttk.Frame(body, padding=(10, 8, 10, 0))
            block.pack(fill="x")
            label = field.get("label", field["id"])
            if field.get("required"):
                label += " *"
            ttk.Label(block, text=label, font=theme.FONT_BOLD).pack(anchor="w")
            if field.get("help"):
                ttk.Label(block, text=field["help"], foreground=self.app.colors["muted"]).pack(
                    anchor="w"
                )
            widget = make_field_widget(
                block, field, self.survey["id"], self.engine.db.suggest_tags
            )
            widget.error_label = ttk.Label(block, text="", foreground="#c62828")
            widget.error_label.pack(anchor="w")
            existing = self.prefill_answers.get(field["id"])
            if existing is not None:
                widget.prefill(existing)
            self.widgets.append(widget)

        footer = ttk.Frame(self, padding=12)
        footer.pack(fill="x")
        ttk.Label(
            footer,
            text=f"for ping at {self._local_str(self.scheduled)}",
            foreground=self.app.colors["muted"],
        ).pack(side="left")
        submit = ttk.Button(footer, text="Submit", command=self.submit)
        submit.pack(side="right")

        if self.widgets:
            first = self.widgets[0].focus_target()
            first.focus_set()
            last = self.widgets[-1].focus_target()
            last.bind("<Return>", lambda e: self.submit())

    def _snooze(self) -> None:
        refused = self.engine.snooze(self.sample_id)
        if refused == "near_expiry":
            self.app.set_status("Snooze refused: too close to expiry")
        elif refused == "max_snoozes":
            self.app.set_status("Snooze refused: snooze limit reached")
        else:
            self.app.set_status("Snoozed")
            self.destroy()
        self.app.refresh()

    def _skip(self) -> None:
        self.engine.skip(self.sample_id)
        self.app.set_status("Skipped")
        self.destroy()
        self.app.refresh()

    def submit(self) -> None:
        answers = {}
        ok = True
        for widget in self.widgets:
            error = widget.validate()
            widget.error_label.configure(text=error or "")
            if error:
                ok = False
                continue
            value = widget.value()
            if value is not None:
                answers[widget.field["id"]] = value
        if not ok:
            return
        self.engine.answer(self.sample_id, answers, supersedes=self.supersedes)
        self.app.set_status("Answer saved")
        self.destroy()
        self.app.refresh()
        self.app.show_next_queued()
