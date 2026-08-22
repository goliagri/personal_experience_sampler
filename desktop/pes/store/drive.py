"""Google Drive REST v3 backend for ``CloudStore`` (spec §8.5).

Thin client over the REST API — no Google SDK. Scope is ``drive.file`` only,
so every visible file was created by this app. The root folder
(``PersonalExperienceSampler``) is located by name once, then addressed by its
stored Drive file ID (kv namespace ``drive``), so renames in Drive are
harmless. Folder and file IDs are cached in the same namespace and
re-resolved on 404 (something deleted/recreated remotely).

``metadata()``'s etag is Drive's ``modifiedTime`` — opaque to callers, only
compared for equality, exactly like the local backend's content hash.

The HTTP session is injected (anything with ``.request(method, url, **kw)``):
production uses ``AuthorizedSession``; tests use an in-memory fake.
"""

from __future__ import annotations

import json
import secrets

import requests

from .cloud import CloudStore, _check_relative
from .oauth import DriveAuth

FILES_URL = "https://www.googleapis.com/drive/v3/files"
UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
FOLDER_MIME = "application/vnd.google-apps.folder"
ROOT_FOLDER_NAME = "PersonalExperienceSampler"
LIST_FIELDS = "files(id,name,mimeType,modifiedTime,size),nextPageToken"


class DriveError(OSError):
    """Unexpected Drive API response."""


class MultipleRootsError(DriveError):
    """More than one root folder found (§8.5): user must resolve manually."""


class AuthorizedSession:
    """requests.Session + DriveAuth: bearer header, one retry on 401."""

    def __init__(self, auth: DriveAuth, session: requests.Session | None = None):
        self.auth = auth
        self.session = session or requests.Session()

    def request(self, method: str, url: str, **kw):
        headers = dict(kw.pop("headers", None) or {})
        kw.setdefault("timeout", 30)
        headers["Authorization"] = f"Bearer {self.auth.access_token()}"
        resp = self.session.request(method, url, headers=headers, **kw)
        if resp.status_code == 401:
            headers["Authorization"] = f"Bearer {self.auth.access_token(force_refresh=True)}"
            resp = self.session.request(method, url, headers=headers, **kw)
        return resp


def _escape(name: str) -> str:
    return name.replace("\\", "\\\\").replace("'", "\\'")


class DriveStore(CloudStore):
    """CloudStore over the Drive REST API; ID caches live in the local db."""

    def __init__(self, session, db, root_name: str = ROOT_FOLDER_NAME):
        self.session = session
        self.db = db  # kv namespace "drive": root + path->id caches
        self.root_name = root_name
        self._root: str | None = None  # verified once per instance

    # -- low-level HTTP ---------------------------------------------------

    def _request(self, method: str, url: str, ok=(200,), **kw):
        resp = self.session.request(method, url, **kw)
        if resp.status_code not in ok:
            raise DriveError(f"Drive API {resp.status_code} on {method} {url}")
        return resp

    def _list_query(self, q: str) -> list[dict]:
        out: list[dict] = []
        token = None
        while True:
            params = {"q": q, "fields": LIST_FIELDS, "pageSize": 1000}
            if token:
                params["pageToken"] = token
            body = self._request("GET", FILES_URL, params=params).json()
            out.extend(body.get("files", []))
            token = body.get("nextPageToken")
            if not token:
                return out

    def _children(self, parent_id: str, name: str | None = None) -> list[dict]:
        q = f"'{parent_id}' in parents and trashed = false"
        if name is not None:
            q = f"name = '{_escape(name)}' and " + q
        return self._list_query(q)

    # -- ID cache ----------------------------------------------------------

    def _cached(self, key: str) -> str | None:
        return self.db.kv_get("drive", key)

    def _cache(self, key: str, file_id: str) -> None:
        self.db.kv_set("drive", key, file_id)

    def _drop_cache(self, key: str) -> None:
        self.db.kv_set("drive", key, "")

    def _exists(self, file_id: str) -> bool:
        resp = self._request(
            "GET",
            f"{FILES_URL}/{file_id}",
            ok=(200, 404),
            params={"fields": "id,trashed"},
        )
        return resp.status_code == 200 and not resp.json().get("trashed")

    # -- root folder (§8.5) ------------------------------------------------

    def root_id(self) -> str:
        if self._root:
            return self._root
        cached = self._cached("root")
        if cached and self._exists(cached):
            self._root = cached
            return cached
        if cached:
            # Root vanished (folder deleted/recreated): every cached ID under
            # it is stale too.
            for key in self.db.kv_all("drive"):
                self._drop_cache(key)
        matches = self._list_query(
            f"name = '{_escape(self.root_name)}'"
            f" and mimeType = '{FOLDER_MIME}' and trashed = false"
        )
        if len(matches) > 1:
            raise MultipleRootsError(
                f"Found {len(matches)} '{self.root_name}' folders in Drive;"
                " remove or rename the extras, then sync again"
            )
        root = matches[0]["id"] if matches else self._create_folder(self.root_name, None)
        self._cache("root", root)
        self._root = root
        return root

    def _create_folder(self, name: str, parent_id: str | None) -> str:
        meta: dict = {"name": name, "mimeType": FOLDER_MIME}
        if parent_id:
            meta["parents"] = [parent_id]
        return self._request("POST", FILES_URL, json=meta).json()["id"]

    # -- path resolution ---------------------------------------------------

    def _resolve_dir(self, parts: tuple[str, ...], create: bool) -> str | None:
        """Folder ID for a directory path (relative to root); walks + caches."""
        parent = self.root_id()
        path = ""
        for name in parts:
            path = f"{path}/{name}" if path else name
            key = f"dir:{path}"
            cached = self._cached(key)
            if cached:
                parent = cached
                continue
            found = [
                f for f in self._children(parent, name) if f["mimeType"] == FOLDER_MIME
            ]
            if found:
                parent = found[0]["id"]
            elif create:
                parent = self._create_folder(name, parent)
            else:
                return None
            self._cache(key, parent)
        return parent

    def _resolve_file(self, path: str) -> dict | None:
        """{"id", "modifiedTime", "size"} for a file path, or None if absent."""
        p = _check_relative(path)
        parent = self._resolve_dir(p.parts[:-1], create=False)
        if parent is None:
            return None
        key = f"file:{path}"
        cached = self._cached(key)
        if cached:
            resp = self._request(
                "GET",
                f"{FILES_URL}/{cached}",
                ok=(200, 404),
                params={"fields": "id,trashed,modifiedTime,size"},
            )
            if resp.status_code == 200 and not resp.json().get("trashed"):
                return resp.json()
            self._drop_cache(key)
        found = [
            f for f in self._children(parent, p.name) if f["mimeType"] != FOLDER_MIME
        ]
        if not found:
            return None
        self._cache(key, found[0]["id"])
        return found[0]

    # -- CloudStore interface ---------------------------------------------

    def get(self, path: str) -> bytes | None:
        info = self._resolve_file(path)
        if info is None:
            return None
        resp = self._request(
            "GET", f"{FILES_URL}/{info['id']}", ok=(200, 404), params={"alt": "media"}
        )
        if resp.status_code == 404:  # deleted between resolve and read
            self._drop_cache(f"file:{path}")
            return None
        return resp.content

    def put(self, path: str, data: bytes) -> None:
        info = self._resolve_file(path)
        if info is not None:
            resp = self._request(
                "PATCH",
                f"{UPLOAD_URL}/{info['id']}",
                ok=(200, 404),
                params={"uploadType": "media"},
                data=data,
                headers={"Content-Type": "application/octet-stream"},
            )
            if resp.status_code == 200:
                return
            self._drop_cache(f"file:{path}")  # vanished remotely; create fresh
        self._create_file(path, data)

    def put_if_absent(self, path: str, data: bytes) -> bool:
        if self._resolve_file(path) is not None:
            return False
        self._create_file(path, data)
        return True

    def _create_file(self, path: str, data: bytes) -> None:
        p = _check_relative(path)
        parent = self._resolve_dir(p.parts[:-1], create=True)
        boundary = "pes" + secrets.token_hex(12)
        meta = {"name": p.name, "parents": [parent]}
        body = (
            f"--{boundary}\r\n"
            "Content-Type: application/json; charset=UTF-8\r\n\r\n"
            f"{json.dumps(meta)}\r\n"
            f"--{boundary}\r\n"
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode() + data + f"\r\n--{boundary}--\r\n".encode()
        resp = self._request(
            "POST",
            UPLOAD_URL,
            params={"uploadType": "multipart"},
            data=body,
            headers={"Content-Type": f"multipart/related; boundary={boundary}"},
        )
        self._cache(f"file:{path}", resp.json()["id"])

    def list(self, prefix: str) -> list[str]:
        parts = _check_relative(prefix).parts
        base = self._resolve_dir(parts, create=False)
        if base is None:
            return []
        out: list[str] = []
        stack = [("/".join(parts), base)]
        while stack:
            dir_path, dir_id = stack.pop()
            for f in self._children(dir_id):
                child_path = f"{dir_path}/{f['name']}"
                if f["mimeType"] == FOLDER_MIME:
                    self._cache(f"dir:{child_path}", f["id"])
                    stack.append((child_path, f["id"]))
                else:
                    self._cache(f"file:{child_path}", f["id"])
                    out.append(child_path)
        return sorted(out)

    def metadata(self, path: str) -> dict | None:
        info = self._resolve_file(path)
        if info is None:
            return None
        return {"etag": info["modifiedTime"], "size": int(info.get("size") or 0)}
