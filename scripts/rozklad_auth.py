#!/usr/bin/env python3
"""Access tokens of the local stack for scripts, curl and e2e-real (docs/RUNNING.md).

The sign-in service (services/auth) has no password grant. This module drives the real browser flow over
HTTP with the public client `rozklad-cli` (authorization code + PKCE, loopback redirect), which exists only
while AUTH_CLI_ENABLED=true (development):

  1. GET  <auth>/oauth2/authorize?...code_challenge...   -> redirect to the sign-in page /auth/login
  2. POST <auth>/login  username, password, _csrf         -> redirect back to authorize -> loopback callback
  3. the loopback URL is never opened: the code is read from it and the state is checked
  4. POST <auth>/oauth2/token  code + code_verifier        -> access token (no refresh token)

Standard library only. Demo credentials come from infra/dev/demo-users.env (test values only).

  python scripts/rozklad_auth.py token --user admin      # prints the access token of the demo admin
  python scripts/rozklad_auth.py token --user krentsin   # a demo teacher (TEACHER, TEACHER2 also work)

AUTH_URL overrides where the sign-in service is reached (default http://127.0.0.1:8088/auth, nginx of the
web container; http://127.0.0.1:8085/auth reaches the service directly). The token's issuer is always the
configured one (http://localhost:8088/auth), whichever address served the flow.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUTH_URL = os.environ.get("AUTH_URL", "http://127.0.0.1:8088/auth").rstrip("/")
CLIENT_ID = "rozklad-cli"
# loopback: the service accepts any port for an IP literal (RFC 8252); nothing listens there
REDIRECT_URI = "http://127.0.0.1:53682/cli-callback"
DEMO_USERS = ROOT / "infra" / "dev" / "demo-users.env"


class AuthError(RuntimeError):
    """The sign-in did not produce a token (wrong password, lockout, service down, client disabled)."""


def demo_credentials() -> dict[str, str]:
    """ADMIN_USERNAME, ADMIN_PASSWORD, TEACHER_* and TEACHER2_* of infra/dev/demo-users.env."""
    values = {}
    for line in DEMO_USERS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def demo_user(name: str) -> tuple[str, str]:
    """(username, password) by role prefix (ADMIN, TEACHER, TEACHER2) or by username (admin, krentsin, ...)."""
    users = demo_credentials()
    prefix = name.upper()
    if f"{prefix}_USERNAME" not in users:
        prefix = next((key[: -len("_USERNAME")] for key, value in users.items()
                       if key.endswith("_USERNAME") and value == name), "")
    username, password = users.get(f"{prefix}_USERNAME"), users.get(f"{prefix}_PASSWORD")
    if not username or not password:
        raise AuthError(f"{DEMO_USERS} has no demo user '{name}'")
    return username, password


class _Callback(Exception):
    def __init__(self, url: str):
        super().__init__(url)
        self.url = url


class _StopAtCallback(urllib.request.HTTPRedirectHandler):
    """Follows redirects like a browser, but stops at the loopback callback instead of connecting to it."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl.startswith(REDIRECT_URI):
            raise _Callback(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class _LoginPage(HTMLParser):
    """The hidden _csrf field and the alert text of the sign-in page."""

    def __init__(self):
        super().__init__()
        self.csrf: str | None = None
        self.alert: list[str] = []
        self._in_alert = 0

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "input" and attributes.get("name") == "_csrf":
            self.csrf = attributes.get("value")
        if attributes.get("role") == "alert":
            self._in_alert += 1
        elif self._in_alert:
            self._in_alert += 1

    def handle_endtag(self, tag):
        if self._in_alert:
            self._in_alert -= 1

    def handle_data(self, data):
        if self._in_alert and data.strip():
            self.alert.append(data.strip())


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def login(username: str, password: str, auth_url: str = AUTH_URL) -> dict:
    """Signs in through the sign-in page and returns the token response (access_token, expires_in, id_token)."""
    verifier = _b64url(secrets.token_bytes(32))
    challenge = _b64url(hashlib.sha256(verifier.encode("ascii")).digest())
    state = _b64url(secrets.token_bytes(16))
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                         _StopAtCallback())
    # text/html: without it the service answers 401 instead of sending a browser to its sign-in page
    headers = {"Accept": "text/html"}
    query = urllib.parse.urlencode({
        "response_type": "code", "client_id": CLIENT_ID, "redirect_uri": REDIRECT_URI, "scope": "openid",
        "state": state, "code_challenge": challenge, "code_challenge_method": "S256"})
    try:
        try:
            with opener.open(urllib.request.Request(f"{auth_url}/oauth2/authorize?{query}", headers=headers),
                             timeout=30) as response:
                page = _LoginPage()
                page.feed(response.read().decode("utf-8", "replace"))
        except _Callback as already:  # a session in the cookie jar (never on a fresh jar, kept for safety)
            return _exchange(opener, auth_url, already.url, state, verifier)
        if not page.csrf:
            raise AuthError(f"{auth_url}/login has no sign-in form (is AUTH_CLI_ENABLED=true?)")
        form = urllib.parse.urlencode({"username": username, "password": password, "_csrf": page.csrf}).encode()
        try:
            with opener.open(urllib.request.Request(f"{auth_url}/login", data=form, headers=headers),
                             timeout=30) as response:
                answer = _LoginPage()
                answer.feed(response.read().decode("utf-8", "replace"))
        except _Callback as callback:
            return _exchange(opener, auth_url, callback.url, state, verifier)
        reason = " ".join(answer.alert) or "the sign-in page came back without a message"
        raise AuthError(f"sign-in as '{username}' failed: {reason}")
    except urllib.error.HTTPError as failed:
        raise AuthError(f"{failed.url} -> {failed.code}: {failed.read().decode('utf-8', 'replace')[:300]}") from None
    except urllib.error.URLError as failed:
        raise AuthError(f"sign-in service {auth_url} is not reachable: {failed.reason}") from None


def _exchange(opener, auth_url: str, callback_url: str, state: str, verifier: str) -> dict:
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(callback_url).query)
    if params.get("state") != [state]:
        raise AuthError("the callback carries a different state")
    if "error" in params:
        raise AuthError(f"the sign-in service refused: {params['error'][0]}")
    form = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": CLIENT_ID,
                                   "code": params["code"][0], "redirect_uri": REDIRECT_URI,
                                   "code_verifier": verifier}).encode()
    request = urllib.request.Request(f"{auth_url}/oauth2/token", data=form, headers={"Accept": "application/json"})
    with opener.open(request, timeout=30) as response:
        return json.load(response)


class TokenSource:
    """One user's access token, signed in again shortly before it expires (one sign-in per lifetime)."""

    def __init__(self, username: str, password: str, auth_url: str = AUTH_URL):
        self.username = username
        self.password = password
        self.auth_url = auth_url
        self._token: str | None = None
        self._expires = 0.0

    def token(self) -> str:
        if self._token is None or time.time() > self._expires - 30:
            answer = login(self.username, self.password, self.auth_url)
            self._token = answer["access_token"]
            self._expires = time.time() + answer.get("expires_in", 300)
        return self._token


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    token = commands.add_parser("token", help="print an access token of a demo user")
    token.add_argument("--user", default="admin", help="admin, krentsin, nepyivoda or ADMIN/TEACHER/TEACHER2")
    args = parser.parse_args(argv)
    try:
        username, password = demo_user(args.user)
        print(login(username, password)["access_token"])
    except AuthError as failed:
        print(f"rozklad_auth: {failed}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
