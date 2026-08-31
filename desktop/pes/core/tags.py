"""Tag parsing and normalisation (spec §7).

Shared by every path that can produce tags — the desktop answer form, the
Android answer form, and Android's inline notification reply — so the same
typed text yields the same stored tags whichever client and whichever route
the owner used. Mirrored by `android/core/src/main/kotlin/pes/core/Tags.kt`.

Tags are **case-folded on ingest**. The notification reply box is drawn by the
system IME, which capitalises the first word and cannot be told not to, so
without folding the same tag enters `tag_vocab` as both `email` and `Email`,
splitting suggestion counts and splitting the exported data for analysis
(Tier 3 charter C1 F6).
"""

from __future__ import annotations

import re

TAG_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,64}$")


def split_tags(text: str) -> list[str]:
    """Whitespace-separated tags, normalised. Order and duplicates preserved."""
    return [normalize_tag(t) for t in text.split() if t]


def normalize_tag(tag: str) -> str:
    return tag.lower()


def invalid_tags(text: str) -> list[str]:
    """Tokens that are not valid tags, in input order (after normalisation)."""
    return [t for t in split_tags(text) if not TAG_RE.match(t)]
