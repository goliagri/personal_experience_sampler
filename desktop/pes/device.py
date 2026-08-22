"""Device identity (spec §4): stable ``<platform>-<8 hex>`` id per install."""

from __future__ import annotations

import secrets
import sys

from .store.db import Db


def _platform_prefix() -> str:
    if sys.platform.startswith("win"):
        return "win"
    if sys.platform == "darwin":
        return "mac"
    return "linux"


def get_device_id(db: Db) -> str:
    """The install's device id, generated on first call and persisted."""
    existing = db.kv_get("device", "device_id")
    if existing:
        return existing
    device_id = f"{_platform_prefix()}-{secrets.token_hex(4)}"
    db.kv_set("device", "device_id", device_id)
    return device_id
