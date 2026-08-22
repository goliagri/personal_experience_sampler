"""Unit tests for the local-folder CloudStore and UI-adjacent pure helpers."""

from __future__ import annotations

import pytest

from pes.store import LocalFolderStore
from pes.ui.surveys import validate_survey
from pes.ui.widgets import invalid_tags, split_tags


def test_local_folder_store_roundtrip(tmp_path):
    store = LocalFolderStore(tmp_path / "cloud")
    assert store.get("a/b.txt") is None
    assert store.metadata("a/b.txt") is None

    store.put("a/b.txt", b"one")
    assert store.get("a/b.txt") == b"one"
    meta = store.metadata("a/b.txt")
    assert meta["size"] == 3

    store.put("a/b.txt", b"two")
    assert store.get("a/b.txt") == b"two"
    assert store.metadata("a/b.txt")["etag"] != meta["etag"]

    assert store.put_if_absent("a/b.txt", b"three") is False
    assert store.get("a/b.txt") == b"two"
    assert store.put_if_absent("a/c.txt", b"three") is True

    assert store.list("a") == ["a/b.txt", "a/c.txt"]
    assert store.list("") == ["a/b.txt", "a/c.txt"]
    assert store.list("missing") == []


def test_local_folder_store_rejects_escaping_paths(tmp_path):
    store = LocalFolderStore(tmp_path / "cloud")
    for bad in ("../x", "a/../../x", "/abs"):
        with pytest.raises(ValueError):
            store.put(bad, b"x")


def test_tag_helpers():
    assert split_tags("  work.writing  home ") == ["work.writing", "home"]
    assert invalid_tags("ok als0-fine bad!tag") == ["bad!tag"]
    assert invalid_tags("a" * 65) == ["a" * 65]


def test_validate_survey():
    good = {
        "id": "s1",
        "version": 1,
        "fields": [
            {"id": "tags", "type": "tags", "label": "T"},
            {"id": "mood", "type": "number", "label": "M"},
        ],
    }
    assert validate_survey(good) == []
    assert validate_survey({"id": "Bad!", "version": 1, "fields": []})
    dup = {
        "id": "s1",
        "version": 1,
        "fields": [
            {"id": "a", "type": "tags"},
            {"id": "a", "type": "text"},
            {"id": "c", "type": "choice"},
        ],
    }
    errors = validate_survey(dup)
    assert any("duplicate" in e for e in errors)
    assert any("options" in e for e in errors)
