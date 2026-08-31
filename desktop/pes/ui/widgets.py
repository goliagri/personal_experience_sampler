"""Reusable tkinter pieces: scrollable page, tag entry with autocomplete."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk

from ..core.tags import invalid_tags, normalize_tag, split_tags


class ScrollFrame(ttk.Frame):
    """A vertically scrolling container (the one-page answer flow, §10.3)."""

    def __init__(self, parent, **kw):
        super().__init__(parent, **kw)
        self.canvas = tk.Canvas(self, highlightthickness=0, borderwidth=0)
        self.vbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.inner = ttk.Frame(self.canvas)
        self.inner_id = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.canvas.configure(yscrollcommand=self.vbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.vbar.pack(side="right", fill="y")
        self.inner.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")),
        )
        self.canvas.bind(
            "<Configure>",
            lambda e: self.canvas.itemconfigure(self.inner_id, width=e.width),
        )
        for widget in (self.canvas, self.inner):
            widget.bind("<MouseWheel>", self._wheel)
            widget.bind("<Button-4>", self._wheel)
            widget.bind("<Button-5>", self._wheel)

    def _wheel(self, event):
        delta = -1 if getattr(event, "delta", 0) > 0 or event.num == 4 else 1
        self.canvas.yview_scroll(delta, "units")


# `split_tags` / `invalid_tags` are re-exported from the core (spec §7) so
# there is exactly one definition of the rule across both clients.
__all__ = ["ScrollFrame", "TagEntry", "invalid_tags", "normalize_tag", "split_tags"]


class TagEntry(ttk.Frame):
    """Whitespace-separated tags with prefix autocomplete from tag_vocab."""

    def __init__(self, parent, suggest, curated: list[str] | None = None):
        super().__init__(parent)
        self.suggest = suggest  # (prefix) -> list[str]
        # Tags are folded on ingest, so the curated list must be compared
        # folded too or a capitalised entry could never match.
        self.curated = None if curated is None else [normalize_tag(t) for t in curated]
        self.entry = ttk.Entry(self)
        self.entry.pack(fill="x")
        self.listbox: tk.Listbox | None = None
        self.entry.bind("<KeyRelease>", self._on_key)
        self.entry.bind("<Down>", self._focus_list)
        self.entry.bind("<Escape>", lambda e: self._close_list())
        self.entry.bind("<FocusOut>", lambda e: self.after(150, self._close_list))

    def get(self) -> list[str]:
        return split_tags(self.entry.get())

    def set(self, tags: list[str]) -> None:
        self.entry.delete(0, "end")
        self.entry.insert(0, " ".join(tags))

    def errors(self) -> str | None:
        bad = invalid_tags(self.entry.get())
        if bad:
            return f"invalid tag(s): {' '.join(bad)}"
        if self.curated is not None:
            off = [t for t in self.get() if t not in self.curated]
            if off:
                return f"not in the allowed list: {' '.join(off)}"
        return None

    # -- autocomplete -----------------------------------------------------

    def _current_prefix(self) -> str:
        text = self.entry.get()[: self.entry.index("insert")]
        return text.split()[-1] if text and not text[-1].isspace() else ""

    def _on_key(self, event):
        if event.keysym in ("Down", "Up", "Return", "Escape", "Tab"):
            return
        prefix = self._current_prefix()
        pool = (
            [t for t in self.curated if t.startswith(prefix)]
            if self.curated is not None
            else self.suggest(prefix)
        )
        pool = [t for t in pool if t != prefix]
        if not prefix or not pool:
            self._close_list()
            return
        self._open_list(pool[:8])

    def _open_list(self, items: list[str]) -> None:
        self._close_list()
        self.listbox = tk.Listbox(self.winfo_toplevel(), height=min(8, len(items)))
        for item in items:
            self.listbox.insert("end", item)
        x = self.entry.winfo_rootx() - self.winfo_toplevel().winfo_rootx()
        y = (
            self.entry.winfo_rooty()
            - self.winfo_toplevel().winfo_rooty()
            + self.entry.winfo_height()
        )
        self.listbox.place(x=x, y=y, width=self.entry.winfo_width())
        self.listbox.bind("<<ListboxSelect>>", self._pick)
        self.listbox.bind("<Return>", self._pick)
        self.listbox.bind("<Escape>", lambda e: self._close_list())

    def _focus_list(self, _event):
        if self.listbox:
            self.listbox.focus_set()
            self.listbox.selection_set(0)
        return "break"

    def _pick(self, _event):
        if not self.listbox or not self.listbox.curselection():
            return
        chosen = self.listbox.get(self.listbox.curselection()[0])
        text = self.entry.get()
        cursor = self.entry.index("insert")
        head = text[:cursor]
        prefix = self._current_prefix()
        new_head = head[: len(head) - len(prefix)] + chosen + " "
        self.entry.delete(0, "end")
        self.entry.insert(0, new_head + text[cursor:])
        self.entry.icursor(len(new_head))
        self.entry.focus_set()
        self._close_list()

    def _close_list(self) -> None:
        if self.listbox is not None:
            self.listbox.destroy()
            self.listbox = None
