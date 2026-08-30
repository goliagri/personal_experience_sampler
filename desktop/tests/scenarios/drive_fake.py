"""In-memory fake of the Drive REST v3 surface DriveStore uses (test plan §2,
"Drive store (Milestone 3, mockable HTTP layer)").

Implements exactly the requests DriveStore makes: files.list with the three
query shapes (name/parent/mimeType/trashed), files.get (metadata and
alt=media), folder create, multipart create, and media update. modifiedTime
is a deterministic counter — opaque to callers, like the real timestamp.
"""

from __future__ import annotations

import json
import re


class FakeResponse:
    def __init__(self, status_code: int, content: bytes = b"", body: dict | None = None):
        self.status_code = status_code
        self.content = content if body is None else json.dumps(body).encode()

    def json(self) -> dict:
        return json.loads(self.content.decode())


class FakeDrive:
    """A requests-session stand-in backed by a dict of Drive file records."""

    def __init__(self):
        self.files: dict[str, dict] = {}  # id -> {name, mimeType, parents, content}
        self._seq = 0
        self.calls: list[tuple[str, str, dict]] = []  # (method, url, params)
        self.fail_after: int | None = None  # raise OSError past this many calls

    # -- direct manipulation for test setup/assertions --------------------

    def _next(self, prefix: str) -> str:
        self._seq += 1
        return f"{prefix}-{self._seq:06d}"

    def add(self, name: str, mime: str, parents: list[str], content: bytes = b"") -> str:
        file_id = self._next("id")
        self.files[file_id] = {
            "id": file_id,
            "name": name,
            "mimeType": mime,
            "parents": parents,
            "trashed": False,
            "modifiedTime": self._next("mt"),
            "content": content,
        }
        return file_id

    def by_name(self, name: str) -> list[dict]:
        return [f for f in self.files.values() if f["name"] == name and not f["trashed"]]

    def media_downloads(self) -> list[str]:
        return [
            url.rsplit("/", 1)[1]
            for method, url, params in self.calls
            if method == "GET" and params.get("alt") == "media"
        ]

    # -- the fake API -----------------------------------------------------

    def request(self, method: str, url: str, **kw) -> FakeResponse:
        params = kw.get("params") or {}
        self.calls.append((method, url, params))
        if self.fail_after is not None and len(self.calls) > self.fail_after:
            raise OSError("simulated network failure")

        if url == "https://www.googleapis.com/drive/v3/files":
            if method == "GET":
                return self._list(params["q"])
            if method == "POST":  # folder / metadata-only create
                return self._create(kw["json"], b"")
        if (
            url == "https://www.googleapis.com/upload/drive/v3/files"
            and method == "POST"
            and params.get("uploadType") == "multipart"
        ):
            meta, content = self._parse_multipart(
                kw["data"], kw["headers"]["Content-Type"]
            )
            return self._create(meta, content)
        if url.startswith("https://www.googleapis.com/upload/drive/v3/files/"):
            file_id = url.rsplit("/", 1)[1]
            if method == "PATCH" and params.get("uploadType") == "media":
                record = self.files.get(file_id)
                if record is None or record["trashed"]:
                    return FakeResponse(404, body={"error": "not found"})
                record["content"] = kw["data"]
                record["modifiedTime"] = self._next("mt")
                return FakeResponse(200, body={"id": file_id})
        if url.startswith("https://www.googleapis.com/drive/v3/files/"):
            file_id = url.rsplit("/", 1)[1]
            record = self.files.get(file_id)
            if method == "DELETE":
                if record is None:
                    return FakeResponse(404, body={"error": "not found"})
                del self.files[file_id]
                return FakeResponse(204)
            if method == "GET":
                if record is None or record["trashed"]:
                    return FakeResponse(404, body={"error": "not found"})
                if params.get("alt") == "media":
                    return FakeResponse(200, content=record["content"])
                return FakeResponse(200, body=self._meta(record))
        raise AssertionError(f"FakeDrive: unexpected {method} {url} {params}")

    def _meta(self, record: dict) -> dict:
        return {
            "id": record["id"],
            "name": record["name"],
            "mimeType": record["mimeType"],
            "trashed": record["trashed"],
            "modifiedTime": record["modifiedTime"],
            "size": str(len(record["content"])),
        }

    def _live(self, file_id: str) -> bool:
        record = self.files.get(file_id)
        return record is not None and not record["trashed"]

    def trash(self, file_id: str) -> None:
        """Trash a file, or a folder with everything under it (as Drive does)."""
        self.files[file_id]["trashed"] = True
        for f in list(self.files.values()):
            if file_id in f["parents"]:
                self.trash(f["id"])

    def _create(self, meta: dict, content: bytes) -> FakeResponse:
        if any(not self._live(p) for p in meta.get("parents", [])):
            return FakeResponse(404, body={"error": "parent not found"})
        file_id = self.add(
            meta["name"],
            meta.get("mimeType", "application/octet-stream"),
            meta.get("parents", []),
            content,
        )
        return FakeResponse(200, body={"id": file_id})

    def _list(self, q: str) -> FakeResponse:
        name = mime = parent = None
        for clause in q.split(" and "):
            clause = clause.strip()
            if m := re.fullmatch(r"name = '((?:[^'\\]|\\.)*)'", clause):
                name = m.group(1).replace("\\'", "'").replace("\\\\", "\\")
            elif m := re.fullmatch(r"mimeType = '([^']*)'", clause):
                mime = m.group(1)
            elif m := re.fullmatch(r"'([^']*)' in parents", clause):
                parent = m.group(1)
            elif clause != "trashed = false":
                raise AssertionError(f"FakeDrive: unsupported clause {clause!r}")
        if parent is not None and not self._live(parent):
            return FakeResponse(404, body={"error": "parent not found"})
        matches = [
            self._meta(f)
            for f in self.files.values()
            if not f["trashed"]
            and (name is None or f["name"] == name)
            and (mime is None or f["mimeType"] == mime)
            and (parent is None or parent in f["parents"])
        ]
        return FakeResponse(200, body={"files": matches})

    @staticmethod
    def _parse_multipart(body: bytes, content_type: str) -> tuple[dict, bytes]:
        boundary = content_type.split("boundary=", 1)[1].encode()
        parts = body.split(b"--" + boundary)
        meta_part, data_part = parts[1], parts[2]
        meta = json.loads(meta_part.split(b"\r\n\r\n", 1)[1].rstrip(b"\r\n"))
        content = data_part.split(b"\r\n\r\n", 1)[1]
        content = content.removesuffix(b"\r\n")
        return meta, content
