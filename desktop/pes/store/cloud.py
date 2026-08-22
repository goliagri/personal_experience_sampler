"""CloudStore abstraction (spec §8.5) and the local-folder backend.

The sync procedure (§8.4) is written against this interface so the Google
Drive REST backend (Milestone 3) and a plain shared folder (development,
Syncthing) are interchangeable. Paths are always cloud-relative, ``/``
-separated (e.g. ``events/laptop-9c11aa00/2026-08.jsonl``).

``metadata`` returns an opaque change tag (``etag``): callers may only compare
it for equality against a previously seen value. The local backend hashes
content; the Drive backend will use ``modifiedTime``.
"""

from __future__ import annotations

import hashlib
from abc import ABC, abstractmethod
from pathlib import Path, PurePosixPath


class CloudStore(ABC):
    @abstractmethod
    def get(self, path: str) -> bytes | None:
        """File content, or None if absent."""

    @abstractmethod
    def put(self, path: str, data: bytes) -> None:
        """Write (overwrite) a file, creating parents."""

    @abstractmethod
    def put_if_absent(self, path: str, data: bytes) -> bool:
        """Write only if the file does not exist; True if written."""

    @abstractmethod
    def list(self, prefix: str) -> list[str]:
        """All file paths under a directory prefix, recursively, sorted."""

    @abstractmethod
    def metadata(self, path: str) -> dict | None:
        """{"etag": str, "size": int} or None if absent."""


def _check_relative(path: str) -> PurePosixPath:
    p = PurePosixPath(path)
    if p.is_absolute() or ".." in p.parts:
        raise ValueError(f"cloud path must be relative, without ..: {path!r}")
    return p


class LocalFolderStore(CloudStore):
    """A directory on disk acting as the cloud folder."""

    def __init__(self, root: Path | str):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _full(self, path: str) -> Path:
        return self.root / _check_relative(path)

    def get(self, path: str) -> bytes | None:
        full = self._full(path)
        if not full.is_file():
            return None
        return full.read_bytes()

    def put(self, path: str, data: bytes) -> None:
        full = self._full(path)
        full.parent.mkdir(parents=True, exist_ok=True)
        tmp = full.with_name(full.name + ".tmp")
        tmp.write_bytes(data)
        tmp.replace(full)

    def put_if_absent(self, path: str, data: bytes) -> bool:
        full = self._full(path)
        if full.exists():
            return False
        self.put(path, data)
        return True

    def list(self, prefix: str) -> list[str]:
        base = self._full(prefix)
        if not base.is_dir():
            return []
        return sorted(
            str(p.relative_to(self.root).as_posix())
            for p in base.rglob("*")
            if p.is_file() and not p.name.endswith(".tmp")
        )

    def metadata(self, path: str) -> dict | None:
        full = self._full(path)
        if not full.is_file():
            return None
        data = full.read_bytes()
        return {"etag": hashlib.sha256(data).hexdigest(), "size": len(data)}
