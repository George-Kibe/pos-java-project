#!/usr/bin/env python3
"""
Send every GET in the Postman collection to the running stack and report what came back.

Reads only - nothing is changed. Uses the demo environment written by `make demo-seed`
(postman/local.postman_environment.json) for ids, and signs in as the bootstrap administrator so
permissions do not hide a failure. Exits non-zero on any 5xx: a read that breaks the server is a bug.

    make api-smoke

Standard library only.
"""

from __future__ import annotations

import collections
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

COLLECTION = Path("postman/pos-api.postman_collection.json")
ENVIRONMENT = Path("postman/local.postman_environment.json")


def walk(items):
    for item in items:
        if "item" in item:
            yield from walk(item["item"])
        else:
            yield item


def main() -> None:
    if not ENVIRONMENT.exists():
        sys.exit("No demo environment: run `make demo-seed` first.")
    collection = json.loads(COLLECTION.read_text())
    env = {v["key"]: v["value"] for v in json.loads(ENVIRONMENT.read_text())["values"]}
    email, password = os.environ.get("AUTH_BOOTSTRAP_EMAIL"), os.environ.get("AUTH_BOOTSTRAP_PASSWORD")
    if not email or not password:
        sys.exit("AUTH_BOOTSTRAP_EMAIL and AUTH_BOOTSTRAP_PASSWORD must be set (make api-smoke reads .env).")

    login = urllib.request.Request(env["baseUrl"] + "/api/v1/auth/login", method="POST",
                                   data=json.dumps({"email": email, "password": password}).encode())
    login.add_header("Content-Type", "application/json")
    token = json.loads(urllib.request.urlopen(login, timeout=30).read())["accessToken"]

    statuses: collections.Counter = collections.Counter()
    failures, skipped = [], []
    for item in walk(collection["item"]):
        request = item["request"]
        if request["method"] != "GET":
            continue
        url = request["url"]["raw"]
        missing = [name for name in re.findall(r"\{\{(\w+)\}\}", url) if not env.get(name)]
        if missing:
            skipped.append(f"{item['name']} (needs {', '.join(missing)})")
            continue
        url = re.sub(r"\{\{(\w+)\}\}", lambda m: env[m.group(1)], url)
        get = urllib.request.Request(url)
        get.add_header("Authorization", "Bearer " + token)
        get.add_header("Accept", "application/json")
        try:
            status = urllib.request.urlopen(get, timeout=30).status
        except urllib.error.HTTPError as error:
            status = error.code
            if status >= 400:
                detail = error.read().decode(errors="replace")[:140]
                failures.append((status, url.replace(env["baseUrl"], ""), detail))
        statuses[status] += 1

    print(f"GET requests: {sum(statuses.values())}  by status: {dict(sorted(statuses.items()))}")
    if skipped:
        print(f"Skipped {len(skipped)} whose ids only a live flow produces:")
        for name in skipped:
            print(f"  - {name}")
    for status, path, detail in failures:
        print(f"  {status} {path[:100]}\n        {detail}")
    if any(status >= 500 for status, _, _ in failures):
        sys.exit("A read broke the server (5xx): that is a bug.")


if __name__ == "__main__":
    main()
