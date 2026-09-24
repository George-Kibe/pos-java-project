#!/usr/bin/env python3
"""
Signs in as an administrator and proves what it can do, service by service, against the running
stack: people, roles, branches and the audit trail; the catalogue; stock at every branch; buying;
sales, shifts and payments at every branch; customers; dashboards per branch; and every report -
by week, month, quarter and year, per branch and across the whole business - with an export.

    make admin-check email=jane@realhive.co.ke        (asks for the password, or ADMIN_PASSWORD)

Reads only: it changes nothing. Exits non-zero if the administrator is refused anything.
"""

from __future__ import annotations

import argparse
import base64
import json
import datetime as dt
import getpass
import os
import sys

from common import ApiError, Gateway, gateway_url, read_env


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--email", required=True)
    args = parser.parse_args()
    password = os.environ.get("ADMIN_PASSWORD") or getpass.getpass(f"Password for {args.email}: ")

    env = read_env()
    try:
        admin = Gateway(gateway_url(env)).sign_in(args.email, password)
    except ApiError as error:
        sys.exit(f"Could not sign in as {args.email}: {error}")

    me = admin.get("/api/v1/auth/me")
    # From /me, which anyone may read: a check that cannot even list branches still has to report.
    branches = me["branches"]  # type: ignore[index]
    today = dt.date.today()
    year_start = today.replace(month=1, day=1).isoformat()
    span = f"from={year_start}&to={today.isoformat()}"

    checks: list[tuple[str, str]] = [
        ("Who am I", "/api/v1/auth/me"),
        ("Every user", "/api/v1/users?size=100"),
        ("Every role", "/api/v1/roles"),
        ("The permission catalogue", "/api/v1/permissions"),
        ("Every branch", "/api/v1/branches"),
        ("The audit trail", "/api/v1/audit?size=20"),
        ("Products", "/api/v1/products?size=20"),
        ("Categories", "/api/v1/categories"),
        ("Tax classes", "/api/v1/tax-classes"),
        ("Suppliers", "/api/v1/suppliers?size=20"),
        ("Customers", "/api/v1/customers?size=20"),
        ("Sales by branch", f"/api/v1/reports/sales/by-branch?{span}"),
        ("Sales by product", f"/api/v1/reports/sales/by-product?{span}"),
        ("Margin by category", f"/api/v1/reports/margin/by-category?{span}"),
        ("Payment mix", f"/api/v1/reports/payment-mix?{span}"),
        ("Report export (CSV)", f"/api/v1/reports/exports/sales-by-branch?{span}&format=csv"),
    ]
    for period in ("WEEK", "MONTH", "QUARTER", "YEAR"):
        checks.append((f"Sales by {period.lower()}, whole business", f"/api/v1/reports/sales/by-period?period={period}&{span}&acrossBranches=true"))
    for branch in branches:  # type: ignore[union-attr]
        b = branch["id"]
        label = branch["code"]
        checks += [
            (f"{label}: stock", f"/api/v1/stock?branchId={b}&size=20"),
            (f"{label}: low stock", f"/api/v1/stock/low?branchId={b}"),
            (f"{label}: stock adjustments", f"/api/v1/adjustments?branchId={b}&size=20"),
            (f"{label}: purchase orders", f"/api/v1/purchase-orders?branchId={b}&size=20"),
            (f"{label}: sales", f"/api/v1/sales?branchId={b}&size=20"),
            (f"{label}: shifts", f"/api/v1/till-sessions?branchId={b}&size=20"),
            (f"{label}: payments", f"/api/v1/payments?branchId={b}&size=20"),
            (f"{label}: dashboard", f"/api/v1/dashboards?branchId={b}"),
            (f"{label}: stock valuation", f"/api/v1/reports/stock/valuation?branchId={b}"),
            (f"{label}: sales by month", f"/api/v1/reports/sales/by-period?period=MONTH&{span}&branchId={b}"),
            (f"{label}: sales by year", f"/api/v1/reports/sales/by-period?period=YEAR&{span}&branchId={b}"),
        ]

    refused, broken, empty = [], [], []
    for label, path in checks:
        try:
            admin.get(path)
        except ApiError as error:
            if error.status in (401, 403):
                refused.append((label, path, error))
            elif error.status >= 500:
                broken.append((label, path, error))
            else:
                # Allowed, and told there is nothing to show yet - e.g. a branch never valued.
                empty.append(label)
        except ValueError:
            pass  # a CSV body, not JSON: answered, which is what is being checked

    # The token's list, not /me's: /me shows the role's grant (the wildcard for SUPER_ADMIN), while
    # the token carries it expanded - and the token is what every service checks.
    payload = admin.token.split(".")[1]  # type: ignore[union-attr]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    permissions = set(claims.get("perms", []))
    try:
        catalogue = {
            entry["code"]
            for group in admin.get("/api/v1/permissions")["byCategory"].values()  # type: ignore[index]
            for entry in group
        }
    except ApiError:
        # Refused the catalogue itself (it is listed as a refusal below); measure against the known core.
        catalogue = {"user:manage", "role:manage", "branch:manage", "audit:view", "report:view", "branch:access:all"}
    print(f"{me['fullName']} <{me['email']}> - roles {', '.join(me['roles'])}")  # type: ignore[index]
    missing = catalogue - permissions
    print(f"  {len(permissions & catalogue)} of {len(catalogue)} permissions in the catalogue held"
          f"{'' if not missing else ' - missing ' + ', '.join(sorted(missing))}")
    print(f"  {len(checks) - len(refused) - len(broken)} of {len(checks)} checks allowed across {len(branches)} branches"  # type: ignore[arg-type]
          f"{f' ({len(empty)} with nothing to show yet)' if empty else ''}")
    for label, path, error in refused:
        print(f"  REFUSED  {label}: {error}")
    for label, path, error in broken:
        print(f"  BROKEN   {label}: {error}")
    if refused or missing:
        sys.exit("The administrator is missing rights.")
    if broken:
        sys.exit("A service failed while answering the administrator.")
    print("  Every right checked is held.")


if __name__ == "__main__":
    main()
