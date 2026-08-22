"""Shared design language (spec §10.1): status palette, fonts, two themes."""

from __future__ import annotations

STATUS_COLORS = {
    "answered": "#2e7d32",  # green
    "skipped": "#757575",  # gray
    "expired": "#f9a825",  # amber
    "unobserved": "#607d8b",  # blue-gray
    "suppressed": "#9e9e9e",  # muted
    "pending": "#1565c0",  # accent
    "retracted": "#9e9e9e",  # struck-through in rendering
    "scheduled": "#bdbdbd",
}

THEMES = {
    "light": {
        "bg": "#fafafa",
        "fg": "#212121",
        "muted": "#616161",
        "card": "#ffffff",
        "accent": "#1565c0",
        "warn_bg": "#fff3cd",
        "warn_fg": "#7a5c00",
        "entry_bg": "#ffffff",
    },
    "dark": {
        "bg": "#1e1e1e",
        "fg": "#e0e0e0",
        "muted": "#9e9e9e",
        "card": "#2a2a2a",
        "accent": "#64b5f6",
        "warn_bg": "#4a3c00",
        "warn_fg": "#ffe082",
        "entry_bg": "#333333",
    },
}

FONT = ("TkDefaultFont", 10)
FONT_BOLD = ("TkDefaultFont", 10, "bold")
FONT_BIG = ("TkDefaultFont", 14, "bold")
FONT_TIME = ("TkDefaultFont", 16, "bold")
