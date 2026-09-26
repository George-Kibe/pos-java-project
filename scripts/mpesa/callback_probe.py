#!/usr/bin/env python3
"""
Sends Daraja-shaped STK callbacks to a POS address and checks how each is answered - the path
Safaricom takes, without waiting for Safaricom:

    make mpesa-callback-probe                          (MPESA_CALLBACK_URL from .env: the tunnel)
    make mpesa-callback-probe url=http://localhost:8080

1. a wrong token          must answer 404 (the endpoint does not admit it exists)
2. the right token        must answer 200 {"ResultCode": 0} - a *cancelled* payment for a
                          CheckoutRequestID no push ever made, so no sale is marked paid
3. the same one again     must answer 200 again (a duplicate callback is a no-op)

Step 2 leaves one parked callback in payment-service (logged "parked until its push is
recorded"), so run it against development, not production. `--wrong-token-only` runs step 1
alone, which is safe anywhere.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

STK_PATH = "/api/v1/payments/mpesa/callbacks/stk/"


def read_env(path: str = ".env") -> dict[str, str]:
    """Parses .env without a shell, dropping an inline ' #' comment from an unquoted value."""
    values: dict[str, str] = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        elif value.startswith("#"):
            value = ""
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        values[key.strip()] = value
    return values


def post(url: str, body: dict) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="POST",
        # ngrok's free plan puts a browser warning page in front of requests without this;
        # Daraja's own calls are not browsers and never see it.
        headers={"Content-Type": "application/json", "ngrok-skip-browser-warning": "1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def cancelled_callback(checkout_request_id: str) -> dict:
    # The shape Daraja sends when the customer dismisses the prompt (ResultCode 1032).
    return {
        "Body": {
            "stkCallback": {
                "MerchantRequestID": "PROBE-" + checkout_request_id,
                "CheckoutRequestID": checkout_request_id,
                "ResultCode": 1032,
                "ResultDesc": "Request cancelled by user",
            }
        }
    }


def check(label: str, status: int, body: str, expected: int) -> bool:
    ok = status == expected
    print(f"  {'ok  ' if ok else 'FAIL'} {label}: {status} {body[:120]}")
    return ok


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", help="base address; defaults to MPESA_CALLBACK_URL from .env")
    parser.add_argument("--wrong-token-only", action="store_true")
    args = parser.parse_args()

    env = read_env()
    base = (args.url or env.get("MPESA_CALLBACK_URL") or "").rstrip("/")
    token = env.get("MPESA_CALLBACK_TOKEN", "")
    if not base:
        sys.exit("No address: set MPESA_CALLBACK_URL in .env or pass url=...")
    if not token and not args.wrong_token_only:
        sys.exit("MPESA_CALLBACK_TOKEN is empty in .env")

    print(f"Probing {base}{STK_PATH}...")
    probe_id = "ws_CO_PROBE_" + time.strftime("%Y%m%d%H%M%S")
    results = [check("wrong token", *post(base + STK_PATH + "not-the-token", cancelled_callback(probe_id)), 404)]
    if not args.wrong_token_only:
        body = cancelled_callback(probe_id)
        results.append(check("right token", *post(base + STK_PATH + token, body), 200))
        results.append(check("duplicate", *post(base + STK_PATH + token, body), 200))
        print(f"  CheckoutRequestID used: {probe_id}")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
