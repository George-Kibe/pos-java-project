# Architecture

Decisions, event contracts and flows. Companion to [readme.md](../readme.md) (overview) and
[ROADMAP.md](ROADMAP.md) (build order).

---

## 1. Architecture decision records

Each records what was decided, why, and what it costs.

### ADR-001 — Microservices over a modular monolith
**Decision:** eleven deployable services split by business capability.
**Why:** independent scaling of the checkout path versus reporting; team and deploy independence;
the brief calls for it.
**Cost:** distributed-system complexity — eventual consistency, correlation-ID tracing, more
infrastructure. Mitigated by shared libraries, a strict service template and observability wired in
from Phase 1.

### ADR-002 — Custom `auth-service` rather than Keycloak
**Decision:** own the identity service; issue RS256 JWTs; expose JWKS.
**Why:** the email-OTP registration flow and runtime-editable permission model are first-class
product requirements, which in Keycloak need custom SPIs and themes. One fewer heavy component to
operate.
**Cost:** we own the security-critical code. Mitigated by making every *other* service a standard
OAuth2 resource server validating via JWKS, so an external IdP is a configuration swap, not a
rewrite.

### ADR-003 — Schema per service in one PostgreSQL instance
**Decision:** one instance, one schema and one login role per service, grants scoped to that schema.
**Why:** enforces service boundaries at the database level while keeping local dev, backups and ops
cheap. Any schema can be promoted to its own instance later with a connection-string change.
**Cost:** a shared instance is a shared failure domain and a shared resource pool. Accepted at this
scale; revisit when one service's load justifies separation.

### ADR-004 — Kafka with versioned JSON, not Avro + Schema Registry
**Decision:** JSON payloads in a shared envelope carrying `schemaVersion`; DTOs live in `events-lib`.
**Why:** `events-lib` gives compile-time coupling across Java services, which is where the real
contract safety comes from here. Avoids running and operating a Schema Registry.
**Cost:** no runtime compatibility enforcement, larger messages. Mitigated by additive-only
evolution, a new `.v2` topic for breaking changes, and contract tests.

### ADR-005 — Transactional outbox for publishing
**Decision:** producers write an outbox row in the same transaction as the state change; a relay
publishes to Kafka.
**Why:** publishing inside business logic loses events when the broker is down or the transaction
rolls back after a send. Money and stock cannot tolerate that.
**Cost:** publish latency of up to the poll interval, plus an outbox table per producing service.

### ADR-006 — Idempotent consumers everywhere
**Decision:** every consumer records `eventId` in `processed_event` before acting.
**Why:** Kafka is at-least-once. A redelivered `sale-completed` must not deduct stock twice.
**Cost:** one extra write and table per consuming service. Non-negotiable.

### ADR-007 — Offline-capable lane with server-side revalidation
**Decision:** the terminal queues sales locally and syncs with an `Idempotency-Key`; the server
recomputes totals and reports variances rather than trusting the client.
**Why:** a supermarket cannot stop selling because the network dropped, but it also cannot let a
stale terminal define prices.
**Cost:** a variance workflow, and a window in which stock can go negative. Handled by variance
reporting plus negative-stock detection and alerting.

### ADR-008 — Docker Compose for production, Kubernetes-ready
**Decision:** Compose on a VPS for production now; service-per-container layout that maps directly
onto Kubernetes.
**Why:** one business's traffic does not justify a cluster's operational cost.
**Cost:** no autoscaling or self-healing scheduler. Mitigated by restart policies, healthchecks,
resource limits, and keeping nothing Compose-specific in application code.

### ADR-009 — Configurable tax engine, no fiscalization yet
**Decision:** tax classes with effective-dated rates and per-product inclusive/exclusive pricing;
no country-specific fiscal device integration.
**Why:** correct tax handling is universal; fiscal signing is jurisdiction-specific and can be added
as an adapter service consuming `sale-completed`.
**Cost:** a VAT-registered Kenyan retailer will need a KRA eTIMS adapter before going live. The seam
is deliberately clean so that is additive work, not a refactor.

### ADR-010 — Gateway is not the only line of defence
**Decision:** every service validates the JWT independently via JWKS.
**Why:** a service reachable on the internal network must not be trivially callable without a valid
token. Defence in depth.
**Cost:** a small per-request validation cost and a JWKS cache in each service.

---

## 2. Event catalogue

Topic naming: `pos.<domain>.<event>.v<n>`, dead-letter: same + `.dlt`. Key = aggregate id.

| Topic | Producer | Consumers | Payload essentials |
|---|---|---|---|
| `pos.auth.otp-requested.v1` | auth | notification | email, otpCode, purpose, expiresAt |
| `pos.auth.user-registered.v1` | auth | notification, reporting | userId, email, name, roles |
| `pos.auth.password-reset-requested.v1` | auth | notification | email, resetToken, expiresAt |
| `pos.auth.user-role-changed.v1` | auth | reporting | userId, added[], removed[], actorId |
| `pos.catalog.product-changed.v1` | catalog | inventory, reporting, sales | productId, sku, name, taxClassId, sellByWeight, active |
| `pos.catalog.price-changed.v1` | catalog | sales, reporting | productId, branchId, price, effectiveFrom |
| `pos.purchasing.po-approved.v1` | purchasing | notification, reporting | poId, supplierId, total, approvedBy |
| `pos.purchasing.goods-received.v1` | purchasing | inventory, reporting | grnId, branchId, lines[{productId, qty, batchNo, expiry, unitCost}] |
| `pos.payments.payment-requested.v1` | sales | payment | saleId, branchId, amount, tenders[] |
| `pos.payments.payment-authorized.v1` | payment | sales, reporting | saleId, paymentId, method, amount, reference |
| `pos.payments.payment-failed.v1` | payment | sales, notification | saleId, reason, code |
| `pos.payments.payment-refunded.v1` | payment | sales, reporting | refundId, saleId, amount, method |
| `pos.sales.sale-completed.v1` | sales | inventory, customer, reporting, notification | saleId, branchId, cashierId, customerId?, lines[], totals, taxBreakdown[], payments[] |
| `pos.sales.sale-voided.v1` | sales | inventory, reporting | saleId, reason, actorId |
| `pos.sales.return-processed.v1` | sales | inventory, customer, reporting | returnId, saleId, lines[{productId, qty, resaleable, batchNo?}] |
| `pos.sales.shift-closed.v1` | sales | reporting, notification | shiftId, branchId, registerId, expected, declared, variance |
| `pos.inventory.stock-deducted.v1` | inventory | reporting | saleId, branchId, lines[{productId, qty, batchAllocations[]}] |
| `pos.inventory.low-stock.v1` | inventory | notification, purchasing | productId, branchId, onHand, reorderPoint |
| `pos.inventory.batch-expiring.v1` | inventory | notification | batchId, productId, branchId, expiry, qty, value |
| `pos.inventory.negative-stock-detected.v1` | inventory | notification, reporting | productId, branchId, onHand, triggeredBy |
| `pos.inventory.adjustment-posted.v1` | inventory | reporting | adjustmentId, branchId, lines[], reason, actorId |
| `pos.customers.loyalty-accrued.v1` | customer | notification, reporting | customerId, saleId, points, balance |
| `pos.customers.tier-changed.v1` | customer | notification | customerId, fromTier, toTier |

**Envelope** (every message):

```json
{
  "eventId": "01J8ZQ...",
  "eventType": "sales.sale-completed",
  "schemaVersion": 1,
  "occurredAt": "2026-09-20T10:15:30.123Z",
  "correlationId": "req-7f3a…",
  "causationId": "01J8ZQ…",
  "branchId": "…",
  "actorId": "…",
  "payload": { }
}
```

`correlationId` originates at the gateway and threads through every HTTP hop and every event, so one
identifier follows a sale from the scan to the receipt email in Grafana.

---

## 3. Key flows

### 3.1 Registration with email OTP

```
Client          gateway        auth-service        Kafka        notification
  │ register ────▶ │ ──────────────▶ │
  │                │      create user PENDING + outbox row (one transaction)
  │                │                 │ ──relay──▶ otp-requested ──▶ │
  │                │                 │                              │ SMTP send, log
  │ ◀── 202 "check your email" ──────┤
  │ verify-otp ───▶ │ ─────────────▶ │ compare hash, attempts, expiry
  │                │      status=ACTIVE, outbox: user-registered ──▶ │ welcome email
  │ login ────────▶ │ ─────────────▶ │ access(15m) + refresh(7d)
```
The OTP is hashed at rest and compared in constant time. Verification responses are uniform whether
the email exists or not.

### 3.2 Checkout saga

```
sales: create sale PENDING, reserve stock (soft)
  └─▶ payments.payment-requested
        payment: cash | M-Pesa STK | card terminal reference
          ├─ success ─▶ payments.payment-authorized
          │               sales: sale PAID, receipt number assigned
          │                 └─▶ sales.sale-completed
          │                       ├─▶ inventory: FEFO deduct, release reservation
          │                       ├─▶ customer:  accrue loyalty
          │                       ├─▶ reporting: project read models
          │                       └─▶ notification: email receipt (if requested)
          └─ failure/timeout ─▶ payments.payment-failed
                          sales: release reservation, sale CANCELLED
```
Compensation is explicit — there is no distributed transaction. A timeout on the payment step is
treated as a failure, and a late `payment-authorized` for a cancelled sale is detected and routed to
manual reconciliation rather than silently applied.

### 3.3 Offline sale synchronisation

```
Terminal offline            Terminal reconnects            sales-service
  scan, price from             POST /sales/sync              for each sale:
  cached catalog                 batch of queued sales         dedupe on clientSaleId
  sale stored in Dexie           Idempotency-Key header        revalidate price & tax now
  with clientSaleId (UUIDv7)                                   compare to client totals
                                                               ├─ match  → accept
                                                               └─ differ → accept + flag variance
                                                               deduct stock (may go negative → alert)
                            ◀── per-sale result + variances
```
Duplicate submission of the same batch is a no-op. Nothing is ever dropped silently: a sale that
cannot be accepted surfaces in the terminal UI for supervisor action.

### 3.4 M-Pesa payment

```
payment-service ──STK Push──▶ Daraja ──prompt──▶ customer phone
        │                                           │ enters PIN
        │ ◀──── callback (may duplicate, reorder, or never arrive) ────┘
        │ idempotent on CheckoutRequestID
        │
        └─ scheduled job: for intents still PENDING past the timeout,
           query the Daraja transaction-status API and settle from the truth
```
The callback is a hint; the status query is the authority. Daily reconciliation compares recorded
payments against the M-Pesa statement and reports variances.

---

## 4. Data ownership map

| Schema | Owner | Anything else needing this data |
|---|---|---|
| `auth` | auth-service | Reads identity claims from the JWT — never the tables |
| `catalog` | catalog-service | sales caches product/price via events; inventory caches product metadata |
| `inventory` | inventory-service | sales sees availability via API; reporting projects from events |
| `purchasing` | purchasing-service | inventory receives stock via `goods-received` events |
| `sales` | sales-service | reporting projects from events; payment correlates by `saleId` |
| `payment` | payment-service | sales learns outcomes via events only |
| `customer` | customer-service | sales attaches `customerId`; loyalty resolved via events/API |
| `notification` | notification-service | — |
| `reporting` | reporting-service | Read-only projections; rebuildable from topic replay |

Grants are per-role and per-schema, so a cross-schema query fails at the database, not at review
time.

---

## 5. Port and topic reference

| Component | Port |
|---|---|
| web (Next.js) | 3000 |
| api-gateway | 8080 |
| auth-service | 8081 |
| catalog-service | 8082 |
| inventory-service | 8083 |
| purchasing-service | 8084 |
| sales-service | 8085 |
| payment-service | 8086 |
| customer-service | 8087 |
| notification-service | 8088 |
| reporting-service | 8089 |
| postgres | 5432 |
| kafka | 9092 |
| redis | 6379 |
| mailpit (SMTP / UI) | 1025 / 8025 |
| prometheus | 9090 |
| grafana | 3001 |
| tempo | 3200 |
| loki | 3100 |

Topic defaults: 3 partitions, replication 1 in dev and 3 in production, 7-day retention for
transactional events, 30 days for `sales.*` (reporting rebuild window), compaction for
`catalog.product-changed`.
