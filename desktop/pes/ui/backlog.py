"""Backlog screen (spec §10.2, §10.4): recent unanswered samples, grouped by
stream, original time in large type. Late answers happen only from here or
History, never from Home's active card."""

from __future__ import annotations

from tkinter import ttk

from ..core.timeutil import parse_utc
from . import theme
from .widgets import ScrollFrame


class BacklogScreen(ttk.Frame):
    def __init__(self, parent, app):
        super().__init__(parent, padding=10)
        self.app = app

    def refresh(self) -> None:
        for child in self.winfo_children():
            child.destroy()
        ttk.Label(
            self,
            text="These pings have expired. Answers will be marked late.",
            font=theme.FONT_BOLD,
        ).pack(anchor="w", pady=(0, 8))

        rows = self.app.engine.backlog()
        if not rows:
            ttk.Label(self, text="Backlog is empty.", foreground=self.app.colors["muted"]).pack(
                anchor="w"
            )
            return

        page = ScrollFrame(self)
        page.pack(fill="both", expand=True)
        by_stream: dict[str, list[dict]] = {}
        for row in rows:
            by_stream.setdefault(row["stream"], []).append(row)

        for stream_id, group in sorted(by_stream.items()):
            stream = self.app.engine.stream_config(
                stream_id, self.app.engine.clock.now()
            )
            name = stream["name"] if stream else stream_id
            ttk.Label(page.inner, text=name, font=theme.FONT_BOLD).pack(
                anchor="w", pady=(8, 2)
            )
            for row in group:
                card = ttk.Frame(page.inner, style="Card.TFrame", padding=8)
                card.pack(fill="x", pady=2)
                when = self.app.local_str(parse_utc(row["scheduled_utc"]))
                ttk.Label(card, text=when, font=theme.FONT_TIME, style="Card.TLabel").pack(
                    side="left"
                )
                ttk.Label(
                    card,
                    text=row["status"],
                    foreground=theme.STATUS_COLORS.get(row["status"], "#000"),
                    style="Card.TLabel",
                ).pack(side="left", padx=10)
                sample = row["sample"]
                ttk.Button(
                    card, text="Answer", command=lambda s=sample: self.app.open_answer(s)
                ).pack(side="right")
