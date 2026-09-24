#!/usr/bin/env python3
"""
Seed the running stack with demo data, through its public APIs.

Everything goes through the gateway, exactly as the apps would, so every service and every read
model stays consistent: products reach inventory and sales by event, stock arrives by goods
receipt, sales flow into reporting and loyalty. Nothing is written to a database directly.

Every record is marked so `make demo-clear` can remove exactly what this created and nothing else:
branch, supplier, role and product codes start with DEMO, and every email ends @demo.pos.local.

Run through `make demo-seed`, which switches notification-service to capture mode first so the
demo users' and customers' emails are never actually sent.

Standard library only; Python 3.10+.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

DEMO_DOMAIN = "demo.pos.local"
# Demo accounts only - documented in scripts/demo/README.md. Never a real person's password.
DEMO_PASSWORD = "Demo-Password-2026"
TEMP_PASSWORD = "Temp-Demo-Password-2026"

BRANCHES = [
    ("DEMO-NBO", "Demo Nairobi CBD"),
    ("DEMO-WST", "Demo Westlands"),
    ("DEMO-MSA", "Demo Mombasa Nyali"),
    ("DEMO-KSM", "Demo Kisumu Mega"),
    ("DEMO-NKR", "Demo Nakuru Town"),
]

# (sku suffix, name, category, tax class, unit, price incl. tax where taxed, sell by weight)
PRODUCTS = [
    ("001", "Maize flour 2kg", "GROCERY", "ZERO_RATED", "EA", 210, False),
    ("002", "Wheat flour 2kg", "GROCERY", "ZERO_RATED", "EA", 245, False),
    ("003", "White sugar 1kg", "GROCERY", "STANDARD", "EA", 190, False),
    ("004", "Long grain rice 2kg", "GROCERY", "STANDARD", "EA", 420, False),
    ("005", "Cooking oil 1L", "GROCERY", "STANDARD", "EA", 345, False),
    ("006", "Tea leaves 500g", "BEVERAGES", "STANDARD", "EA", 275, False),
    ("007", "Instant coffee 100g", "BEVERAGES", "STANDARD", "EA", 480, False),
    ("008", "Mineral water 1.5L", "BEVERAGES", "STANDARD", "EA", 90, False),
    ("009", "Orange juice 1L", "BEVERAGES", "STANDARD", "EA", 260, False),
    ("010", "Fresh milk 500ml", "DAIRY", "EXEMPT", "EA", 65, False),
    ("011", "Natural yoghurt 500ml", "DAIRY", "STANDARD", "EA", 180, False),
    ("012", "Salted butter 250g", "DAIRY", "STANDARD", "EA", 395, False),
    ("013", "White bread 400g", "BAKERY", "ZERO_RATED", "EA", 65, False),
    ("014", "Brown bread 600g", "BAKERY", "ZERO_RATED", "EA", 85, False),
    ("015", "Bananas", "FRESH", "EXEMPT", "KG", 120, True),
    ("016", "Tomatoes", "FRESH", "EXEMPT", "KG", 140, True),
    ("017", "Beef steak", "BUTCHERY", "EXEMPT", "KG", 780, True),
    ("018", "Laundry bar soap 800g", "HOUSEHOLD", "STANDARD", "EA", 230, False),
    ("019", "Toilet paper 10-pack", "HOUSEHOLD", "STANDARD", "EA", 520, False),
    ("020", "Dishwashing liquid 750ml", "HOUSEHOLD", "STANDARD", "EA", 210, False),
]

FIRST = ["Wanjiku", "Otieno", "Achieng", "Kamau", "Njeri", "Mutua", "Chebet", "Kiprono", "Akinyi",
         "Mwangi", "Wambui", "Ouma", "Nyambura", "Kiptoo", "Atieno", "Karanja", "Jepkosgei", "Omondi",
         "Wairimu", "Barasa"]
LAST = ["Kariuki", "Odhiambo", "Wekesa", "Mutiso", "Kiplagat", "Njoroge", "Adhiambo", "Maina",
        "Chege", "Owino", "Kilonzo", "Rotich", "Gitau", "Ochieng", "Nduta", "Macharia", "Keter",
        "Wanyama", "Muthoni", "Onyango"]

SUPPLIERS = ["Mombasa Millers", "Rift Valley Dairies", "Lake Basin Foods", "Highlands Bakers",
             "Coast Beverages", "Central Farmers Co-op", "Nairobi Household Supplies",
             "Kericho Tea Packers", "Eastlands Wholesalers", "Savannah Meats", "Tana Fresh Produce",
             "Mount Kenya Mills", "Western Sugar Traders", "Pwani Oil Refiners",
             "Great Lakes Distributors", "Athi River Paper", "Naivasha Growers",
             "Thika Juice Works", "Kitengela Cleaning Products", "Kiambu Agro Traders"]

# Staff: (role, how many). Twenty people.
STAFF = [("BRANCH_MANAGER", 3), ("SUPERVISOR", 3), ("CASHIER", 9), ("STOCK_CONTROLLER", 2),
         ("ACCOUNTANT", 1), ("AUDITOR", 1), ("DEMO_NIGHT_MANAGER", 1)]

rng = random.Random(2026)


def money(value: float | Decimal) -> float:
    return float(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def ean13(body12: str) -> str:
    """A valid EAN-13 from 12 digits (the 2x prefixes are GS1's in-store range: no real product)."""
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body12))
    return body12 + str((10 - total % 10) % 10)


class ApiError(Exception):
    def __init__(self, method: str, path: str, status: int, body: str):
        super().__init__(f"{method} {path} -> {status}: {body[:400]}")
        self.status = status
        self.body = body


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None

    def call(self, method: str, path: str, body=None, *, token: str | None = None,
             expect=(200, 201, 202, 204), retries: int = 8):
        url = self.base + path
        data = json.dumps(body).encode() if body is not None else None
        for attempt in range(retries):
            req = urllib.request.Request(url, data=data, method=method)
            req.add_header("Accept", "application/json")
            if data is not None:
                req.add_header("Content-Type", "application/json")
            bearer = token if token is not None else self.token
            if bearer:
                req.add_header("Authorization", f"Bearer {bearer}")
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    raw = resp.read().decode()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as e:
                raw = e.read().decode(errors="replace")
                if e.code == 429:
                    wait = int(e.headers.get("Retry-After") or 5)
                    log(f"    rate limited; waiting {wait}s")
                    time.sleep(wait)
                    continue
                if e.code in (502, 503, 504) and attempt < retries - 1:
                    time.sleep(2)
                    continue
                if e.code in expect:
                    return json.loads(raw) if raw else None
                raise ApiError(method, path, e.code, raw) from None
            except urllib.error.URLError:
                if attempt < retries - 1:
                    time.sleep(2)
                    continue
                raise
        raise ApiError(method, path, 429, "still rate limited after retries")

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        # No body at all for an action endpoint (submit, approve, post...): an empty JSON object
        # would be a content type such an endpoint does not accept.
        return self.call("POST", path, body, **kw)

    def put(self, path, body, **kw):
        return self.call("PUT", path, body, **kw)

    def login(self, email: str, password: str) -> str:
        return self.post("/api/v1/auth/login", {"email": email, "password": password}, token="")[
            "accessToken"]


def log(message: str) -> None:
    print(message, flush=True)


def wait_for(description: str, check, timeout: float = 60, interval: float = 1.0):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            result = check()
            if result:
                return result
        except ApiError as e:
            last_error = e
        time.sleep(interval)
    raise SystemExit(f"Timed out waiting for {description}" + (f": {last_error}" if last_error else ""))


# ---------------------------------------------------------------------------------------------


def seed(api: Api, admin_email: str, admin_password: str, env_out: Path | None) -> None:
    api.token = api.login(admin_email, admin_password)
    admin = api.get("/api/v1/auth/me")

    existing = [b for b in api.get("/api/v1/branches") if b["code"].startswith("DEMO")]
    if existing:
        raise SystemExit("Demo data is already present. Run `make demo-clear` first, then seed again.")

    # --- branches, roles, staff ---------------------------------------------------------------
    log("Branches")
    branches = [api.post("/api/v1/branches", {"code": c, "name": n, "timezone": "Africa/Nairobi"})
                for c, n in BRANCHES]
    branch_ids = [b["id"] for b in branches]

    # The administrator works across the demo branches too (and signs in again: an assignment
    # ends the sessions it had).
    api.put(f"/api/v1/users/{admin['id']}/branches",
            {"branchIds": sorted(set(admin["branchIds"]) | set(branch_ids))})
    api.token = api.login(admin_email, admin_password)

    log("Roles")
    api.post("/api/v1/roles", {"code": "DEMO_NIGHT_MANAGER", "name": "Demo night manager",
                               "description": "Runs the late shift: tills, voids, refunds, stock",
                               "permissions": ["product:view", "inventory:view", "shift:open",
                                               "shift:close", "shift:close:any", "cash:drop",
                                               "cart:manage", "sale:create", "sale:void",
                                               "sale:refund", "payment:take", "customer:view",
                                               "report:view:branch"]})
    api.post("/api/v1/roles", {"code": "DEMO_PROMOTIONS_CLERK", "name": "Demo promotions clerk",
                               "description": "Keeps prices and promotions current",
                               "permissions": ["product:view", "price:manage", "promotion:manage"]})

    log("Staff (20)")
    staff: list[dict] = []
    n = 0
    for role, count in STAFF:
        for _ in range(count):
            first, last = FIRST[n], LAST[n]
            email = f"{first.lower()}.{last.lower()}@{DEMO_DOMAIN}"
            home = branch_ids[n % len(branch_ids)]
            assigned = branch_ids if role in ("ACCOUNTANT", "AUDITOR") else [home]
            user = api.post("/api/v1/users", {
                "email": email, "temporaryPassword": TEMP_PASSWORD,
                "fullName": f"{first} {last}", "phone": f"07{rng.randint(10000000, 99999999)}",
                "roles": [role], "branchIds": assigned})
            staff.append({"id": user["id"], "email": email, "role": role, "branch": home,
                          "name": f"{first} {last}"})
            n += 1
    # Each sets the demo password, so the demo accounts sign straight in.
    for person in staff:
        token = api.login(person["email"], TEMP_PASSWORD)
        api.post("/api/v1/auth/change-password",
                 {"currentPassword": TEMP_PASSWORD, "newPassword": DEMO_PASSWORD}, token=token)
    # One demo cashier is suspended, so the status appears in the list too.
    suspended = next(p for p in staff if p["role"] == "CASHIER")
    api.put(f"/api/v1/users/{suspended['id']}/status", {"status": "SUSPENDED"},
            expect=(200, 204))

    # --- catalogue ------------------------------------------------------------------------------
    log("Products (20)")
    units = {u["code"]: u["id"] for u in api.get("/api/v1/units-of-measure")}
    taxes = {t["code"]: t["id"] for t in api.get("/api/v1/tax-classes")}
    categories = {c["code"]: c["id"] for c in api.get("/api/v1/categories")}
    products = []
    for suffix, name, category, tax, unit, price, weighed in PRODUCTS:
        product = api.post("/api/v1/products", {
            "sku": f"DEMO-{suffix}", "name": name, "description": f"Demo product: {name}",
            "categoryId": categories[category], "unitOfMeasureId": units[unit],
            "taxClassId": taxes[tax], "sellByWeight": weighed, "priceIncludesTax": True,
            "basePrice": price, "reorderPoint": 15, "reorderQuantity": 60, "active": True,
            # 29: GS1's in-store range, clear of the 20/21 prefixes catalog reads as scale labels.
            "barcodes": [ean13(f"2990000{suffix}00")]})
        products.append({"id": product["id"], "sku": f"DEMO-{suffix}", "name": name,
                         "price": price, "weighed": weighed, "tax": tax})

    # --- purchasing: suppliers, orders, receipts, invoices, returns ----------------------------
    log("Suppliers (20) and their products")
    suppliers = []
    for i, name in enumerate(SUPPLIERS):
        supplier = api.post("/api/v1/suppliers", {
            "code": f"DEMO-SUP-{i + 1:02d}", "name": name, "contactName": f"{FIRST[i]} {LAST[-i - 1]}",
            "email": f"orders{i + 1:02d}@{DEMO_DOMAIN}", "phone": f"07{rng.randint(10000000, 99999999)}",
            "address": f"P.O. Box {rng.randint(1000, 99999)}, Nairobi", "taxIdentifier": f"P05{rng.randint(1000000, 9999999)}X",
            "paymentTermsDays": rng.choice([14, 30, 45]), "leadTimeDays": rng.choice([2, 3, 5, 7]),
            "currency": "KES", "notes": "Demo supplier"})
        # Four products each, overlapping, so every product has a supplier.
        lines = [products[(i + k) % len(products)] for k in range(4)]
        for p in lines:
            api.post(f"/api/v1/suppliers/{supplier['id']}/products", {
                "productId": p["id"], "sku": p["sku"], "productName": p["name"],
                "supplierSku": f"S{i + 1:02d}-{p['sku']}", "agreedUnitCost": money(p["price"] * 0.68),
                "minimumOrderQty": 10, "leadTimeDays": 3, "preferred": True})
        suppliers.append({"id": supplier["id"], "products": lines})

    log("Purchase orders (20), goods receipts (20), supplier invoices (20)")
    today = dt.date.today()
    receipts = []
    for i, supplier in enumerate(suppliers):
        branch = branch_ids[i % len(branch_ids)]
        order_lines = []
        for p in supplier["products"]:
            qty = rng.choice([30, 40, 50]) if p["weighed"] else rng.choice([60, 80, 100, 120])
            order_lines.append({"productId": p["id"], "sku": p["sku"], "productName": p["name"],
                                "quantity": qty, "unitCost": money(p["price"] * 0.68), "taxRate": 0})
        po = api.post("/api/v1/purchase-orders", {
            "supplierId": supplier["id"], "branchId": branch,
            "expectedDeliveryDate": str(today + dt.timedelta(days=2)), "notes": "Demo order",
            "lines": order_lines})
        api.post(f"/api/v1/purchase-orders/{po['id']}/submit")
        api.post(f"/api/v1/purchase-orders/{po['id']}/approve")
        api.post(f"/api/v1/purchase-orders/{po['id']}/send")

        receipt_lines = []
        for k, line in enumerate(order_lines):
            # A few batches lapse within the week, so near-expiry has something to show.
            days = 5 if (i + k) % 7 == 0 else rng.choice([45, 90, 180, 365])
            receipt_lines.append({"productId": line["productId"], "sku": line["sku"],
                                  "productName": line["productName"],
                                  "quantityReceived": line["quantity"], "quantityRejected": 0,
                                  "unitCost": line["unitCost"],
                                  "batchNumber": f"DEMO-B{i + 1:02d}{k + 1}",
                                  "expiryDate": str(today + dt.timedelta(days=days))})
        freight = rng.choice([0, 0, 1500, 2500])
        grn = api.post("/api/v1/goods-receipts", {
            "supplierId": supplier["id"], "branchId": branch, "purchaseOrderId": po["id"],
            "deliveryNoteRef": f"DN-DEMO-{i + 1:03d}", "freightAmount": freight, "dutyAmount": 0,
            "allocationBasis": "BY_VALUE", "notes": "Demo delivery", "lines": receipt_lines})
        api.post(f"/api/v1/goods-receipts/{grn['id']}/post")
        receipts.append({"id": grn["id"], "po": po["id"], "supplier": supplier["id"],
                         "branch": branch, "lines": receipt_lines})

        # Invoices: most match; every sixth bills a little more than the order and needs a decision.
        overbill = 1.04 if i % 6 == 5 else 1.0
        invoice_lines = [{"productId": l["productId"], "sku": l["sku"], "quantity": l["quantityReceived"],
                          "unitCost": money(l["unitCost"] * overbill)} for l in receipt_lines]
        net = money(sum(l["quantity"] * l["unitCost"] for l in invoice_lines))
        invoice = api.post("/api/v1/supplier-invoices", {
            "supplierId": supplier["id"], "invoiceNumber": f"DEMO-INV-{i + 1:03d}",
            "invoiceDate": str(today), "netAmount": net, "taxAmount": 0,
            "purchaseOrderId": po["id"], "grnId": grn["id"], "lines": invoice_lines})
        status = invoice.get("status") or invoice.get("matchStatus") or ""
        if overbill > 1 and i % 12 == 5:
            api.post(f"/api/v1/supplier-invoices/{invoice['id']}/dispute",
                     {"reason": "Demo: billed above the agreed cost"})
        elif overbill > 1:
            api.post(f"/api/v1/supplier-invoices/{invoice['id']}/accept-exception",
                     {"reason": "Demo: supplier's new price list accepted"})
            api.post(f"/api/v1/supplier-invoices/{invoice['id']}/approve-for-payment")
        elif "EXCEPTION" not in str(status):
            api.post(f"/api/v1/supplier-invoices/{invoice['id']}/approve-for-payment")

    log("Supplier returns (3)")
    for n_return, grn in enumerate(receipts[:3]):
        line = grn["lines"][0]
        ret = api.post("/api/v1/supplier-returns", {
            "supplierId": grn["supplier"], "branchId": grn["branch"], "grnId": grn["id"],
            "reasonCode": ["DAMAGED_IN_TRANSIT", "SHORT_DATED", "QUALITY"][n_return],
            "notes": "Demo return", "lines": [{
                "productId": line["productId"], "sku": line["sku"], "productName": line["productName"],
                "batchNumber": line["batchNumber"], "quantity": 5, "unitCost": line["unitCost"]}]})
        api.post(f"/api/v1/supplier-returns/{ret['id']}/send")
        if n_return < 2:
            api.post(f"/api/v1/supplier-returns/{ret['id']}/credit",
                     {"creditNoteRef": f"DEMO-CN-{n_return + 1:03d}"})

    # --- inventory ------------------------------------------------------------------------------
    log("Waiting for the deliveries to reach inventory")
    for grn in receipts:
        for line in grn["lines"]:
            wait_for(f"stock of {line['sku']}",
                     lambda l=line, g=grn: api.get(
                         f"/api/v1/stock/{l['productId']}?branchId={g['branch']}")["quantityOnHand"] > 0)

    log("Adjustments (5), transfers (4), stock takes (2)")
    for k, reason in enumerate(["DAMAGE", "SAMPLE", "THEFT", "EXPIRY", "DAMAGE"]):
        grn = receipts[k]
        line = grn["lines"][1]
        adj = api.post("/api/v1/adjustments", {
            "branchId": grn["branch"], "reasonCode": reason, "notes": f"Demo {reason.lower()}",
            "lines": [{"productId": line["productId"], "sku": line["sku"], "quantityDelta": -2,
                       "notes": "Demo"}]})
        api.post(f"/api/v1/adjustments/{adj['id']}/post", expect=(200, 204, 409))

    for k in range(4):
        grn = receipts[k + 5]
        line = grn["lines"][0]
        target = branch_ids[(branch_ids.index(grn["branch"]) + 1) % len(branch_ids)]
        transfer = api.post("/api/v1/transfers", {
            "reference": f"DEMO-TRF-{k + 1:03d}", "fromBranchId": grn["branch"], "toBranchId": target,
            "notes": "Demo rebalancing", "lines": [{"productId": line["productId"], "sku": line["sku"],
                                                    "quantity": 10}]})
        if k < 3:
            dispatched = api.post(f"/api/v1/transfers/{transfer['id']}/dispatch")
            if k < 2:
                lines = (dispatched or transfer).get("lines", [])
                api.post(f"/api/v1/transfers/{transfer['id']}/receive", {
                    "lines": [{"lineId": l["id"], "quantityReceived": l.get("quantitySent") or 10}
                              for l in lines]})

    for k in range(2):
        take = api.post("/api/v1/stock-takes", {"reference": f"DEMO-ST-{k + 1:03d}",
                                                "branchId": branch_ids[k], "notes": "Demo cycle count"})
        take = api.get(f"/api/v1/stock-takes/{take['id']}")
        counts = [{"stockItemId": l["stockItemId"],
                   "countedQuantity": max(0, float(l["snapshotQuantity"]) - (1 if j % 4 == 0 else 0)),
                   "notes": "Demo count"} for j, l in enumerate(take["lines"])]
        if counts:
            api.post(f"/api/v1/stock-takes/{take['id']}/counts", {"counts": counts})
            api.post(f"/api/v1/stock-takes/{take['id']}/review")
            if k == 0:
                api.post(f"/api/v1/stock-takes/{take['id']}/post")

    # --- customers ------------------------------------------------------------------------------
    log("Customers (20), addresses and consents")
    customers = []
    for i in range(20):
        first, last = FIRST[-i - 1], LAST[i]
        customer = api.post("/api/v1/customers", {
            "firstName": first, "lastName": last, "phone": f"07{rng.randint(10000000, 99999999)}",
            "email": f"{first.lower()}.{last.lower()}.member@{DEMO_DOMAIN}",
            "cardNumber": f"DEMO{7000000000 + i}",
            "dateOfBirth": str(dt.date(1970 + i, (i % 12) + 1, (i % 27) + 1)),
            "notes": "Demo member"})
        api.post(f"/api/v1/customers/{customer['id']}/addresses", {
            "label": "Home", "line1": f"House {rng.randint(1, 300)}, Estate Road {i + 1}",
            "town": ["Nairobi", "Mombasa", "Kisumu", "Nakuru", "Eldoret"][i % 5],
            "county": ["Nairobi", "Mombasa", "Kisumu", "Nakuru", "Uasin Gishu"][i % 5], "makeDefault": True})
        api.post(f"/api/v1/customers/{customer['id']}/consents", {
            "channel": "DATA_PROCESSING", "granted": True, "source": "DEMO", "note": "Signed up in store"})
        api.post(f"/api/v1/customers/{customer['id']}/consents", {
            "channel": "MARKETING_SMS", "granted": i % 3 != 0, "source": "DEMO"})
        customers.append(customer["id"])
    for i, customer_id in enumerate(customers[:6]):
        api.post("/api/v1/loyalty/adjustments", {"customerId": customer_id, "points": 300 + 50 * i,
                                                 "reason": "Demo: welcome bonus"})

    # --- tills and sales ------------------------------------------------------------------------
    log("Shifts and sales")
    cashiers = [p for p in staff if p["role"] == "CASHIER" and p is not suspended][:8]
    cashier_tokens = {p["id"]: api.login(p["email"], DEMO_PASSWORD) for p in cashiers}
    sale_count = 0
    shifts = []
    returns_made = 0
    for s_index, cashier in enumerate(cashiers):
        token = cashier_tokens[cashier["id"]]
        branch = cashier["branch"]
        # Checked as the administrator: a cashier sells, but does not browse stock.
        stocked = [p for p in products
                   if float((api.get(f"/api/v1/stock/{p['id']}?branchId={branch}",
                                     expect=(200, 404)) or {}).get("quantityAvailable") or 0) > 5]
        if not stocked:
            continue
        shift = api.post("/api/v1/till-sessions", {"branchId": branch, "registerId": str(uuid.uuid4()),
                                                   "openingFloat": 5000}, token=token)
        shifts.append(shift["id"])
        sales_in_shift = []
        for n_sale in range(3):
            member = customers[(s_index * 3 + n_sale) % len(customers)] if n_sale != 1 else None
            cart = api.post("/api/v1/carts", {"tillSessionId": shift["id"], "customerId": member,
                                              "member": member is not None}, token=token)
            for p in rng.sample(stocked, k=min(len(stocked), rng.randint(1, 4))):
                qty = round(rng.uniform(0.5, 2.5), 3) if p["weighed"] else rng.randint(1, 3)
                api.post(f"/api/v1/carts/{cart['id']}/lines",
                         {"productId": p["id"], "quantity": qty, "weighed": p["weighed"]}, token=token)
            sale = api.post("/api/v1/sales/checkout", {"cartId": cart["id"]}, token=token)
            total = float(sale["grandTotal"])
            method = ["CASH", "CARD", "CASH"][n_sale] if not (member and n_sale == 2 and s_index < 4) else "LOYALTY"
            if method == "CASH":
                tendered = float((Decimal(str(total)) / 50).to_integral_value(rounding="ROUND_CEILING") * 50)
                api.post(f"/api/v1/sales/{sale['id']}/tender",
                         {"tenders": [{"method": "CASH", "amount": total}], "amountTendered": tendered},
                         token=token)
            elif method == "CARD":
                api.post(f"/api/v1/sales/{sale['id']}/tender",
                         {"tenders": [{"method": "CARD", "amount": total,
                                       "terminalReference": f"DEMO-TERM-{s_index + 1:02d}"}]}, token=token)
                # The payment reaches the terminal asynchronously: capture once it is waiting there.
                intent = wait_for("the card payment to await capture", lambda: next(
                    (i for i in api.get(f"/api/v1/payments/sales/{sale['id']}", token=token) or []
                     if i["status"] == "AWAITING_CAPTURE"), None))
                api.post(f"/api/v1/payments/{intent['id']}/capture",
                         {"approvalCode": f"{rng.randint(100000, 999999)}",
                          "terminalReference": f"DEMO-TERM-{s_index + 1:02d}"}, token=token)
            else:
                points = min(200.0, money(total))
                cash = money(total - points)
                tenders = [{"method": "LOYALTY", "amount": points}]
                if cash > 0:
                    tenders.append({"method": "CASH", "amount": cash})
                api.post(f"/api/v1/sales/{sale['id']}/tender",
                         {"tenders": tenders, "amountTendered": cash if cash > 0 else None}, token=token)
            paid = wait_for("the sale to be paid", lambda: (
                s := api.get(f"/api/v1/sales/{sale['id']}", token=token))["status"] in ("PAID", "CANCELLED") and s)
            sales_in_shift.append(paid)
            sale_count += 1

        # Supervisor work, done by the administrator: a void in some shifts, a cash refund in others.
        paid_sales = [s for s in sales_in_shift if s["status"] == "PAID"]
        if s_index % 3 == 0 and paid_sales:
            api.post(f"/api/v1/sales/{paid_sales[-1]['id']}/void", {"reason": "Demo: rung up twice"})
        elif returns_made < 3 and paid_sales and paid_sales[0]["lines"]:
            line = paid_sales[0]["lines"][0]
            api.post("/api/v1/returns", {
                "saleId": paid_sales[0]["id"], "reason": "CHANGED_MIND", "refundMethod": "CASH",
                "notes": "Demo return", "tillSessionId": shift["id"],
                "lines": [{"saleLineId": line["id"], "quantity": min(1, float(line["quantity"])),
                           "resaleable": True}]})
            returns_made += 1

        # A safe drop is a supervisor's job (cash:drop), so the administrator makes it.
        api.post(f"/api/v1/till-sessions/{shift['id']}/drops",
                 {"amount": 1000, "reason": "Demo safe drop", "reference": f"DEMO-BAG-{s_index + 1}"})
        # All but the last shift close, a little over or short; the last stays open (an X-report).
        if s_index < len(cashiers) - 1:
            closing = api.post(f"/api/v1/till-sessions/{shift['id']}/begin-close", token=token)
            counted = money(float(closing["expectedCash"]) + rng.choice([0, 0, -20, 10, -5]))
            api.post(f"/api/v1/till-sessions/{shift['id']}/close",
                     {"countedCash": counted, "notes": "Demo close"}, token=token)

    # --- reporting and payments -----------------------------------------------------------------
    # (No payment reconciliation run: it reconciles an uploaded M-Pesa statement, and the demo
    # takes cash, card and loyalty only.)
    log("Stock valuations")
    for branch in branch_ids:
        api.post(f"/api/v1/stock/valuations?branchId={branch}")

    log(f"\nSeeded: {len(branches)} branches, {len(staff)} staff, {len(products)} products, "
        f"{len(suppliers)} suppliers, {len(receipts)} purchase orders / receipts / invoices, "
        f"{len(customers)} customers, {len(shifts)} shifts, {sale_count} sales.")
    log(f"Demo sign-in: any staff email above (e.g. {staff[0]['email']}) with password {DEMO_PASSWORD}")

    if env_out:
        write_postman_environment(env_out, api.base, branch_ids, products, customers, suppliers,
                                  shifts, staff, api)


def first_id(api: Api, path: str, key: str = "id") -> str:
    """The first record's id from a list endpoint, or "" - best effort, for the environment."""
    try:
        body = api.get(path)
    except ApiError:
        return ""
    rows = body.get("content", []) if isinstance(body, dict) else (body or [])
    return str(rows[0].get(key, "")) if rows else ""


def write_postman_environment(path: Path, base: str, branch_ids, products, customers, suppliers,
                              shifts, staff, api: Api) -> None:
    """The Postman environment for the demo data: every variable the collection uses that a demo
    record can fill, so a request works straight after import."""
    manager = next(p for p in staff if p["role"] == "BRANCH_MANAGER")
    branch = branch_ids[0]
    # A paid sale, so the receipt lookup and the return-eligibility requests have a real one.
    try:
        sales = api.get(f"/api/v1/sales?branchId={branch}&size=100").get("content", [])
    except ApiError:
        sales = []
    paid_sale = next((sale for sale in sales if sale.get("status") == "PAID"), {})
    values = {
        "baseUrl": base,
        "email": manager["email"],
        "password": DEMO_PASSWORD,
        "day": str(dt.date.today()),
        "branchId": branch,
        "fromBranchId": branch_ids[0],
        "toBranchId": branch_ids[1],
        "productId": products[0]["id"],
        "customerId": customers[0],
        "supplierId": suppliers[0]["id"],
        "shiftId": shifts[0] if shifts else "",
        "tillSessionId": shifts[0] if shifts else "",
        "userId": manager["id"],
        "roleId": next((r["id"] for r in api.get("/api/v1/roles") if r["code"].startswith("DEMO")), ""),
        "categoryId": first_id(api, "/api/v1/categories"),
        "taxClassId": first_id(api, "/api/v1/tax-classes"),
        "unitOfMeasureId": first_id(api, "/api/v1/units-of-measure"),
        "purchaseOrderId": first_id(api, f"/api/v1/purchase-orders?branchId={branch}"),
        "goodsReceiptId": first_id(api, f"/api/v1/goods-receipts?branchId={branch}"),
        "supplierInvoiceId": first_id(api, "/api/v1/supplier-invoices"),
        "supplierReturnId": first_id(api, "/api/v1/supplier-returns"),
        "adjustmentId": first_id(api, f"/api/v1/adjustments?branchId={branch}"),
        "saleId": paid_sale.get("id", ""),
        "receiptNumber": paid_sale.get("receiptNumber") or "",
        "returnId": first_id(api, "/api/v1/returns"),
        "paymentId": first_id(api, "/api/v1/payments"),
        "stockItemId": first_id(api, f"/api/v1/stock?branchId={branch}"),
    }
    values["grnId"] = values["goodsReceiptId"]

    # Keep every variable the generated collection uses, blank where the demo has nothing for it.
    template = path.parent / "pos-api.postman_environment.json"
    if template.exists():
        for entry in json.loads(template.read_text())["values"]:
            values.setdefault(entry["key"], entry["value"])
    environment = {
        "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "pos-java-project/demo-env")),
        "name": "POS local (demo data)",
        "values": [{"key": k, "value": v, "type": "secret" if k == "password" else "default",
                    "enabled": True} for k, v in values.items()],
        "_postman_variable_scope": "environment",
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(environment, indent=2) + "\n")
    filled = sum(1 for v in values.values() if v)
    log(f"Postman environment with the demo ids ({filled} of {len(values)} variables filled): {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("GATEWAY_URL", "http://localhost:8080"))
    parser.add_argument("--env-out", type=Path, default=None,
                        help="write a Postman environment holding the seeded ids")
    args = parser.parse_args()
    email = os.environ.get("AUTH_BOOTSTRAP_EMAIL")
    password = os.environ.get("AUTH_BOOTSTRAP_PASSWORD")
    if not email or not password:
        sys.exit("AUTH_BOOTSTRAP_EMAIL and AUTH_BOOTSTRAP_PASSWORD must be set (make demo-seed reads .env).")
    started = time.time()
    seed(Api(args.base_url), email, password, args.env_out)
    log(f"Done in {time.time() - started:.0f}s.")


if __name__ == "__main__":
    main()
