"""Library-free OAuth 2.0 for the Drive backend (spec §8.5).

Installed-app flow with PKCE and a loopback redirect, over plain HTTPS via
``requests`` — no Google client libraries (owner's decision). The token file
lives in the local data directory with mode 0600. Neither the token nor the
client-secret JSON is ever written to the cloud folder, synced, or logged.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import platform
import secrets
import shutil
import subprocess
import sys
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlparse

import requests

DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
REVOKE_URI = "https://oauth2.googleapis.com/revoke"
EXPIRY_MARGIN_S = 60


class DriveAuthError(OSError):
    """Authorization problem the user must resolve (connect/reconnect)."""


def _pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return verifier, challenge


def open_in_browser(url: str) -> None:
    """Open ``url`` in the user's browser; falls back to printing it.

    Under WSL the stock ``webbrowser`` module shells out to ``gio``/
    ``xdg-open``, which cannot reach the Windows browser — go through
    ``wslview`` or PowerShell instead.
    """
    if "microsoft" in platform.uname().release.lower():  # WSL
        if shutil.which("wslview"):
            subprocess.Popen(["wslview", url])
            return
        if shutil.which("powershell.exe"):
            # The URL contains & and =; hand it over pre-quoted.
            subprocess.Popen(
                ["powershell.exe", "-NoProfile", "-Command", f"Start-Process '{url}'"]
            )
            return
    elif webbrowser.open(url):
        return
    print(f"Open this URL in your browser to authorize:\n{url}", file=sys.stderr)


class _CodeHandler(BaseHTTPRequestHandler):
    """Receives the single OAuth redirect on the loopback server."""

    def do_GET(self) -> None:
        query = parse_qs(urlparse(self.path).query)
        self.server.result = {k: v[0] for k, v in query.items()}  # type: ignore[attr-defined]
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"Authorized. You can close this window and return to the app.")

    def log_message(self, *args) -> None:  # silence request logging
        pass


class DriveAuth:
    """Loads the OAuth client, runs the consent flow, refreshes tokens."""

    def __init__(
        self,
        client_secret_path: Path | str,
        token_path: Path | str,
        session: requests.Session | None = None,
    ):
        self.client_secret_path = Path(client_secret_path)
        self.token_path = Path(token_path)
        self.session = session or requests.Session()
        self._token: dict | None = None
        self._now = time.time  # patchable in tests

    # -- client secret ----------------------------------------------------

    def _client(self) -> dict:
        try:
            doc = json.loads(self.client_secret_path.read_text())
        except FileNotFoundError as exc:
            raise DriveAuthError(
                f"OAuth client file not found: {self.client_secret_path}"
            ) from exc
        client = doc.get("installed")
        if not client:
            raise DriveAuthError(
                "OAuth client JSON must be an 'installed' (desktop) client"
            )
        return client

    # -- token persistence ------------------------------------------------

    def connected(self) -> bool:
        return self._load_token() is not None

    def _load_token(self) -> dict | None:
        if self._token is None and self.token_path.is_file():
            self._token = json.loads(self.token_path.read_text())
        return self._token

    def _save_token(self, token: dict) -> None:
        self._token = token
        data = (json.dumps(token, indent=2) + "\n").encode()
        fd = os.open(
            self.token_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600
        )
        with os.fdopen(fd, "wb") as f:
            f.write(data)

    # -- consent flow ------------------------------------------------------

    def authorize(self, open_browser=open_in_browser, timeout_s: int = 300) -> None:
        """Run the installed-app consent flow; blocks until redirected.

        Call from a worker thread — this waits for the browser round-trip.
        """
        client = self._client()
        verifier, challenge = _pkce_pair()
        state = secrets.token_urlsafe(16)
        server = HTTPServer(("127.0.0.1", 0), _CodeHandler)
        server.result = None  # type: ignore[attr-defined]
        server.timeout = 1  # short accept timeout so the deadline check runs
        redirect_uri = f"http://127.0.0.1:{server.server_address[1]}"
        url = client["auth_uri"] + "?" + urlencode(
            {
                "client_id": client["client_id"],
                "redirect_uri": redirect_uri,
                "response_type": "code",
                "scope": DRIVE_SCOPE,
                "code_challenge": challenge,
                "code_challenge_method": "S256",
                "access_type": "offline",
                "prompt": "consent",
                "state": state,
            }
        )
        try:
            open_browser(url)
            deadline = self._now() + timeout_s
            while server.result is None:  # type: ignore[attr-defined]
                if self._now() > deadline:
                    raise DriveAuthError("Timed out waiting for browser authorization")
                server.handle_request()
            result = server.result  # type: ignore[attr-defined]
        finally:
            server.server_close()
        if result.get("state") != state:
            raise DriveAuthError("Authorization response state mismatch; try again")
        if "code" not in result:
            raise DriveAuthError(
                f"Authorization refused: {result.get('error', 'no code returned')}"
            )
        self._exchange_code(client, result["code"], verifier, redirect_uri)

    def _exchange_code(
        self, client: dict, code: str, verifier: str, redirect_uri: str
    ) -> None:
        resp = self.session.post(
            client["token_uri"],
            data={
                "client_id": client["client_id"],
                "client_secret": client["client_secret"],
                "code": code,
                "code_verifier": verifier,
                "redirect_uri": redirect_uri,
                "grant_type": "authorization_code",
            },
            timeout=30,
        )
        if resp.status_code != 200:
            raise DriveAuthError(f"Token exchange failed (HTTP {resp.status_code})")
        body = resp.json()
        if "refresh_token" not in body:
            raise DriveAuthError("No refresh token granted; revoke access and retry")
        self._save_token(
            {
                "refresh_token": body["refresh_token"],
                "access_token": body.get("access_token", ""),
                "expires_at": self._now() + body.get("expires_in", 0) - EXPIRY_MARGIN_S,
            }
        )

    # -- access tokens -----------------------------------------------------

    def access_token(self, force_refresh: bool = False) -> str:
        token = self._load_token()
        if token is None:
            raise DriveAuthError("Google Drive is not connected (Settings > Connect)")
        if not force_refresh and token.get("access_token") and self._now() < token.get(
            "expires_at", 0
        ):
            return token["access_token"]
        return self._refresh(token)

    def _refresh(self, token: dict) -> str:
        client = self._client()
        resp = self.session.post(
            client["token_uri"],
            data={
                "client_id": client["client_id"],
                "client_secret": client["client_secret"],
                "refresh_token": token["refresh_token"],
                "grant_type": "refresh_token",
            },
            timeout=30,
        )
        if resp.status_code != 200:
            error = ""
            try:
                error = resp.json().get("error", "")
            except ValueError:
                pass
            if error in ("invalid_grant", "unauthorized_client"):
                raise DriveAuthError(
                    "Drive authorization was revoked or expired;"
                    " reconnect from Settings"
                )
            raise DriveAuthError(f"Token refresh failed (HTTP {resp.status_code})")
        body = resp.json()
        token = dict(token)
        token["access_token"] = body["access_token"]
        token["expires_at"] = self._now() + body.get("expires_in", 3600) - EXPIRY_MARGIN_S
        if body.get("refresh_token"):
            token["refresh_token"] = body["refresh_token"]
        self._save_token(token)
        return token["access_token"]

    # -- disconnect --------------------------------------------------------

    def disconnect(self) -> None:
        """Best-effort revoke, then forget the local token."""
        token = self._load_token()
        if token:
            try:
                self.session.post(
                    REVOKE_URI, data={"token": token["refresh_token"]}, timeout=10
                )
            except requests.RequestException:
                pass  # offline; the local token is removed regardless
        self._token = None
        self.token_path.unlink(missing_ok=True)
