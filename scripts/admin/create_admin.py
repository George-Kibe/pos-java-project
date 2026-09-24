#!/usr/bin/env python3
"""
Creates an administrator: the SUPER_ADMIN role - every permission there is, every branch - on
every active branch, so the branch switcher lists them all.

    make admin email=jane@realhive.co.ke name="Jane Wambui"

The password is a temporary one, replaced at first sign-in, as for every account an administrator
creates. It is taken from ADMIN_PASSWORD if set, asked for (twice, unechoed) at a terminal, and
otherwise generated and shown once. Signs in as the bootstrap administrator from .env to do this;
no email is sent.
"""

from __future__ import annotations

import argparse
import getpass
import re
import secrets
import string
import sys
import os

from common import ApiError, Gateway, gateway_url, read_env

MIN_PASSWORD = 12


def choose_password() -> tuple[str, bool]:
    """The temporary password, and whether it was generated (and so must be shown)."""
    given = os.environ.get("ADMIN_PASSWORD")
    if given:
        return given, False
    if sys.stdin.isatty():
        first = getpass.getpass("Temporary password (12+ characters; Enter to generate one): ")
        if first:
            if getpass.getpass("Again: ") != first:
                sys.exit("The two passwords differ.")
            return first, False
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(16)) + "-Rh7", True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--email", required=True)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()

    email, name = args.email.strip(), args.name.strip()
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
        sys.exit(f"Not an email address: {email!r}. Usage: make admin email=... name=\"...\"")
    if not name:
        sys.exit('A name is required. Usage: make admin email=... name="..."')

    password, generated = choose_password()
    if len(password) < MIN_PASSWORD:
        sys.exit(f"The password must be at least {MIN_PASSWORD} characters.")

    env = read_env()
    for key in ("AUTH_BOOTSTRAP_EMAIL", "AUTH_BOOTSTRAP_PASSWORD"):
        if not env.get(key):
            sys.exit(f"{key} is not set in .env: the bootstrap administrator creates the account.")

    base = Gateway(gateway_url(env))
    try:
        bootstrap = base.sign_in(env["AUTH_BOOTSTRAP_EMAIL"], env["AUTH_BOOTSTRAP_PASSWORD"])
    except (ApiError, OSError) as error:
        sys.exit(f"Could not sign in as the bootstrap administrator ({error}). Is the stack up (make up)?")

    try:
        user = bootstrap.post(
            "/api/v1/users",
            {"email": email, "temporaryPassword": password, "fullName": name, "roles": ["SUPER_ADMIN"]},
        )
    except ApiError as error:
        if error.status == 409:
            sys.exit(f"{email} already has an account. Change its roles from the back office instead.")
        sys.exit(f"The account was not created: {error}")

    branches = [branch["id"] for branch in bootstrap.get("/api/v1/branches") if branch.get("active", True)]  # type: ignore[union-attr]
    bootstrap.put(f"/api/v1/users/{user['id']}/branches", {"branchIds": branches})  # type: ignore[index]
    created = bootstrap.get(f"/api/v1/users/{user['id']}")  # type: ignore[index]

    print(f"Administrator created: {created['fullName']} <{created['email']}>")  # type: ignore[index]
    print(f"  Role:     {', '.join(created['roles'])} - every permission, every branch")  # type: ignore[index]
    print(f"  Branches: {len(branches)} assigned")
    if generated:
        print(f"  Temporary password (shown once, not stored anywhere): {password}")
    print("  The password is temporary: it must be changed at first sign-in.")
    print(f"  Prove the rights: make admin-check email={email}")


if __name__ == "__main__":
    main()
