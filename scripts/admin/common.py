"""Shared by the admin scripts: .env reading and a small gateway client. Standard library only."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from pathlib import Path


def read_env(path: str = ".env") -> dict[str, str]:
    """Parses .env without a shell: a value with spaces need not be quoted, and nothing runs."""
    values: dict[str, str] = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


class ApiError(Exception):
    def __init__(self, method: str, path: str, status: int, body: str):
        super().__init__(f"{method} {path} -> {status}: {body[:300]}")
        self.status = status
        self.body = body


class Gateway:
    def __init__(self, base_url: str, token: str | None = None):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def call(self, method: str, path: str, body: object | None = None) -> object:
        request = urllib.request.Request(
            self.base_url + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
        )
        request.add_header("Accept", "application/json")
        if body is not None:
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise ApiError(method, path, error.code, error.read().decode(errors="replace")) from None

    def get(self, path: str) -> object:
        return self.call("GET", path)

    def post(self, path: str, body: object | None = None) -> object:
        return self.call("POST", path, body)

    def put(self, path: str, body: object) -> object:
        return self.call("PUT", path, body)

    def sign_in(self, email: str, password: str) -> "Gateway":
        answer = self.post("/api/v1/auth/login", {"email": email, "password": password})
        return Gateway(self.base_url, answer["accessToken"])  # type: ignore[index]


def gateway_url(env: dict[str, str]) -> str:
    return f"http://localhost:{env.get('GATEWAY_PORT') or '8080'}"
