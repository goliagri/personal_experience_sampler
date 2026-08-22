"""OAuth flow unit tests (library-free installed-app flow, spec §8.5):
token persistence and permissions, refresh, revoked-grant reporting, and the
full loopback consent round-trip against a fake token endpoint."""

from __future__ import annotations

import json
import os
import stat
import sys
import threading
import urllib.parse
import urllib.request

import pytest

from pes.store.oauth import DriveAuth, DriveAuthError


class FakeTokenEndpoint:
    """Stands in for requests.Session; answers only token-endpoint POSTs."""

    def __init__(self):
        self.posts: list[dict] = []
        self.response: tuple[int, dict] = (
            200,
            {"access_token": "at-1", "refresh_token": "rt-1", "expires_in": 3600},
        )

    def post(self, url, data=None, timeout=None):
        self.posts.append({"url": url, "data": data})
        status, body = self.response

        class Resp:
            status_code = status

            @staticmethod
            def json():
                return body

        return Resp()


@pytest.fixture
def auth(tmp_path):
    secret = tmp_path / "client.json"
    secret.write_text(
        json.dumps(
            {
                "installed": {
                    "client_id": "cid",
                    "client_secret": "csecret",
                    "auth_uri": "https://example.test/auth",
                    "token_uri": "https://example.test/token",
                    "redirect_uris": ["http://localhost"],
                }
            }
        )
    )
    a = DriveAuth(secret, tmp_path / "token.json", session=FakeTokenEndpoint())
    a._now = lambda: 1_000_000.0
    return a


def test_not_connected_raises(auth):
    assert not auth.connected()
    with pytest.raises(DriveAuthError, match="not connected"):
        auth.access_token()


def test_token_file_permissions_and_reuse(auth):
    auth._save_token(
        {"refresh_token": "rt", "access_token": "at", "expires_at": 1_000_500.0}
    )
    if sys.platform != "win32":
        mode = stat.S_IMODE(os.stat(auth.token_path).st_mode)
        assert mode == 0o600
    # Fresh token: served from disk, no network.
    assert auth.access_token() == "at"
    assert auth.session.posts == []


def test_refresh_when_expired(auth):
    auth._save_token(
        {"refresh_token": "rt", "access_token": "old", "expires_at": 999_999.0}
    )
    auth.session.response = (200, {"access_token": "new", "expires_in": 3600})
    assert auth.access_token() == "new"
    assert auth.session.posts[0]["data"]["grant_type"] == "refresh_token"
    saved = json.loads(auth.token_path.read_text())
    assert saved["access_token"] == "new"
    assert saved["refresh_token"] == "rt"  # kept when not re-issued


def test_revoked_grant_reports_reconnect(auth):
    auth._save_token({"refresh_token": "rt", "access_token": "", "expires_at": 0})
    auth.session.response = (400, {"error": "invalid_grant"})
    with pytest.raises(DriveAuthError, match="reconnect"):
        auth.access_token()


def test_disconnect_removes_token(auth):
    auth._save_token({"refresh_token": "rt", "access_token": "at", "expires_at": 0})
    auth.disconnect()
    assert not auth.token_path.exists()
    assert not auth.connected()


def test_authorize_loopback_roundtrip(auth):
    """Fake 'browser': follow the redirect back to the loopback server."""

    def browser(url: str) -> None:
        query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        assert query["code_challenge_method"] == ["S256"]
        redirect = query["redirect_uri"][0]
        state = query["state"][0]

        def hit():
            urllib.request.urlopen(f"{redirect}/?code=authcode&state={state}")

        threading.Thread(target=hit, daemon=True).start()

    auth.authorize(open_browser=browser, timeout_s=10)
    exchange = auth.session.posts[-1]["data"]
    assert exchange["grant_type"] == "authorization_code"
    assert exchange["code"] == "authcode"
    assert "code_verifier" in exchange
    assert auth.connected()
    assert json.loads(auth.token_path.read_text())["refresh_token"] == "rt-1"


def test_authorize_state_mismatch_rejected(auth):
    def browser(url: str) -> None:
        query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        redirect = query["redirect_uri"][0]

        def hit():
            urllib.request.urlopen(f"{redirect}/?code=authcode&state=WRONG")

        threading.Thread(target=hit, daemon=True).start()

    with pytest.raises(DriveAuthError, match="state mismatch"):
        auth.authorize(open_browser=browser, timeout_s=10)
    assert not auth.connected()
