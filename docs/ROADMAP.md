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
| 6 | catalog-service | Products, barcodes, tax engine, pricing, promos | ✅ done |
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

## Phase 6 — catalog-service ✅

**Goal:** products can be defined, priced and taxed correctly.

Delivered:
- 12 tables: categories (hierarchical), brands, units of measure, tax classes with
  **effective-dated** rates, products, barcodes, scale barcode rules, price lists and their items,
  promotions and their rules
- **Tax engine**: inclusive prices have tax extracted, exclusive have it added, rates resolve "as
  at" an instant so a reprint uses the rate of the day. Rounding happens once and the third figure
  is derived, so `net + tax == gross` exactly at every amount
- **`PriceResolver`**: subtotal, then discounts, then tax - in that order, because a discount
  changes the taxable amount. Deterministic promotion ordering, sequential stacking, single
  best-offer when anything is non-stackable, discounts capped at the line. Pure: no Spring, no
  JPA, no clock
- **Scale barcodes**: EAN-13 check digit verified, layout driven entirely by configured rules, so
  a shop changing scale vendors needs no release
- Price lists per branch with priority and fallback to base price; promotions scoped by product,
  category or store-wide, by branch, by time window and by membership
- `product-changed` and `price-changed` events through the outbox; CSV bulk import with per-row
  transactions and a line-numbered error report

**Verified — 251 tests in the build, 70 here, plus an end-to-end run through the gateway:**

| Check | Result |
|---|---|
| Inclusive vs exclusive tax | ✅ 116 incl. = 100 + 16; 100 excl. = 100 + 16 |
| The two are not interchangeable | ✅ 13.79 vs 16.00 on the same 100 |
| Zero-rated goods | ✅ no tax either way |
| `net + tax == lineTotal` | ✅ at every amount tested, including awkward weights |
| Weighed line | ✅ 1.235 kg × 250.00 = 308.75 |
| Rounding across 1000 lines | ✅ no drift |
| Percentage, amount, buy-X-get-Y | ✅ incl. part groups (5 units on 3-for-2 gives one free) |
| Stacking | ✅ two halves off leave a quarter, not nothing |
| Non-stackable present | ✅ single best offer wins |
| Discount larger than the line | ✅ capped; a line can be free, never negative |
| Promotion ordering | ✅ same answer whatever order they arrive in |
| Member-only / branch / window scoping | ✅ all enforced |
| Price list priority and fallback | ✅ |
| Rate change mid-year | ✅ before and after resolve to different rates, same price to the customer |
| Tax class with no rate in force | ✅ fails loudly rather than charging nothing |
| Scale barcode | ✅ decodes to the right product and 1.235 kg; bad check digit refused |
| Ambiguous scale item code | ✅ refused rather than charging for the wrong item |
| CSV import | ✅ good rows land, bad rows reported with line numbers, re-import updates |
| Coverage gate | ✅ |

**Bugs found by the build rather than by a person:** `ddl-auto: validate` caught `CHAR(3)` against
a `String` field; the exclusion constraint on tax rate periods rejected the ordinary
close-then-open sequence until it was deferred to commit; Jackson 3 rejected requests that merely
omitted an optional boolean; and the gateway had no route for two of catalog's paths.

**Deferred with reason:** `BUNDLE` promotions are modelled and stored but evaluated in
sales-service, because deciding whether a bundle is satisfied needs the whole basket and this
service prices one line at a time. `PriceResolver` skips them explicitly rather than silently.

---

## Phase 7 — inventory-service ✅

**Goal:** stock is accurate, batch-aware and auditable.

Delivered:
- 10 tables: `stock_items` (product × branch, with the derived on-hand cache), `stock_batches`
  (lot, expiry, quantity, unit cost), `stock_movements` (append-only ledger), `stock_adjustments`
  and their lines, `stock_transfers` and their lines, `stock_takes` and their lines,
  `stock_reservations`
- **The ledger is the source of truth.** Every quantity change writes a movement row carrying type,
  reason, actor and reference, and the same call applies it to the cached quantity - one method, so
  the two cannot drift apart by someone forgetting the second half. `/api/v1/stock/reconciliation`
  answers "do these numbers add up" on demand instead of leaving it to a nightly log
- **`FefoAllocator`**: oldest expiry first, then receipt order, then batch number, nulls last;
  splits one sale line across as many batches as it takes. Pure - no Spring, no JPA, no clock
- **A shortfall is recorded, not refused.** By the time inventory hears about a sale the customer
  has left with the goods, so an uncovered quantity becomes a batch-less movement, the on-hand
  figure goes negative, and `negative-stock-detected` is emitted. Refusing it would lose the fact
  that stock left the shop
- Returns honour the till's resaleable flag: good stock goes back to a batch, damaged stock is
  recorded in and then written off, so a return is never invisible to the shrinkage report
- Reservations for open carts with expiry and a sweep; adjustments with mandatory reason codes;
  inter-branch transfers with an in-transit state; stock takes from count sheet to posted variances
- Consumes `sales.sale-completed`, `sales.return-processed`, `purchasing.goods-received` and
  `catalog.product-changed`, each idempotent; emits `inventory.stock-deducted`, `.low-stock`,
  `.batch-expiring`, `.negative-stock-detected` and `.adjustment-posted` through the outbox
- Scheduled near-expiry scan and ledger reconciliation sweep

**Verified — 324 tests in the build, 56 here, plus an end-to-end run through the gateway against
the built image:**

| Check | Result |
|---|---|
| FEFO order | ✅ earliest expiry first, then receipt order, then batch number |
| A batch with no expiry | ✅ sorts last, never ahead of dated stock |
| One line across several batches | ✅ partial consumption, remainders left intact |
| A line larger than all stock | ✅ shortfall reported, not an exception |
| Shortfall handling | ✅ batch-less movement, on-hand goes negative, event emitted |
| Ledger sums to the cached quantity | ✅ after every operation tested |
| Reconciliation endpoint | ✅ empty, and reachable on demand |
| Redelivered sale event | ✅ deducts once; the second delivery is a no-op |
| Redelivered GRN and return events | ✅ same |
| Return of resaleable goods | ✅ back to a batch and sellable again |
| Return of damaged goods | ✅ in, then written off - both halves in the ledger |
| Reservation | ✅ holds against available without moving on-hand; released on cancel |
| Stock take | ✅ opens, counts, reports variance, posts as adjustments |
| Transfer | ✅ draft → in transit → received, stock lands at the destination |
| Adjustment | ✅ signed delta, reason code, actor recorded, event on the topic |
| Branch scoping | ✅ a manager at one branch is refused another's stock |
| Permissions | ✅ `inventory:view` cannot adjust; unauthenticated is 401 |
| Migrations against the real image | ✅ applied clean, 13 tables |
| Outbox relay in the container | ✅ `adjustment-posted` consumed off Kafka with the right envelope |
| Coverage gate | ✅ 88.8% |

**Bugs found by the build rather than by a person:** a 404 whose resource name contained a space
(`"Stock item"`) produced the error code `stock item.not_found`, which `URI.create` then refused
*inside* the exception handler - so the resolver abandoned it and a deliberate 404 escaped the
dispatcher as an unhandled 500 with no body. Shipped in catalog since Phase 6 on the "Scale item"
path. Error codes are now slugged at source and the type URI never throws. The gateway was also
missing routes for `/api/v1/adjustments` and `/api/v1/reservations` - the same trap as Phase 6, and
the reason the route table is now checked with a token rather than by reading it.

**Improved while verifying:** an invalid enum answered "Request body could not be parsed" and named
nothing. It now names the field and the accepted values - never the submitted value, which may be a
password - and an offline till retrying a queued sale gets one chance to be told what is wrong.

**Deferred with reason:** stock valuation reports (moving average, FIFO cost layers) wait for
reporting-service, which owns read models. Batch-level costing is captured on every movement, so
nothing needed for it is being lost in the meantime.

---

## Phase 8 — purchasing-service ✅

**Goal:** stock enters the system the way it does in a real shop, with real costs.

Delivered:
- 11 tables: `suppliers`, `supplier_products` (agreed and last-delivered cost side by side),
  `purchase_orders` and their lines, `goods_received_notes` and `grn_lines`, `supplier_invoices`,
  `supplier_returns` and their lines, `reorder_suggestions`
- **A purchase order is a state machine, not a document.** Every transition goes through one method,
  so the illegal ones cannot be reached by a new endpoint: an approved order can never return to
  DRAFT, because an order editable after approval is an order whose total can be raised after
  someone signed for it. `approved_total` freezes the authorised figure
- **`LandedCostAllocator`**: freight and duty spread across a delivery by value or by quantity, with
  the rounding remainder given to the largest line so the parts sum to the charge *exactly*. Naive
  proportional arithmetic loses fractions of a cent on almost every real split and leaves an
  unexplained residual in the accounts. Pure - no Spring, no JPA, no clock
- **The landed cost is what inventory values stock at**, not the invoice price. A consignment
  carrying freight sells at a loss if every item is priced off the supplier's unit price, and the
  loss is invisible because each line looks profitable
- **`ThreeWayMatcher`**: quantity against the *receipt*, price against the *order*, and receipt
  against order for goods nobody authorised. Per product, not on totals - a supplier can bill the
  right grand total while charging for goods that never arrived. Tolerance is an absolute floor or
  a percentage, whichever is more generous, but quantity and not-received findings are never
  tolerated: those are not matters of degree
- Rejected goods are recorded and excluded - they never become sellable stock, and they carry none
  of the freight
- Returns to supplier priced at the landed cost the goods came in at, not today's price
- Reorder suggestions from `inventory.low-stock`, sized to the preferred supplier's minimum order
  quantity, and a dismissal is honoured for a cooling-off period so the list stays worth reading
- Emits `purchasing.po-approved`, `purchasing.goods-received` and
  `purchasing.supplier-cost-changed` through the outbox; consumes `inventory.low-stock` and
  `catalog.product-changed`, both idempotent

**Verified — 424 tests in the build, 100 here, plus an end-to-end run through the gateway against
the built images:**

| Check | Result |
|---|---|
| Order lifecycle draft → submitted → approved → sent | ✅ with actor and time recorded at each step |
| `approved_total` frozen at approval | ✅ |
| An approved order cannot be edited or returned to draft | ✅ refused by the state machine |
| A submitted order can be sent back to the buyer | ✅ the only backwards step there is |
| Every illegal transition | ✅ enumerated exhaustively, not by example |
| A supplier on hold cannot take new orders | ✅ |
| Freight by value / by quantity | ✅ genuinely different answers, both correct |
| Allocated charges sum to the charge | ✅ across 1–13 lines splitting 1000.01 |
| Three lines splitting 100 | ✅ 33.3334 + 33.3333 + 33.3333, remainder deterministic |
| A delivery of free samples | ✅ falls back to quantity rather than refusing |
| Landed unit cost reaches the batch | ✅ 95.00 + freight = 103.3333 on the real image |
| Partial receipt | ✅ 60 of 100 leaves PARTIALLY_RECEIVED, 40 outstanding |
| Second receipt completes it | ✅ RECEIVED |
| Rejected quantities | ✅ excluded from stock and from the freight weighting |
| A delivery with everything rejected | ✅ refused, not posted as nothing |
| A receipt posted twice | ✅ 409 |
| A delivery with no purchase order | ✅ accepted and recorded |
| An order already delivered against cannot be cancelled | ✅ close it instead |
| Over-billed invoice | ✅ EXCEPTION, variance 500.00, PRICE_VARIANCE named with its cost |
| Billed for more than arrived | ✅ EXCEPTION |
| Right total, wrong goods | ✅ EXCEPTION — a totals-only check would have passed it |
| Small price drift | ✅ WITHIN_TOLERANCE, still reported |
| Absolute floor vs percentage | ✅ the more generous wins, both directions tested |
| A quantity variance worth 10 cents | ✅ still an exception |
| Accepting an exception | ✅ requires a reason, and keeps it |
| An invoice with nothing to match against | ✅ stays PENDING rather than claiming a match |
| Return lifecycle draft → sent → credited | ✅ priced at landed cost |
| Cost change on delivery and on renegotiation | ✅ emitted once, not on every save |
| One preferred supplier per product | ✅ enforced by a partial unique index |
| Low-stock event → suggestion | ✅ priced from the supplier's price list |
| Redelivered low-stock event | ✅ no second suggestion |
| A dismissed suggestion | ✅ not re-proposed by the next event |
| Minimum order quantity | ✅ a 24-case product is never suggested as 6 |
| Branch scoping and per-permission authorization | ✅ approve is separate from create |
| Migrations against the real image | ✅ applied clean, 13 tables |
| Cross-service: purchasing → Kafka → inventory | ✅ batch created with the right expiry and landed cost |
| Coverage gate | ✅ 90.5% |

**Bugs found by the build rather than by a person:** `@EntityGraph` defaults to `FETCH`, which
demotes every attribute it does not name to lazy - including a collection declared `EAGER` - so
adding a graph to fetch the supplier silently broke the order's lines; `LOAD` adds to the declared
fetch plan instead of replacing it. Replacing a draft order's lines violated the unique
`(order, line_number)` index, because Hibernate orders inserts before the deletes that orphan
removal queues. And the first delivery through the built image never reached inventory: the new
`supplier-cost-changed` topic had been added to the topic script, which only runs on an empty Kafka
volume, so the row sat in the outbox retrying against a topic that did not exist. `make
kafka-topics-sync` now exists for exactly that.

**Improved while verifying:** an invalid enum now names the accepted values *and* the type even when
Jackson records no property path, which it does not always do.

**Deferred with reason:** reorder suggestions use stock level, lead time and minimum order quantity,
but not **sales velocity** - that needs sales history, which arrives with `sales-service` in Phase 9.
The suggestion is sized to cover the reorder quantity inventory asks for meanwhile, and the hook to
refine it is one service call away. Landed-cost **valuation reporting** (moving average across
deliveries) belongs to `reporting-service`, which owns read models; every movement already carries
the cost it needs.

---

## Phase 9 — sales-service ✅

**Goal:** the till works — including with the network down.

Delivered:
- 14 tables: `till_sessions`, `cash_movements`, `carts`, `cart_lines`, `sales`, `sale_lines`,
  `sale_payments`, `price_overrides`, `returns`, `return_lines`, `receipts`, `receipt_sequences`,
  `offline_sync_batches`, `idempotency_records`, plus the shared `outbox` / `processed_event`
- **Shift lifecycle**: open with a float, cash drops and float top-ups as `cash_movements`,
  `begin-close` freezes the expected figure *before* the count so a sale rung up mid-count cannot
  show as a shortfall, close with a declared count and a signed variance. A variance never blocks
  a close. A cashier closes their own shift; anyone else's needs `shift:close:any`
- **Carts**: add by product, SKU or barcode, weighed lines kept separate rather than merged, quantity
  change, line void kept on the record, price override (permission-gated, reason required, audited
  in `price_overrides` with the value given away), suspend with a four-digit code and recall,
  customer attach that reprices for members
- **Server-side totalling, always.** Every line is priced by catalog's `PriceResolver`;
  `SaleTotalsCalculator` sums tax per class and flags a disagreement with the client's figure
  rather than trusting either side. Pure — no Spring, no JPA
- **Checkout saga**: `PENDING` → tender. Cash and card settle at the till; M-Pesa emits
  `payment-requested` and waits as `AWAITING_PAYMENT`. `payment-authorized` completes the sale,
  `payment-failed` or the timeout sweep cancels it and releases the stock. A late authorisation
  for a sale already given up on is not applied. Split tenders; change only ever in cash
- **Receipts**: numbered per branch from a gapless sequence drawn inside the completing
  transaction, so a cancelled sale leaves no hole. Full tax breakdown per class; reprints counted
- **Returns**: reference the original sale, pro-rated from what was *charged* (a promotion is
  honoured on the way back), partial returns tracked per line, a returns window with a recorded
  supervisor override outside it, `resaleable` carried to inventory. Emits `return-processed`
- **Voids**: `sale:void` only, the approver taken from the token, never an `UPDATE` that erases —
  emits `sale-voided` and the drawer follows
- **Offline sync**: a batch of terminal-created sales under an `Idempotency-Key`. Deduped on the
  terminal's own `clientSaleId`, each sale in its own transaction so one bad sale cannot sink the
  rest, repriced against catalog with the variance flagged, and the whole answer stored so a
  replay returns it verbatim. A catalog outage refuses the batch (503) without recording it, so the
  retry is processed properly
- **Z-report** built from the recorded sales, not the running counters, and says whether the two
  agree — the only way a drifting counter is ever noticed
- `Idempotency-Key` honoured on every mutating endpoint via a replaying filter; catalog and
  inventory behind circuit breakers, catalog failing closed (503, basket kept) and inventory
  failing open (a lane that will not serve is worse than overselling the last unit)
- Emits `sale-completed`, `sale-voided`, `return-processed`, `shift-closed` and
  `payment-requested` through the outbox; consumes `payment-authorized` / `payment-failed`,
  idempotently

**Done when:** an end-to-end test rings up a mixed basket (standard-rated, zero-rated, weighed,
promo-discounted), pays it, and sees stock deducted and a receipt whose tax breakdown matches the
catalog's `PriceResolver` exactly; a replayed offline batch creates no duplicates; a payment failure
leaves no stock reserved.

**Verified — 520 tests in the build, 89 here (41 unit, 48 integration), plus an end-to-end run
through the gateway against the built images:**

| Check | Result |
|---|---|
| Mixed basket: standard-rated, zero-rated, weighed, promo-discounted | ✅ 1052.5377, tax 116.2121 |
| Receipt tax breakdown matches catalog line by line | ✅ to the fourth decimal, and sums to the sale |
| Cash change | ✅ 47.46 handed back; the drawer records the sale, not the notes |
| Stock deducted, cross-service, on the real images | ✅ 20 → 18 → 16 via `sale-completed` |
| The basket's hold consumed with the deduction | ✅ reserved 2 → 4 → 2 on the real images |
| Payment authorised asynchronously | ✅ AWAITING_PAYMENT → PAID on the event, receipt numbered then |
| A redelivered authorisation | ✅ paid once, numbered once, announced once |
| A failed payment | ✅ sale CANCELLED, stock released, nothing announced as sold |
| A late authorisation for a cancelled sale | ✅ not applied |
| Timeout sweep | ✅ compensates exactly like a failure |
| Split cash + M-Pesa | ✅ waits for the M-Pesa half; only the cash half reaches the drawer |
| Receipt numbers | ✅ contiguous; cancelled sales leave no gap |
| Offline batch | ✅ accepted, numbered, receipted, announced, and on the shift's counters |
| A replayed offline batch | ✅ first answer returned, no duplicates |
| The same sale in a new batch | ✅ DUPLICATE with the original receipt, no number consumed |
| A stale terminal price | ✅ accepted at the server's figure, variance −6.00 flagged |
| One unsellable sale in a batch | ✅ REJECTED, the other two land, no gap in numbering |
| Catalog down during a sync | ✅ 503, nothing recorded, the retry succeeds |
| Partial return | ✅ refund pro-rated from the promotional price, `resaleable=false` carried |
| Returning more than was sold | ✅ refused |
| Outside the returns window | ✅ refused without an approver; recorded with one |
| Shift float → sales → refund → drop → top-up → count | ✅ expected 4432.00, variance −2.00, `shift-closed` emitted |
| Z-report against the counters | ✅ agree, takings split by method |
| One shift per register; a colleague's drawer | ✅ 409; 403 unless `shift:close:any` |
| The whole lane over HTTP | ✅ every response mapped with open-in-view off |
| Branch scoping, before anything is written | ✅ 403 leaves no till or cart behind |
| A retried request with the same `Idempotency-Key` | ✅ replayed, byte for byte |
| Gateway routes, with a real token | ✅ `/sales`, `/carts`, `/till-sessions`, `/returns` |
| Outbox relay on the real image | ✅ every row PUBLISHED |
| Coverage gate | ✅ 91.0% |

**Bugs found by the build rather than by a person:**
- **Offline sync refused every sale.** The per-sale `REQUIRES_NEW` method was called on `this`, so
  no transaction existed; the receipt-number service (`MANDATORY`) threw and a catch-all reported
  each sale as REJECTED. It had no test until this phase's verification wrote one.
- **An unknown barcode read as "catalog is down"**, because the circuit breaker's fallback caught
  the 404 too and counted it towards opening the breaker. In a sync, one delisted product made
  the whole batch a 503 the terminal would retry forever.
- **Holds outlived the sale.** Inventory had a `consume` for exactly this and nothing called it:
  `sale-completed` did not say which cart the stock was held under. The payload now carries
  `cartId` (additive; `.v1` unchanged) and inventory consumes the holds with the deduction.
- **`GET /returns` answered 500** on every call: the list mapped each return's lazy original sale
  after the session closed. The single-return lookup had the entity graph; the page did not.
- **A 403 left the row behind.** Opening a cart and checking out checked branch access on the
  object they had just committed. Both now check the parent first.
- **The gateway routed `/api/v1/shifts/**`**, a path the service never had; till sessions would
  have 404'd at the edge while the service was healthy.
- **Change came back as 47.4623**, four decimals of shilling handed to a customer.

**Deferred with reason:** loyalty redemption at the till waits for `customer-service` (Phase 11);
the cart already attaches a customer and reprices for members. Payment providers are Phase 10 —
here cash and card settle at the till and M-Pesa is exercised through its events.

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
