# Build Roadmap

The step-by-step plan, from empty repo to production. Each phase ends in something **runnable and
verifiable**, and stops for review before the next begins. Phases are ordered so that every phase
can be tested against what already exists — no phase depends on a later one.

Estimates assume focused work by one developer plus Claude; they are sizing signals, not deadlines.

| # | Phase | Delivers | Est. |
|---|---|---|---|
| 0 | Prerequisites & accounts | Tools installed, credentials in hand | ✅ done |
| 1 | Repo skeleton & infrastructure | Compose stack up, parent POM builds | ✅ done |
| 2 | Shared libraries | `common-lib`, `events-lib`, `messaging-lib` | ✅ done |
| 3 | auth-service | Register → OTP → login → refresh, JWKS | ✅ done |
| 4 | api-gateway | Single ingress, JWT enforcement, rate limits | ✅ done |
| 5 | notification-service | Real OTP email; registration loop closes | ✅ done |
| 6 | catalog-service | Products, barcodes, tax engine, pricing, promos | 4–5 d |
| 7 | inventory-service | Stock, batches/expiry, FEFO, movements | 4–5 d |
| 8 | purchasing-service | Suppliers, PO, GRN, costing | 3–4 d |
| 9 | sales-service | Shifts, checkout saga, receipts, returns, offline sync | 5–7 d |
| 10 | payment-service | Cash, M-Pesa STK, card terminal, refunds | 4–5 d |
| 11 | customer-service | Customers, loyalty, member pricing | 2–3 d |
| 12 | reporting-service | CQRS projections, Z-report, dashboards | 3–4 d |
| 13 | Frontend: foundation & auth | Next.js app, BFF auth, shell, RBAC routing | 3–4 d |
| 14 | Frontend: cashier lane | Checkout, scanner, printer, offline | 5–7 d |
| 15 | Frontend: back office | Catalog, stock, purchasing, users, reports | 5–7 d |
| 16 | Hardening & production | Observability, load test, security review, deploy | 4–5 d |

---

## Phase 0 — Prerequisites & accounts

**Before any code.** Nothing here is built by Claude; these are yours to obtain.

- [x] JDK 21, Node 24, Docker 29, Docker Compose v2 — verified present on this machine
- [x] Gmail/Workspace account for sending: enable 2FA, generate a **16-character App Password**
- [ ] Safaricom Daraja **sandbox** app: consumer key, consumer secret, shortcode, passkey
- [ ] `cloudflared` or `ngrok` installed, for exposing the M-Pesa callback URL in dev
- [ ] GitHub repository created, with Actions enabled
- [ ] Decide the sending domain and, for production email, add SPF and DKIM records

**Done when:** credentials are recorded in your password manager and `.env` can be filled in Phase 1.

---

## Phase 1 — Repository skeleton & infrastructure ✅

**Goal:** `make infra-up` gives a working Postgres, Kafka, Redis and Mailpit; the Maven build runs.

Delivered:
- Monorepo layout (`backend/`, `frontend/`, `infra/`, `docs/`, `.github/`)
- `backend/pom.xml` — Spring Boot 4.0.8 parent, Spring Cloud 2025.1.3 BOM, Java 21, Spotless
  (google-java-format AOSP), JaCoCo with an 80% line gate, Surefire/Failsafe split, an
  `integration` profile for Testcontainers
- `backend/mvnw` — script-only Maven wrapper 3.9.16 (no jar committed)
- `infra/compose/docker-compose.yml` — Postgres 16, Kafka 3.9 (KRaft, no ZooKeeper), Redis 7,
  Mailpit 1.26, all healthchecked, named volumes, log rotation, required-secret guards
- `infra/postgres/init/01-init-schemas.sh` — 9 schemas, 9 login roles, grants scoped per schema,
  `public` revoked, per-role `search_path`
- `infra/kafka/create-topics.sh` — the full event catalogue, idempotent, explicit partitions and
  retention, compaction where it belongs
- `Makefile` (developer entrypoint), `.env.example` + `make env` secret generation,
  `.editorconfig`, `.dockerignore`, `.gitignore`
- `.github/workflows/ci.yml` — backend build/test/coverage, a Compose smoke test that asserts the
  schemas, topics and cross-schema denial, gitleaks and Trivy

**Verified:**

| Check | Result |
|---|---|
| All four containers healthy | ✅ postgres, kafka, redis, mailpit |
| 9 schemas, each owned by its own role | ✅ |
| `auth_user` can create/insert/select in `auth` | ✅ |
| `auth_user` reading `sales.*` | ✅ `permission denied for schema sales` |
| `auth_user` creating in `public` | ✅ `permission denied for schema public` |
| Kafka topics created | ✅ 46 (23 events + 23 `.dlt`), 3 partitions each |
| Retention and compaction applied | ✅ 30 d on `sales.*`, `cleanup.policy=compact` on `catalog.product-changed` |
| Produce → consume round trip | ✅ |
| Unknown topic fails loudly (auto-create off) | ✅ `UNKNOWN_TOPIC_OR_PARTITION` |
| Redis rejects unauthenticated commands | ✅ `NOAUTH Authentication required` |
| Mailpit UI + API | ✅ HTTP 200 |
| `backend/mvnw clean install` | ✅ BUILD SUCCESS |

**Environment note:** Docker Desktop on Linux only bind-mounts from its file-sharing list.
`/mnt/extra` was added to `FilesharingDirectories` in `~/.docker/desktop/settings-store.json`
(backup kept alongside) — without it every Compose bind mount fails.

---

## Phase 2 — Shared libraries ✅

**Goal:** the cross-cutting concerns exist once, so no service reinvents them.

Delivered as three modules rather than two: `messaging-lib` was split out so that `common-lib`
carries no Kafka dependency (the gateway needs the error and security wiring but no broker).

**`events-lib`** — no Spring dependency at all
- `EventEnvelope<T>` with a builder; `eventType` and `schemaVersion` are derived from the topic
  name so the two cannot drift apart
- `Topics` — every topic in the catalogue, plus `dlt()`, `eventTypeOf()`, `schemaVersionOf()`
- Auth event payloads; each later phase adds its own as that domain is designed
- `EventJson` — one Jackson configuration, unknown properties ignored on read so a consumer on an
  older build survives a producer that added a field

**`common-lib`** — web, security and JPA dependencies all optional, wiring `@ConditionalOnClass`
- RFC 7807 error model: `ApiException`, the `Errors` hierarchy, `GlobalExceptionHandler`, and
  `SecurityExceptionHandler` for `@PreAuthorize` denials
- `CorrelationIdFilter` — accepts or generates, sanitises hostile values, echoes on the response
- `LogMasker` + Logback converter — passwords, OTPs, tokens, JWTs, card PANs, phone numbers
- Resource-server auto-configuration: JWKS validation, `perms` claim to authorities,
  `AuthenticatedUser`, `BranchAccessGuard`, problem+json 401/403 handlers
- `BaseEntity` (UUIDv7 id, audit columns, optimistic lock), `AuthenticatedAuditorAware`
- `UuidV7`, `Money`, `PageResponse`

**`messaging-lib`** — built on `JdbcClient`, not JPA, so it never touches a service's persistence
context
- `OutboxRecorder` — refuses to run outside a transaction, so the atomicity guarantee cannot be
  silently lost
- `OutboxPublisher` — `FOR UPDATE SKIP LOCKED` batches, exponential backoff, rows parked as
  `FAILED` after the attempt limit rather than dropped
- `IdempotentConsumer` — keyed on (event, consumer); marker written before the handler runs so a
  failed handler rolls back and is retried rather than skipped
- `V0_001__messaging_infrastructure.sql` — shared migration; shared versions use `V0_*`, service
  migrations start at `V1`

**Verified — 95 tests, all passing:**

| Check | Result |
|---|---|
| Unauthenticated call | ✅ 401 `application/problem+json`, `auth.unauthenticated`, correlation id |
| `@PreAuthorize` denial | ✅ 403 problem+json, does not name the required permission |
| Cross-branch access | ✅ 403, does not disclose whether the branch exists |
| Validation failure | ✅ 400 with field errors, submitted password never echoed |
| Unexpected exception | ✅ generic 500; host, port and DB user all absent from the response |
| Hostile correlation header | ✅ discarded; newline log-forging not possible |
| Outbox → Kafka | ✅ published, keyed by aggregate id, envelope intact |
| Rolled-back transaction | ✅ no row, no event |
| Recording outside a transaction | ✅ rejected loudly |
| Duplicate delivery | ✅ handler ran exactly once |
| Two consumers, one event | ✅ each ran once |
| Handler throws | ✅ marker rolled back, redelivery retried |
| Failed send | ✅ attempts incremented, backed off, still `PENDING` |
| Repeatedly failed send | ✅ parked as `FAILED`, never auto-retried |
| Coverage gate (80%) | ✅ common-lib 87.8%; integration-test coverage now counted |

**Deferred:** ArchUnit boundary rules. They need at least one service to be meaningful, so they
land in Phase 3 alongside `auth-service`.

---

## Phase 3 — auth-service ✅

**Goal:** the full identity flow works end to end via HTTP, with OTPs readable from the outbox.

Delivered:
- **Schema** (12 tables): `branches`, `users`, `roles`, `permissions`, `role_permissions`,
  `user_roles`, `user_branches`, `refresh_tokens`, `otp_codes`, `password_reset_tokens`,
  `login_attempts`, `audit_log`, plus the shared outbox. Seeded with 41 permissions, 7 roles and a
  first branch.
- **Endpoints:** register, verify-otp, resend-otp, login, refresh, logout, forgot-password,
  reset-password, change-password, `/me`, JWKS, and permission-gated CRUD for users, roles,
  permissions and branches.
- **Crypto:** Argon2id passwords behind a `DelegatingPasswordEncoder` so the algorithm can be
  migrated without a mass reset; RS256 signing from a PKCS12 keystore with `kid`-based rotation
  (all keys published, one signs); refresh tokens as 256-bit opaque values stored SHA-256 hashed.
- **Defences:** rotation with reuse detection revoking the whole family, exponential lockout,
  token-version invalidation on password and role changes, uniform responses on every
  account-existence path, and a timing-equalised login.
- **Bootstrap:** a first SUPER_ADMIN created only when the users table is empty, from
  configuration with no default password, flagged to force a change.
- **ArchUnit:** 8 boundary rules (deferred from Phase 2, now that a service exists to check).

**Verified — 133 tests across the build, 30 integration tests here against real PostgreSQL:**

| Check | Result |
|---|---|
| register → OTP → verify → login → /me → refresh | ✅ full walk |
| Login before verification | ✅ refused |
| Replayed refresh token | ✅ whole family revoked, both parties signed out |
| Access token verifies against published JWKS | ✅ by `kid`; no private material published |
| Registering an existing address | ✅ byte-identical response to a new one |
| Unknown address vs wrong password | ✅ identical code and status |
| Wrong OTP ×5 | ✅ code burned; the correct one then fails too |
| Repeated failed logins | ✅ locked, and all 4 attempts recorded |
| Password reset | ✅ single-use, ends every session, old password dead |
| Change own password | ✅ requires current password, clears forced-change, ends sessions |
| Suspending a user | ✅ sessions ended, sign-in refused, reinstatement works |
| Admin suspending themselves | ✅ refused |
| Custom role built at runtime | ✅ grants exactly its permissions, immediately |
| Widening a role | ✅ holders' tokens invalidated; new sign-in has the new permission |
| Deleting a built-in role | ✅ refused |
| Unknown permission in a role | ✅ rejected, not silently dropped |
| Admin endpoints without permission | ✅ 403; unauthenticated 401 |
| Coverage gate (80%) | ✅ 89.1% |

**Three bugs the tests caught**, all of the same shape — a state change rolled back by the
rejection that followed it: the failed-login counter, the OTP attempt counter, and the
refresh-token family revocation. Each looked correct from outside while doing nothing. Written up
in [CLAUDE.md](../CLAUDE.md#the-rollback-trap-this-bit-us-three-times-in-one-phase).

**Known limitation, by design:** `tv` (token version) is issued and bumped, but nothing enforces it
at the edge yet, so an already-issued access token keeps its old permissions until it expires (15
minutes). Enforcement belongs at the gateway with a Redis-backed version cache — Phase 4. The
integration test asserts this behaviour explicitly rather than pretending otherwise.

---

## Phase 4 — api-gateway ✅

**Goal:** one ingress, and services are no longer reachable directly from outside.

Built on the **servlet** gateway (`gateway-server-webmvc`) rather than the reactive one. The
reactive variant ships a Redis rate limiter this one lacks, which is written by hand here; in
exchange the gateway keeps the same programming model as every service, so common-lib's
correlation ids and problem+json error shape apply unchanged, and virtual threads carry the
concurrency the reactive stack would otherwise be needed for.

Delivered:
- Route table for all nine services by Docker DNS name, each behind its own circuit breaker
- JWT validation against auth-service's JWKS, with issuer and expiry validators and a 30s skew;
  Nimbus refetches on an unknown `kid`, so key rotation needs no restart
- **Token-version enforcement**, closing the gap left open in Phase 3: auth-service publishes each
  bump to Redis with a TTL just past the access-token lifetime, and the gateway refuses any older
  token with a distinct `auth.token_superseded` code
- Hand-written Redis token-bucket rate limiter evaluated by a Lua script, so read-modify-write
  cannot interleave across replicas; three classes of traffic keyed differently (credential
  endpoints by IP at 10/min, authenticated by user at 300/min, anonymous by IP at 60/min), with
  `Retry-After` and `X-RateLimit-*`. Fails open — a Redis outage must not stop a shop trading
- Correlation-id propagation to every downstream service, CORS allowlist, security headers,
  request size limits, and a fallback returning `503` with `Retry-After`
- `service.Dockerfile` (multi-stage, BuildKit cache mount, non-root, `MaxRAMPercentage`) and
  `docker-compose.services.yml`; **only the gateway publishes a port**

**Verified — 156 tests in the build, 21 integration tests here, plus an end-to-end run in Docker:**

| Check | Result |
|---|---|
| auth-service reachable from the host | ✅ `:8081` refused; only `:8080` is published |
| Unauthenticated protected route | ✅ 401 problem+json, request never reaches the service |
| Token signed by an unknown key | ✅ refused |
| Tampered / expired / wrong-issuer token | ✅ all refused |
| Superseded token version | ✅ 401 `auth.token_superseded` |
| Token matching or newer than the published version | ✅ allowed |
| Credential endpoint flood | ✅ 10 allowed, then 429 with `Retry-After` |
| Authenticated traffic | ✅ 300/min, separate bucket per user |
| Health probes | ✅ never rate limited |
| Service that does not exist yet | ✅ 503 in 86 ms, with `Retry-After` |
| Downstream 500 | ✅ passed through, not disguised |
| CORS preflight | ✅ allowed origin only; unknown origin refused |
| Security headers | ✅ nosniff, DENY, Referrer-Policy (HSTS only over TLS) |
| Correlation id | ✅ propagated downstream, echoed once, not duplicated per hop |
| Register → OTP → verify → login → /me, all through `:8080` | ✅ in Docker |

**Two bugs found by running it rather than testing it.** The gateway died at startup in its
container on a missing `spring-boot-restclient` — declared test-scoped for `TestRestTemplate`, so
every test passed while the image could not boot. And every proxied request was getting a freshly
generated correlation id, because the circuit breaker runs the downstream call on its own thread
and the MDC is thread-local.

---

## Phase 5 — notification-service ✅

**Goal:** the registration loop closes - a real OTP email lands in Mailpit.

Delivered:
- Kafka consumers for `auth.otp-requested`, `auth.user-registered` and
  `auth.password-reset-requested`, each idempotent and transactional
- `EmailSender` over `JavaMailSender`, sending `multipart/alternative` with the plain-text part
  first (least-to-most preferred, or every client shows the plain version); Mailpit locally,
  Gmail or Workspace in production, same code path
- Thymeleaf HTML and plain-text templates for all three messages, table-based and inline-styled
  because email HTML is not web HTML
- `notification_log` recording recipient, subject, status, attempt count and error - **never the
  body**, since OTP codes and reset tokens pass through here and would otherwise sit in a second
  database long after they expired
- Retry with exponential backoff, then the dead-letter topic; a DLT consumer records the permanent
  failure so a message nobody received is answerable rather than invisible
- Template preview endpoint, off by default and authenticated when on

**Verified — 169 tests in the build, 12 here, plus an end-to-end run in Docker:**

| Check | Result |
|---|---|
| Register through the gateway → email in Mailpit | ✅ arrived in ~1.5 s |
| Verify using the code **taken from the email** | ✅ 200 |
| Welcome email after verification | ✅ |
| Code in the body, not the subject | ✅ subjects appear on lock screens and in gateway logs |
| Both an HTML and a plain-text part | ✅ `multipart/alternative` |
| The code stored anywhere in the log | ✅ zero rows contain it |
| Redelivered event | ✅ one email, one log row |
| Undeliverable message | ✅ 4 attempts, backoff, dead-lettered, recorded `PERMANENTLY_FAILED` |
| Failure record surviving the rollback it happens inside | ✅ |
| Idempotency marker rolled back on failure | ✅ redelivery is a real retry |

**The bug this phase uncovered, latent since Phase 2:** the outbox relay scheduler was never
created. Its `@ConditionalOnBean` named a bean defined by the same auto-configuration, so the
condition was evaluated before that bean existed. Nothing failed anywhere - services started,
outbox rows accumulated at `PENDING` with zero attempts and no error, because nothing was asking
to publish them. Every messaging test called `publishDue()` directly for determinism, so none of
them noticed. It surfaced the moment a consumer downstream was waiting for an email. Fixed, and
`OutboxRelayWiringIT` now waits for the relay rather than calling it.

The outbox did its job throughout: events queued during the weeks the relay was broken were all
delivered once it was fixed, which is the guarantee the pattern exists to provide.

**Deferred with reason:** receipt and low-stock alert templates. Their data shape depends on the
sales and inventory payloads, which are not designed yet, and a template that cannot be rendered
from a real event has never been tested. They land with the services that produce those events.

**Still needs you:** verifying real delivery through Gmail requires an account with 2FA and a
16-character App Password. The code path is identical - only `SMTP_*` changes - but I will not
invent credentials.

---

## Phase 6 — catalog-service

**Goal:** products can be defined, priced and taxed correctly.

- `categories`, `brands`, `units_of_measure`, `products`, `product_barcodes`, `tax_classes`,
  `tax_rates` (effective-dated), `price_lists`, `price_list_items`, `promotions`, `promotion_rules`
- Products: SKU, name, category, brand, UoM, sell-by-weight flag, tax class, active status, min/max
  stock hints, image reference
- Multiple barcodes per product, plus **scale barcode rules** — configurable EAN-13 prefix patterns
  that decode an embedded weight or price
- Tax engine: inclusive or exclusive pricing, rates versioned by `valid_from`/`valid_to`, resolution
  always "as at" a timestamp so historical receipts stay correct
- Price lists per branch with fallback to base price
- Promotions: percentage/amount off, buy-X-get-Y, bundles, time windows, branch scope, member-only;
  a deterministic `PriceResolver` that returns a fully explained price breakdown
- Emits `catalog.product-changed`, `catalog.price-changed`
- Bulk CSV import with row-level validation and an error report

**Done when:** unit tests cover every pricing and tax combination (inclusive vs exclusive, zero-rated
vs standard, promo stacking rules, weighed items), a scale barcode decodes to the right product and
weight, and `PriceResolver` returns an identical breakdown to what the receipt will later show.

---

## Phase 7 — inventory-service

**Goal:** stock is accurate, batch-aware and auditable.

- `stock_items` (product × branch), `stock_batches` (lot, expiry, qty, unit cost),
  `stock_movements` (append-only ledger), `stock_adjustments`, `stock_transfers`, `stock_takes`,
  `stock_take_lines`
- Every quantity change writes a movement row with type, reason, actor and reference — the ledger is
  the source of truth; `stock_items.qty` is a derived cache that must reconcile
- **FEFO** deduction across multiple batches, including partial consumption of a batch by one line
- Reservations with expiry (for open carts), released on cancel or timeout
- Adjustments with mandatory reason codes; damage and expiry write-offs
- Inter-branch transfers with in-transit state
- Stock takes: snapshot, count entry, variance report, approval, posting
- Consumes `sales.sale-completed` (deduct), `sales.return-processed` (restock),
  `purchasing.goods-received` (receive)
- Emits `inventory.stock-deducted`, `.low-stock`, `.batch-expiring`, `.negative-stock-detected`
- Scheduled near-expiry scan

**Done when:** a sale event deducts FEFO across two batches correctly, a redelivered event changes
nothing, the movement ledger sums exactly to the on-hand cache, and a stock take posts variances as
adjustments.

---

## Phase 8 — purchasing-service

**Goal:** stock enters the system the way it does in a real shop, with real costs.

- `suppliers`, `supplier_products`, `purchase_orders`, `purchase_order_lines`, `goods_received_notes`
  (GRN), `grn_lines`, `supplier_invoices`, `supplier_returns`
- PO lifecycle: draft → submitted → approved (permission-gated, threshold-aware) → sent → partially
  received → received → closed/cancelled
- GRN capture: received quantity, batch number, expiry, unit cost, discrepancy against the PO
- Landed cost allocation (freight, duty) across GRN lines
- Supplier invoice matching (three-way: PO ↔ GRN ↔ invoice) with tolerance rules
- Returns to supplier
- Emits `purchasing.po-approved`, `purchasing.goods-received`, `purchasing.supplier-cost-changed`
- Reorder suggestions from `inventory.low-stock` plus sales velocity

**Done when:** approving and receiving a PO creates batches in `inventory-service` with the right
expiry and unit cost, a partial receipt leaves the PO correctly partially received, and three-way
matching flags an over-billed invoice.

---

## Phase 9 — sales-service

**Goal:** the till works — including with the network down.

- `till_sessions` (shifts), `carts`, `cart_lines`, `sales`, `sale_lines`, `sale_payments`,
  `returns`, `return_lines`, `receipts`, `price_overrides`, `outbox`
- Shift lifecycle: open with float → sales → cash drops → close with declared count → variance
- Cart operations: add by barcode/SKU/search, weighed line entry, quantity change, line void,
  line discount and price override (permission-gated, reason required), suspend and recall,
  customer attach
- **Server-side totalling, always** — the client's totals are advisory and revalidated
- Checkout saga: sale `PENDING` → request payment → on `payment-authorized` mark `PAID` and emit
  `sale-completed`; on failure or timeout compensate and cancel
- Returns and refunds: reference the original sale, policy window, partial returns, resaleable flag
  driving restock, emits `return-processed`
- Voids: supervisor-approved, audited, never destructive
- Receipt generation: numbered per branch, gapless sequence, full tax breakdown per class
- **Offline sync endpoint**: accepts a batch of terminal-created sales carrying client UUIDs and an
  `Idempotency-Key`; deduplicates, revalidates prices and stock, and reports per-sale results
  including price variances
- Z-report data per shift

**Done when:** an end-to-end test rings up a mixed basket (standard-rated, zero-rated, weighed,
promo-discounted), pays it, and sees stock deducted and a receipt whose tax breakdown matches the
catalog's `PriceResolver` exactly; a replayed offline batch creates no duplicates; a payment failure
leaves no stock reserved.

---

## Phase 10 — payment-service

**Goal:** money is taken, matched and reconciled.

- `payment_intents`, `payments`, `payment_events`, `refunds`, `mpesa_transactions`,
  `reconciliation_runs`
- `PaymentProvider` port with three adapters:
  - **Cash** — tendered amount, change due, denomination breakdown for the drawer
  - **M-Pesa** — Daraja OAuth token caching, STK Push initiate, callback endpoint,
    transaction-status query for the cases where the callback never arrives
  - **Card terminal** — manual capture of reference and approval code; no card data stored
- Split payments across methods on one sale
- Refunds per method, respecting M-Pesa's reversal constraints
- Consumes `payments.payment-requested`, emits `payment-authorized` / `-failed` / `-refunded`
- Daily reconciliation: M-Pesa statement against recorded payments, variance report
- Callback handling that is **idempotent on `CheckoutRequestID`**, tolerant of out-of-order and
  duplicate deliveries, and signature/IP validated

**Done when:** a sandbox STK Push completes end to end through a tunnel, a duplicated callback is a
no-op, a never-delivered callback is recovered by the status query job, cash change is exact to the
cent, and a split cash + M-Pesa payment settles the sale exactly once.

---

## Phase 11 — customer-service

**Goal:** members are recognised and rewarded.

- `customers`, `membership_tiers`, `loyalty_accounts`, `loyalty_transactions`, `customer_addresses`
- Customer lookup by phone, card number or name — fast enough for the lane
- Tier rules driven by rolling spend
- Point accrual from `sales.sale-completed`, redemption as a tender type on the next sale,
  expiry rules, manual adjustment with audit
- Emits `customers.loyalty-accrued`, `.tier-changed`
- Consent flags and data-export/erasure support

**Done when:** a sale attributed to a member accrues the right points exactly once under redelivery,
tier upgrade fires at the threshold, and redemption reduces the payable amount correctly.

---

## Phase 12 — reporting-service

**Goal:** the numbers the business actually runs on, without touching other services' schemas.

- Read models projected from events only: `sales_daily`, `sales_by_product`, `sales_by_cashier`,
  `sales_by_branch`, `payment_mix`, `stock_valuation`, `margin_by_category`, `shift_summary`
- Z-report / X-report per shift and per branch
- Dashboard endpoints: today's revenue, basket count, average basket, top movers, dead stock,
  near-expiry value, gross margin
- Date-range, branch and category filters everywhere
- CSV and PDF export
- Projection rebuild capability — replay topics from the beginning into a fresh read model
- Consumer-lag monitoring, since these reads are eventually consistent

**Done when:** a projection rebuilt from an empty database reproduces exactly the same numbers as the
incrementally built one, and a Z-report reconciles against `sales-service` shift totals to the cent.

---

## Phase 13 — Frontend foundation & auth

**Goal:** a person can register, verify by OTP, log in and land on a shell that reflects their
permissions.

- Next.js 15 App Router, TypeScript strict, Tailwind + shadcn/ui, route groups `(auth)`,
  `(pos)`, `(admin)`
- **BFF auth**: route handlers proxy login/refresh and hold the refresh token in an `httpOnly`,
  `Secure`, `SameSite=Strict` cookie; access tokens stay server-side; middleware guards routes
- Registration wizard: details → OTP entry (paste-friendly, resend with visible cooldown) → success
- Typed API client with Zod schemas at every boundary, automatic refresh on `401`, problem+json
  error surfacing
- Permission-aware navigation and a `<Can permission="…">` component; branch switcher for
  multi-branch users
- Design system: light/dark, large touch targets, toasts, loading and empty states
- Vitest + Testing Library set up, Playwright bootstrapped

**Done when:** the full register → OTP → login → protected page → refresh → logout path works in a
browser, a cashier and a manager see different navigation, and no token is visible in
`localStorage` or client state.

---

## Phase 14 — Frontend cashier lane

**Goal:** a cashier can work a full shift, including through a network outage.

- Shift open/close screens with float and declared-count reconciliation
- Checkout screen: global barcode capture (keyboard-wedge scanner), product search, weighed-item
  entry, quantity and line edit, line void, discount and price override with supervisor PIN,
  suspend/recall, customer attach
- **Keyboard-first**: every action has a shortcut, the basket is fully operable without a mouse
- Payment modal: cash with quick-tender buttons and change display, M-Pesa STK with live status
  polling, card terminal reference entry, split payments
- Receipt: ESC/POS thermal printing with a browser print fallback, cash-drawer kick, reprint,
  email receipt
- **Offline mode**: Workbox service worker, catalog and price cache in Dexie, queued sales with
  client UUIDs, a permanently visible online/offline/syncing indicator, replay with
  `Idempotency-Key`, and a clear variance report when the server repriced a queued sale
- Returns and refunds flow with original-sale lookup

**Done when:** a Playwright run completes a mixed-basket sale with split payment; pulling the network
mid-shift still allows selling, and restoring it syncs every queued sale exactly once with no
duplicates and a readable variance report.

---

## Phase 15 — Frontend back office

**Goal:** the business can be run without touching the database.

- Catalog: product CRUD with image upload, barcode management, categories, UoM, tax classes and
  rates, price lists, promotions builder with a live preview of the resulting price
- Inventory: stock by branch, batch and expiry views, adjustments with reasons, transfers,
  stock-take entry and variance approval, low-stock and near-expiry dashboards
- Purchasing: supplier CRUD, PO creation and approval, GRN capture with batch and expiry entry,
  invoice matching, reorder suggestions
- Users and access: user CRUD, branch assignment, **role builder with a permission matrix**, audit
  log viewer with filters
- Customers and loyalty: search, profile, points history, manual adjustment
- Reports: dashboards, Z-reports, sales/margin/stock reports with filters and CSV/PDF export
- Settings: branches, registers, receipt template, tax defaults, notification templates

**Done when:** every workflow in [REQUIREMENTS.md](REQUIREMENTS.md) is reachable through the UI by a
user with the right permissions and correctly hidden from one without.

---

## Phase 16 — Hardening & production deployment

**Goal:** it survives contact with the real world.

Observability:
- OpenTelemetry Collector → Tempo, Prometheus → Grafana, Loki for logs, in
  `docker-compose.observability.yml`
- Dashboards: request rate/latency/errors per service, Kafka consumer lag, DLT depth, DB
  connections, JVM, business KPIs (sales/hour, failed payments)
- Alert rules: consumer lag, DLT arrivals, error-rate spike, p95 breach, failed M-Pesa callbacks,
  disk and memory pressure

Performance & resilience:
- k6 load test of the checkout path at realistic lane concurrency; fix what it exposes
- Index review against real query plans, connection-pool sizing, JVM tuning
- Chaos checks: kill Kafka, kill a service, fill a disk mid-sale — verify nothing is lost or
  double-counted

Security:
- `/security-review` over the whole codebase, dependency and image scanning, secret-scanning in CI
- Penetration pass on auth: token replay, privilege escalation across branches, OTP brute force,
  rate-limit evasion, IDOR on every resource id

Production:
- Multi-stage distroless images, non-root users, pinned digests, resource limits, restart policies
- `docker-compose.prod.yml` + Traefik with automatic TLS
- Docker secrets rather than `.env`; documented rotation procedure
- Nightly `pg_dump` off-site **with a restore actually rehearsed**, Kafka retention per topic
- Runbooks: deploy, rollback, restore, DLT replay, key rotation, incident response
- Staging environment mirroring production; blue-green or rolling deploy verified with a real
  schema migration in flight

**Done when:** the stack runs on the VPS under TLS, dashboards show live traffic, a restore from
backup has been performed successfully at least once, and a rolling deploy with a migration causes
no failed requests.

---

## Working rules

1. **One phase at a time.** No scaffolding ahead of the current phase.
2. **Every phase ends runnable.** If it cannot be demonstrated, it is not done.
3. **Tests ship with the code**, not in a catch-up phase later.
4. **Docs stay current.** An architectural change updates `readme.md`, `CLAUDE.md` and
   `ARCHITECTURE.md` in the same commit.
5. **Migrations are immutable once merged.** Fix forward.
6. **Secrets never enter git.** Ever.
