#!/usr/bin/env python3
"""
Generate a Postman collection (v2.1, which Insomnia also imports) for every API, from the specs
the services publish through the gateway (/v3/api-docs/<service>).

Generated rather than hand-written, so it cannot drift from the code: `make postman` rebuilds it
from the running stack.

    python3 scripts/postman/generate.py [--base-url http://localhost:8080] [--out postman]

Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import urllib.request
import uuid
from pathlib import Path

SERVICES = [
    ("auth-service", "Auth, users, roles and branches"),
    ("catalog-service", "Catalogue and pricing"),
    ("inventory-service", "Inventory"),
    ("purchasing-service", "Purchasing"),
    ("sales-service", "Sales: tills, carts, checkout, returns"),
    ("payment-service", "Payments and refunds"),
    ("customer-service", "Customers and loyalty"),
    ("reporting-service", "Reports and dashboards"),
    ("notification-service", "Notifications"),
]

# Reachable without a token (the gateway's public paths).
PUBLIC = {
    "/api/v1/auth/register", "/api/v1/auth/verify-otp", "/api/v1/auth/resend-otp",
    "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
    "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/.well-known/jwks.json",
}

# Mutating calls a lane terminal makes; the services honour an Idempotency-Key on them.
IDEMPOTENT_PREFIXES = ("/api/v1/carts", "/api/v1/sales", "/api/v1/till-sessions", "/api/v1/returns",
                       "/api/v1/payments", "/api/v1/refunds")

STORE_TOKENS = [
    "// Keep the tokens for every other request in the collection.",
    "const body = pm.response.json();",
    "if (body.accessToken) {",
    "    pm.collectionVariables.set('accessToken', body.accessToken);",
    "    pm.collectionVariables.set('refreshToken', body.refreshToken);",
    "}",
]

SINGULAR = {
    "sales": "sale", "branches": "branch", "categories": "category", "stock-takes": "stockTake",
    "till-sessions": "tillSession", "purchase-orders": "purchaseOrder",
    "goods-receipts": "goodsReceipt", "supplier-invoices": "supplierInvoice",
    "supplier-returns": "supplierReturn", "reconciliation-runs": "reconciliationRun",
    "reorder-suggestions": "reorderSuggestion", "addresses": "address",
}


def camel(text: str) -> str:
    parts = re.split(r"[-_]", text)
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def singular(segment: str) -> str:
    if segment in SINGULAR:
        return SINGULAR[segment]
    word = camel(segment)
    return word[:-1] if word.endswith("s") else word


def path_with_variables(path: str) -> tuple[str, list[str]]:
    """/api/v1/products/{id} -> /api/v1/products/{{productId}}, naming each {id} by its resource."""
    segments = path.strip("/").split("/")
    names = []
    out = []
    for i, segment in enumerate(segments):
        match = re.fullmatch(r"\{(.+)\}", segment)
        if match:
            name = match.group(1)
            if name == "id" and i > 0:
                name = singular(segments[i - 1]) + "Id"
            names.append(name)
            out.append("{{" + name + "}}")
        else:
            out.append(segment)
    return "/" + "/".join(out), names


class Schemas:
    def __init__(self, spec: dict):
        self.components = spec.get("components", {}).get("schemas", {})

    def resolve(self, schema: dict) -> dict:
        seen = 0
        while "$ref" in schema and seen < 20:
            schema = self.components.get(schema["$ref"].split("/")[-1], {})
            seen += 1
        return schema

    def example(self, schema: dict, name: str = "", depth: int = 0):
        schema = self.resolve(schema)
        if depth > 6:
            return None
        if "example" in schema:
            return schema["example"]
        if "enum" in schema:
            return schema["enum"][0]
        for key in ("allOf", "oneOf", "anyOf"):
            if key in schema and schema[key]:
                return self.example(schema[key][0], name, depth + 1)
        kind = schema.get("type")
        if isinstance(kind, list):
            kind = next((k for k in kind if k != "null"), None)
        if kind == "object" or "properties" in schema:
            return {prop: self.example(sub, prop, depth + 1)
                    for prop, sub in schema.get("properties", {}).items()}
        if kind == "array":
            return [self.example(schema.get("items", {}), name, depth + 1)]
        if kind == "boolean":
            return False
        if kind in ("integer", "number"):
            lowered = name.lower()
            if "quantity" in lowered or lowered in ("points", "count"):
                return 1
            if any(w in lowered for w in ("amount", "price", "cost", "total", "float", "cash")):
                return 100
            if "days" in lowered:
                return 30
            return 1
        return self.string_example(schema, name)

    @staticmethod
    def string_example(schema: dict, name: str):
        fmt = schema.get("format")
        lowered = name.lower()
        if fmt == "uuid" or (lowered.endswith("id") and lowered != "id" and fmt != "date"):
            return "{{" + name + "}}"
        if fmt == "date":
            return "{{day}}"
        if fmt == "date-time":
            return "2026-01-01T09:00:00Z"
        if "email" in lowered:
            return "{{email}}"
        if "password" in lowered:
            return "{{password}}"
        if "phone" in lowered:
            return "0712345678"
        if "currency" in lowered:
            return "KES"
        if "reason" in lowered or "note" in lowered:
            return "Recorded from Postman"
        return ""


def fetch(base: str, service: str) -> dict:
    with urllib.request.urlopen(f"{base}/v3/api-docs/{service}", timeout=30) as response:
        return json.load(response)


def query_parameters(parameters: list[dict], schemas: Schemas) -> list[dict]:
    query = []
    for parameter in parameters:
        if parameter.get("in") != "query":
            continue
        name = parameter["name"]
        required = parameter.get("required", False)
        if name == "pageable":
            # Spring's Pageable, flattened into what a client actually sends.
            for key, value in (("page", "0"), ("size", "20"), ("sort", "")):
                query.append({"key": key, "value": value, "disabled": key == "sort"})
            continue
        schema = schemas.resolve(parameter.get("schema", {}))
        if name in ("from", "to", "day", "asOf") or schema.get("format") == "date":
            value = "{{day}}"
        elif name.endswith("Id"):
            value = "{{" + name + "}}"
        elif "default" in schema:
            value = str(schema["default"])
        elif "enum" in schema:
            value = str(schema["enum"][0])
        else:
            value = ""
        query.append({"key": name, "value": value, "disabled": not required,
                      "description": parameter.get("description", "")})
    return query


def request_item(path: str, method: str, operation: dict, schemas: Schemas) -> dict:
    url_path, _ = path_with_variables(path)
    query = query_parameters(operation.get("parameters", []), schemas)
    headers = [{"key": "Accept", "value": "application/json"}]
    body = None
    content = operation.get("requestBody", {}).get("content", {})
    if "application/json" in content:
        headers.append({"key": "Content-Type", "value": "application/json"})
        example = schemas.example(content["application/json"].get("schema", {}))
        body = {"mode": "raw", "raw": json.dumps(example, indent=2),
                "options": {"raw": {"language": "json"}}}
    if method != "get" and path.startswith(IDEMPOTENT_PREFIXES):
        headers.append({"key": "Idempotency-Key", "value": "{{$guid}}",
                        "description": "Makes a retry harmless; a fresh one per call."})

    raw = "{{baseUrl}}" + url_path
    if query:
        raw += "?" + "&".join(f"{q['key']}={q['value']}" for q in query if not q["disabled"])
    request = {
        "method": method.upper(),
        "header": headers,
        "url": {"raw": raw, "host": ["{{baseUrl}}"], "path": url_path.strip("/").split("/"),
                "query": query},
        "description": operation.get("description") or operation.get("summary") or "",
    }
    if body:
        request["body"] = body
    if path in PUBLIC:
        request["auth"] = {"type": "noauth"}
    item = {"name": operation.get("summary") or f"{method.upper()} {path}", "request": request}
    if path in ("/api/v1/auth/login", "/api/v1/auth/refresh"):
        item["event"] = [{"listen": "test", "script": {"type": "text/javascript", "exec": STORE_TOKENS}}]
    return item


def tag_title(tag: str) -> str:
    words = re.sub(r"-controller$", "", tag).replace("-", " ")
    return words[:1].upper() + words[1:]


def build(base: str) -> tuple[dict, int]:
    folders = []
    count = 0
    for service, title in SERVICES:
        spec = fetch(base, service)
        schemas = Schemas(spec)
        by_tag: dict[str, list] = {}
        for path, operations in sorted(spec.get("paths", {}).items()):
            for method in ("get", "post", "put", "patch", "delete"):
                if method in operations:
                    operation = operations[method]
                    tag = (operation.get("tags") or [service])[0]
                    by_tag.setdefault(tag, []).append(request_item(path, method, operation, schemas))
                    count += 1
        if not by_tag:
            continue
        folders.append({
            "name": title,
            "description": f"{service}: {spec.get('info', {}).get('description') or spec.get('info', {}).get('title', '')}",
            "item": [{"name": tag_title(tag), "item": items} for tag, items in sorted(by_tag.items())],
        })

    start = {
        "name": "Start here",
        "description": "Sign in first: the token is stored for every other request.",
        "item": [
            {"name": "Sign in (stores the token)", "event": [{"listen": "test", "script": {
                "type": "text/javascript", "exec": STORE_TOKENS}}],
             "request": {"method": "POST", "auth": {"type": "noauth"},
                         "header": [{"key": "Content-Type", "value": "application/json"}],
                         "body": {"mode": "raw", "raw": json.dumps(
                             {"email": "{{email}}", "password": "{{password}}"}, indent=2),
                             "options": {"raw": {"language": "json"}}},
                         "url": {"raw": "{{baseUrl}}/api/v1/auth/login", "host": ["{{baseUrl}}"],
                                 "path": ["api", "v1", "auth", "login"]}}},
            {"name": "Who am I", "request": {
                "method": "GET", "header": [],
                "url": {"raw": "{{baseUrl}}/api/v1/auth/me", "host": ["{{baseUrl}}"],
                        "path": ["api", "v1", "auth", "me"]}}},
        ],
    }

    collection = {
        "info": {
            "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "pos-java-project/api")),
            "name": "Realhive Group of Supermarkets POS API",
            "description": (
                "Every endpoint of the POS, through the API gateway. Generated from the services' "
                "OpenAPI specs by `make postman`.\n\n"
                "1. Import this collection and an environment (postman/pos-api.postman_environment.json, "
                "or postman/local.postman_environment.json after `make demo-seed`, which holds real ids).\n"
                "2. Run *Start here > Sign in*: the token is stored and used by every other request.\n"
                "3. Ids in paths are variables ({{productId}}, {{saleId}}, ...): set them in the "
                "environment, or from a previous response."),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]},
        "variable": [
            {"key": "accessToken", "value": ""},
            {"key": "refreshToken", "value": ""},
        ],
        "item": [start] + folders,
    }
    return collection, count


def variables_used(collection: dict) -> list[str]:
    text = json.dumps(collection)
    names = set(re.findall(r"\{\{([A-Za-z][A-Za-z0-9]*)\}\}", text))
    return sorted(names - {"accessToken", "refreshToken"})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--out", type=Path, default=Path("postman"))
    args = parser.parse_args()

    collection, count = build(args.base_url.rstrip("/"))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "pos-api.postman_collection.json").write_text(json.dumps(collection, indent=2) + "\n")

    defaults = {"baseUrl": "http://localhost:8080", "day": "2026-01-01"}
    environment = {
        "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "pos-java-project/env")),
        "name": "POS local",
        "values": [{"key": name, "value": defaults.get(name, ""),
                    "type": "secret" if name == "password" else "default", "enabled": True}
                   for name in ["baseUrl"] + [v for v in variables_used(collection) if v != "baseUrl"]],
        "_postman_variable_scope": "environment",
    }
    (args.out / "pos-api.postman_environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    print(f"{count} requests across {len(collection['item']) - 1} services -> {args.out}/")


if __name__ == "__main__":
    main()
