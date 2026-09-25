# POS Platform

A production-grade Point of Sale platform for multi-branch supermarket retail, built as a set of
Spring Boot microservices behind an API gateway, with a Next.js cashier + back-office frontend,
Kafka for event-driven communication, and PostgreSQL for persistence.

> **Status:** phases 0-9 and 11-14 of 16 complete - infrastructure, shared libraries, every
> backend service (`auth`, `api-gateway`, `notification`, `catalog`, `inventory`, `purchasing`,
> `sales`, `customer`, `reporting`), the web app's foundation and the cashier lane are built, tested
> and running under Docker Compose: stock arrives at its landed cost, leaves through a till whose
> totals and receipts are the server's, members earn and spend points on a ledger that balances,
> reports reconcile with the till to the cent, and a cashier works a whole shift from the keyboard -
> split payments, supervisor approvals by PIN, thermal receipts from the browser - and keeps selling
> when the network goes, every offline sale syncing exactly once. Phase 10's `payment-service` is
> built and running - card and cash settle end to end, M-Pesa is verified against a fake of Daraja -
> and waits only on a real sandbox STK Push, which needs a Daraja app subscribed to M-Pesa Express.
> 785 backend tests, 82 frontend tests and 10 browser end-to-end runs. Code is built phase by phase
> per [docs/ROADMAP.md](docs/ROADMAP.md), which records what each phase delivered and how it was
> verified.

---

## Table of contents

- [POS Platform](#pos-platform)
  - [Table of contents](#table-of-contents)
  - [What this is](#what-this-is)
  - [Architecture at a glance](#architecture-at-a-glance)
  - [Services](#services)
  - [Technology stack](#technology-stack)
  - [Repository layout](#repository-layout)
  - [Prerequisites](#prerequisites)
  - [Getting started](#getting-started)
  - [Environment configuration](#environment-configuration)
  - [Security model](#security-model)
  - [Event-driven communication](#event-driven-communication)
  - [Data ownership](#data-ownership)
  - [Testing strategy](#testing-strategy)
  - [Observability](#observability)
  - [Deployment](#deployment)
  - [Documentation index](#documentation-index)

---

## What this is

A supermarket-grade POS covering the full retail loop: purchase goods from suppliers, receive and
value stock, price and promote it, sell it at the lane, take payment, account for the till, and
report on all of it.

Scope decisions that shape everything else:

| Decision | Choice |
|---|---|
| Tenancy | **One business, many branches.** Every transactional row carries `branch_id`. Users are scoped to one or more branches. |
| Identity | **Custom `auth-service`** issuing RS256 JWTs, exposing JWKS. Every other service is a standard OAuth2 resource server, so an external IdP can replace it later without rewrites. |
| Access control | **RBAC with fine-grained permissions.** Roles are user-editable collections of permission strings — new roles need no code change. |
| Registration | **Email OTP verification** before an account becomes active. |
| Persistence | **One PostgreSQL instance, one schema + one DB role per service.** No cross-schema reads, ever. |
| Messaging | **Kafka**, versioned JSON events, transactional outbox on the producer side, idempotent consumers on the other. |
| Tax | **Configurable tax engine** — tax classes per product, rates versioned by effective date, inclusive or exclusive pricing. |
| Payments | **Cash + M-Pesa (Daraja STK Push) + card terminal manual capture**, behind a `PaymentProvider` port. |
| Frontend | **Next.js** cashier lane (offline-capable, barcode + thermal printer) and back-office admin. |
| Build & run | **Maven multi-module**, **Docker Compose** for dev and production, Kubernetes-ready layout. |

Full detail: [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md).

---

## Architecture at a glance

```
                          ┌───────────────────────────┐
                          │   Next.js 16 (App Router)  │
                          │  cashier lane + back office│
                          │  BFF route handlers hold   │
                          │  refresh token in cookie   │
                          └─────────────┬─────────────┘
                                        │ HTTPS, Bearer access token
                          ┌─────────────▼─────────────┐
                          │       api-gateway          │
                          │ routing · JWT verify (JWKS)│
                          │ rate limit · CORS · tracing│
                          └─────────────┬─────────────┘
        ┌──────────┬──────────┬─────────┼─────────┬──────────┬──────────┐
        ▼          ▼          ▼         ▼         ▼          ▼          ▼
     auth      catalog   inventory  purchasing  sales    payment   customer
        │          │          │         │         │          │          │
        └──────────┴──────────┴────┬────┴─────────┴──────────┴──────────┘
                                   │ publish / consume
                          ┌────────▼────────┐
                          │  Kafka (KRaft)   │  versioned JSON events + DLT
                          └────────┬────────┘
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
             notification-service          reporting-service
             (SMTP email, alerts)          (CQRS read models)

  PostgreSQL (schema per service) · Redis (rate limits, idempotency, cache)
  S3 object storage (product images; RustFS in development)
  OpenTelemetry → Tempo · Prometheus → Grafana · Loki (logs)
```

Synchronous calls go **client → gateway → service**. Service-to-service business flows go through
**Kafka**, not direct HTTP. The only permitted synchronous inter-service calls are read-only
lookups through the gateway, and they must be circuit-broken.

---

## Services

| Service | Port | Owns | Key responsibilities |
|---|---|---|---|
| `api-gateway` | 8080 | — | Single ingress, route table, JWT validation via JWKS, per-user + per-IP rate limiting, CORS, correlation-ID injection, aggregated OpenAPI |
| `auth-service` | 8081 | `auth` schema | Users, roles, permissions, branch assignments, registration + email OTP, login, refresh-token rotation with reuse detection, JWKS endpoint, password reset, audit log |
| `catalog-service` | 8082 | `catalog` schema | Products, categories, brands, units of measure, barcodes (incl. scale/weight-embedded), tax classes and effective-dated rates, price lists per branch, promotions and pricing rules |
| `inventory-service` | 8083 | `inventory` schema | Stock on hand per branch, batches/lots with expiry, FEFO deduction, stock movements, adjustments, inter-branch transfers, stock takes, low-stock and near-expiry alerts |
| `purchasing-service` | 8084 | `purchasing` schema | Suppliers, purchase orders and approvals, goods received notes, supplier invoices, returns to supplier, landed cost |
| `sales-service` | 8085 | `sales` schema | Till sessions/shifts, carts, checkout saga, sales and sale lines, price/tax snapshots, receipts, returns and voids, offline sale sync with idempotency, Z-report data |
| `payment-service` | 8086 | `payment` schema | Payment intents, cash tendering and change, M-Pesa STK Push + callback reconciliation, card terminal reference capture, refunds, settlement reconciliation |
| `customer-service` | 8087 | `customer` schema | Customers, membership tiers, loyalty point accrual and redemption, customer-level pricing eligibility |
| `notification-service` | 8088 | `notification` schema | Kafka-driven email via SMTP (OTP, welcome, receipts, alerts), Thymeleaf templates, delivery log, retry + DLT handling |
| `reporting-service` | 8089 | `reporting` schema | Read-model projections built from events: sales by day/branch/cashier/product, margins, stock valuation, Z-reports, exports |
| `web` | 3000 | — | Next.js cashier lane and back-office admin |

Shared Maven modules: `common-lib` (error model, correlation, resource-server security, auditing,
pagination) and `events-lib` (event envelope, topic names, event DTOs). **Libraries carry no
business logic and no service-specific entities.**

---

## Technology stack

**Backend** — versions pinned and verified in `backend/pom.xml`
- Java 21 (virtual threads enabled)
- **Spring Boot 4.0.8** with **Spring Cloud 2025.1.3** (the aligned pair — Cloud 2025.1.x targets
  Boot 4.0.x; Boot 4.1 has no matching Cloud release yet and we need Cloud Gateway)
- Spring Security (OAuth2 resource server), Spring Data JPA, Spring for Apache Kafka
- PostgreSQL 18, Flyway, Redis 8, S3 via the AWS SDK 2.x (RustFS locally)
- MapStruct 1.6.3, Lombok, Bean Validation, springdoc-openapi 3.1.1
- Maven 3.9.16 via the wrapper (`backend/mvnw`), multi-module, all versions in the parent POM

> Boot 4 renamed several starters. Use `spring-boot-starter-webmvc`,
> `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-kafka`,
> `spring-boot-starter-flyway` and `spring-boot-starter-aspectj` — not their Boot 3 names.

**Frontend**
- Next.js 16.3 (App Router), TypeScript strict mode, React 19.2
- Tailwind CSS 4 + shadcn/ui (on Base UI), TanStack Query, Zustand
- Dexie (IndexedDB) + Workbox service worker for offline checkout
- Zod for runtime validation of every API boundary

**Platform**
- Docker + Docker Compose (dev and production profiles)
- Kafka 4 in KRaft mode (no ZooKeeper)
- OpenTelemetry Collector, Tempo, Prometheus, Grafana, Loki
- GitHub Actions CI: build → unit tests → Testcontainers integration tests → Trivy scan → image push

**Testing**
- JUnit 5, AssertJ, Mockito, Testcontainers (Postgres + Kafka), WireMock, ArchUnit
- Vitest + Testing Library, Playwright end-to-end
- k6 for load testing the checkout path

---

## Repository layout

```
pos-java-project/
├── backend/
│   ├── pom.xml                      # parent POM: versions, plugins, profiles
│   ├── mvnw, mvnw.cmd, .mvn/        # Maven wrapper (script-only, 3.9.16)
│   ├── common-lib/                  # errors, correlation, security, auditing, money
│   ├── events-lib/                  # event envelope, topic names, payloads
│   ├── messaging-lib/               # transactional outbox, idempotent consumption
│   ├── api-gateway/
│   ├── auth-service/
│   ├── catalog-service/
│   ├── inventory-service/
│   ├── purchasing-service/
│   ├── sales-service/
│   ├── payment-service/
│   ├── customer-service/
│   ├── notification-service/
│   └── reporting-service/
├── frontend/
│   └── web/                         # Next.js app (cashier + back office)
├── infra/
│   ├── docker/                      # Dockerfiles, entrypoints
│   ├── compose/                     # base, dev override, prod, observability
│   ├── postgres/init/               # schema + role bootstrap SQL
│   ├── kafka/                       # topic creation
│   ├── observability/               # otel, prometheus, grafana, loki config
│   └── traefik/                     # production TLS reverse proxy
├── docs/
│   ├── REQUIREMENTS.md
│   ├── ROADMAP.md
│   └── ARCHITECTURE.md
├── .github/workflows/ci.yml
├── Makefile                         # developer entrypoint - run `make`
├── .env.example                     # env contract; `make env` generates .env
├── CLAUDE.md
└── readme.md
```

Every backend service follows the same internal package structure — see
[CLAUDE.md](CLAUDE.md#service-internal-structure).

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (LTS) | Verified present (21.0.12). |
| Maven | 3.9.16 via `backend/mvnw` | Script-only wrapper — no system Maven, no jar in git. |
| Node.js | 22 LTS or 24 | Verified present (24.x). |
| Docker Engine | 24+ | Verified present (29.x). |
| Docker Compose | v2+ | Verified present. |
| Git | 2.4+ | |

> **Docker Desktop on Linux:** the daemon only bind-mounts from directories on its file-sharing
> list. This repo lives on `/mnt/extra`, which is not shared by default, so `/mnt/extra` was added
> to `FilesharingDirectories` in `~/.docker/desktop/settings-store.json`. Without it, every Compose
> bind mount fails with *"path is not shared from the host"*. Run `make doctor` if the stack will
> not start.

Accounts and credentials needed before the phases that use them:

| Needed for | What to obtain | Phase |
|---|---|---|
| Outbound email (dev) | Gmail account with **2FA enabled** and an **App Password** (16 chars) | 5 |
| Outbound email (prod) | **AWS SES**: a verified sending domain, production access (out of the sandbox), and SES **SMTP credentials** | 16 |
| M-Pesa | Safaricom Daraja **sandbox** app: consumer key, consumer secret, shortcode, passkey. Production shortcode later. | 10 |
| Public callbacks | A tunnel (`cloudflared` or `ngrok`) so Daraja can reach your local callback URL | 10 |
| Deployment | A VPS (4 vCPU / 8 GB RAM minimum), a domain name, DNS access | 15 |

Mail is real in every environment: development sends through **Gmail**, production through **AWS
SES**'s SMTP interface. One SMTP client, one code path, different config. Browser end-to-end runs
never send mail: the e2e overlay switches notification-service to *capture* mode, which writes each
message to a file inside its container for the test to read.

---

## Getting started

> These commands become valid as the phases in [docs/ROADMAP.md](docs/ROADMAP.md) land. Phase 1
> delivers the infra stack; Phase 3 the first running service.

```bash
git clone <repo-url> && cd pos-java-project

make doctor                     # confirm JDK, Node, Docker are usable
make env                        # writes .env with generated secrets (never overwrites)
make env-sync                   # an existing .env: add secrets a later phase introduced (never overwrites)
make up                         # infrastructure + services; gateway on :8080
make ps                         # container status
make verify                     # full build: format, tests, integration tests, coverage
make admin email=… name="…"     # an administrator: every permission, every branch (temporary password)
make admin-check email=…        # sign in as that administrator and read through every service and branch
make demo-seed                  # demo data through the APIs: 5 branches, 20 staff, products, suppliers, sales...
make demo-clear                 # remove the demo data again, and nothing else (repeatable)
make postman                    # regenerate the Postman/Insomnia collection in postman/
make api-smoke                  # send every GET in the collection to the stack; fails on a 5xx
```

Demo sign-in: any `...@demo.pos.local` user with `Demo-Password-2026` (see
[scripts/demo/README.md](scripts/demo/README.md)). The API collection and how to import it are in
[postman/README.md](postman/README.md).

`make infra-up` brings up only the infrastructure, for running a service from your IDE against it.

`make` on its own lists every target. Backend Maven commands run from `backend/`
(`cd backend && ./mvnw …`); the Makefile targets do this for you.

| URL | What |
|---|---|
| http://localhost:8080 | **API gateway — the only way in** (live now) |
| http://localhost:8080/api/v1/pricing/resolve | Price a basket, with the full tax and discount breakdown |
| localhost:5432 | PostgreSQL (**live now**) |
| localhost:29092 | Kafka, from the host (`kafka:9092` inside the network) (**live now**) |
| localhost:6379 | Redis (**live now**) |
| localhost:9000 | Object store (RustFS, S3-compatible): product images (**live now**) |
| http://localhost:3000 | Next.js app: sign in, register, lane and back office |
| http://localhost:8080/swagger-ui.html | Aggregated OpenAPI |
| http://localhost:3001 | Grafana (Phase 16) |

Seed data (`make seed`) arrives with the services that own it; it will create one company, two
branches, a `SUPER_ADMIN`, sample products with mixed tax classes, and a supplier with an open PO.

---

## Environment configuration

No secret is ever committed. `.env.example` is the contract; `.env` is git-ignored.

```env
# --- Postgres ---
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=pos
POSTGRES_SUPERUSER=pos_admin
POSTGRES_SUPERUSER_PASSWORD=

# --- per-service DB roles (one per schema) ---
AUTH_DB_USER=auth_user
AUTH_DB_PASSWORD=
# ... one pair per service

# --- JWT ---
JWT_ISSUER=https://auth.pos.local
JWT_ACCESS_TTL=PT15M
JWT_REFRESH_TTL=P7D
JWT_KEYSTORE_PATH=/run/secrets/jwt-keystore.p12
JWT_KEYSTORE_PASSWORD=

# --- Kafka ---
KAFKA_BOOTSTRAP_SERVERS=kafka:9092

# --- Redis ---
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=

# --- SMTP (Gmail in dev, AWS SES in prod; both port 587 with STARTTLS) ---
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_AUTH=true
SMTP_STARTTLS=true
MAIL_FROM="Realhive Group of Supermarkets POS <no-reply@example.com>"

# --- OTP policy ---
OTP_LENGTH=6
OTP_TTL=PT10M
OTP_MAX_ATTEMPTS=5
OTP_RESEND_COOLDOWN=PT60S

# --- M-Pesa Daraja ---
MPESA_ENV=sandbox
MPESA_CONSUMER_KEY=
MPESA_CONSUMER_SECRET=
MPESA_SHORTCODE=
MPESA_PASSKEY=
MPESA_CALLBACK_URL=
MPESA_CALLBACK_TOKEN=          # generated by make env; the secret in Daraja's callback URL
MPESA_INITIATOR_NAME=          # reversals (M-Pesa refunds) only
MPESA_SECURITY_CREDENTIAL=     # reversals only
MPESA_CALLBACK_ALLOWED_IPS=    # optional
```

In production these come from Docker secrets, not a `.env` file. Rotation procedure lives in
`docs/RUNBOOK.md` (Phase 15).

---

## Security model

**Tokens**

- **Access token** — RS256 JWT, 15 minutes, claims: `sub`, `uid`, `roles`, `perms`, `branches`,
  `tv` (token version), `jti`. Signed by `auth-service`, verified by the gateway *and* by each
  service independently via cached JWKS. Never stored in `localStorage`.
- **Refresh token** — opaque 256-bit random value, SHA-256 hashed at rest, 7 days, **rotated on
  every use**. Rotation families are tracked: presenting an already-used refresh token revokes the
  entire family and forces re-login. Held in an `httpOnly`, `Secure`, `SameSite=Strict` cookie set
  by the Next.js BFF route handlers, so browser JavaScript never touches it.
- **In the browser** — neither token. The BFF keeps both in encrypted (JWE) `httpOnly` cookies and
  attaches the access token itself when it calls the gateway; `proxy.ts` is the one place a session
  is refreshed, so a burst of requests never spends a refresh token twice.
- Logout revokes the refresh family; a password change or role change bumps `tv`, invalidating every
  outstanding access token at the next request.

**Registration flow**

```
POST /auth/register → user created status=PENDING_VERIFICATION
                    → outbox → Kafka: auth.otp-requested.v1
                    → notification-service sends 6-digit OTP by email
POST /auth/verify-otp → status=ACTIVE, Kafka: auth.user-registered.v1
                      → welcome email, default role applied
POST /auth/login → access + refresh tokens
```
OTPs are hashed at rest, single-use, expire in 10 minutes, cap at 5 attempts, and are rate-limited
per email and per IP. Verification responses are deliberately uniform so they cannot be used to
enumerate registered emails.

**Authorization**

Permissions are strings like `sale:create`, `sale:void`, `inventory:adjust`, `price:override`,
`report:view`, `user:manage`. Roles bundle permissions and are editable at runtime — **adding a role
requires no deployment**. Seeded roles: `SUPER_ADMIN`, `BRANCH_MANAGER`, `SUPERVISOR`, `CASHIER`,
`STOCK_CONTROLLER`, `ACCOUNTANT`, `AUDITOR` (read-only). Every endpoint carries an explicit
`@PreAuthorize` check on a permission, never on a role name, plus a branch-scope check. Deny by
default.

**Other controls** — Argon2id password hashing; account lockout with exponential backoff; gateway
rate limiting in Redis; strict CORS allowlist; Bean Validation on every DTO; JPA parameter binding
only; no card data ever stored (terminal reference only); full audit log of privileged actions
(price override, void, refund, stock adjustment, role change); TLS everywhere in production.

---

## Event-driven communication

Every event shares one envelope:

```json
{
  "eventId": "01J8...ULID",
  "eventType": "sales.sale-completed",
  "schemaVersion": 1,
  "occurredAt": "2026-09-20T10:15:30.123Z",
  "correlationId": "…",
  "causationId": "…",
  "branchId": "…",
  "actorId": "…",
  "payload": { }
}
```

Topics are named `pos.<domain>.<event>.v<version>` with a matching `.dlt` dead-letter topic.
Producers write to a **transactional outbox** table in the same DB transaction as the state change;
a publisher relays it to Kafka. Consumers are **idempotent**, recording `eventId` in a
`processed_event` table before acting. Message key is the aggregate id, so ordering per aggregate
holds.

The checkout saga:

```
sales: sale PENDING ──▶ pos.payments.payment-requested.v1
payment: authorize (cash/M-Pesa/card) ──▶ pos.payments.payment-authorized.v1
sales: sale PAID ──▶ pos.sales.sale-completed.v1
   ├─▶ inventory: FEFO deduction, emits stock-deducted / low-stock
   ├─▶ customer:  loyalty accrual
   ├─▶ reporting: projects into read models
   └─▶ notification: emails the receipt
```
Payment failure or timeout emits `payment-failed`, and `sales` compensates by releasing
reservations and marking the sale `CANCELLED`. The full catalogue is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Data ownership

One Postgres instance, one schema per service, one DB role per schema with grants on that schema
only. A service that needs another's data either subscribes to its events and keeps a local
read-model copy, or calls its API. **No joins across schemas — enforced by database grants, not
discipline.** Each schema can be lifted to its own instance later with no application change.

Money is `NUMERIC(19,4)`, never floating point. Quantities are `NUMERIC(19,3)` to support weighed
goods. All timestamps are `TIMESTAMPTZ` stored in UTC. Sale lines snapshot price, cost, tax class
and rate at the moment of sale so history never shifts under a later price or tax change.

---

## Testing strategy

| Layer | Tool | Gate |
|---|---|---|
| Unit | JUnit 5 + AssertJ + Mockito | Domain logic; pricing, tax, FEFO, change calculation |
| Integration | Testcontainers (Postgres, Kafka) | Repositories, Flyway migrations, real produce/consume round-trips |
| Contract | Spring Cloud Contract / WireMock | Gateway ↔ service, event payload shape |
| Architecture | ArchUnit | Layer rules, no cross-service imports, no `@Autowired` fields |
| E2E | Playwright | Register→OTP→login→sell→pay→receipt; offline sale then sync |
| Load | k6 | Checkout p95 under target at expected lane concurrency |

Coverage gate: 80% line coverage on service modules, enforced by JaCoCo in CI. Domain/pricing/tax
packages are held higher.

---

## Observability

Every service exposes `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` and
`/actuator/info`. Logs are structured JSON carrying `correlationId`, `userId` and `branchId`;
the gateway generates a correlation ID if the client did not, and it propagates through HTTP headers
and Kafka event envelopes alike. Traces go through the OpenTelemetry Collector to Tempo, metrics to
Prometheus, logs to Loki, all surfaced in Grafana. Alert rules cover consumer lag, DLT arrivals,
error rate, p95 latency, low disk and failed payment callbacks.

---

## Deployment

**Development** — `docker-compose.yml` + `docker-compose.dev.yml`: hot reload via Spring DevTools,
debug ports exposed, seed data; mail through Gmail.

**Production** — `docker-compose.prod.yml`: multi-stage distroless images built by CI and pinned by
digest, Traefik terminating TLS with automatic Let's Encrypt certificates, Docker secrets rather
than env files, resource limits and restart policies per service, healthcheck-gated startup order,
nightly `pg_dump` to off-site storage with a documented restore drill, and Kafka retention tuned per
topic. Flyway migrations run on service startup and must be backwards compatible — expand, migrate,
contract — so a rolling restart never breaks the previous version mid-deploy.

The Compose layout maps service-for-service onto Kubernetes when scale demands it; nothing in the
application code assumes Compose.

---

## Documentation index

| Document | Purpose |
|---|---|
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | Functional and non-functional requirements, domain model, roles and permissions |
| [docs/ROADMAP.md](docs/ROADMAP.md) | The 16-phase build plan, each with deliverables and a definition of done |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Event catalogue, saga flows, schema ownership, ADRs |
| [CLAUDE.md](CLAUDE.md) | Engineering conventions and working agreement for this repository |
