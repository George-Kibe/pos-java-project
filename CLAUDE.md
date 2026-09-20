# CLAUDE.md

Working agreement for this repository. Read this before writing code here.

## Project

Multi-branch supermarket POS. Spring Boot microservices (Java 21, Maven multi-module) behind a
Spring Cloud Gateway, Kafka for inter-service events, PostgreSQL with a schema per service, Redis,
and a Next.js 15 frontend serving both the cashier lane and the back office. Docker Compose for dev
and production.

Architecture and scope live in [readme.md](readme.md); the build order lives in
[docs/ROADMAP.md](docs/ROADMAP.md). **Keep all three in sync — if a change alters the architecture,
update the docs in the same commit.**

## How we work

- **Phase by phase.** Work the current phase in `docs/ROADMAP.md`. Each phase ends in something
  runnable and verifiable, and stops for review before the next one starts. Do not scaffold ahead.
- **Ask before deviating.** If a phase's design conflicts with what the code needs, raise it rather
  than silently taking a different path.
- **Finish the slice.** A feature is done when it has migrations, validation, tests, error handling,
  OpenAPI annotations, and its events wired — not when the happy path compiles.
- **Never invent credentials, shortcodes or keys.** If a secret is missing, stop and ask.

## Commands

Maven runs from `backend/` (that is where the wrapper and parent POM live).

```bash
make                               # list every target
make doctor                        # verify local tooling
make env                           # generate .env with random secrets
make infra-up / infra-down / ps / logs svc=<name>
make build | fmt | test | it | verify
make psql | redis-cli | kafka-topics

cd backend && ./mvnw -pl sales-service test
cd backend && ./mvnw -pl sales-service verify -Pintegration   # Testcontainers
cd backend && ./mvnw spotless:apply                            # format before committing

cd frontend/web && npm run dev | lint | test | e2e
```

Stack versions are **Spring Boot 4.0.8 + Spring Cloud 2025.1.3** (the aligned pair). Boot 4 renamed
starters: use `spring-boot-starter-webmvc`,
`spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-kafka`,
`spring-boot-starter-flyway`, `spring-boot-starter-aspectj`. Never add a `<version>` to a
dependency in a child module — the parent POM owns every version.

Run the narrowest thing that proves the change: a single test class beats a full build.

## Module layout

```
backend/
  pom.xml            parent: ALL dependency versions in <dependencyManagement>, no versions in children
  common-lib/        error model, correlation filter, resource-server config, auditing, pagination
  events-lib/        event envelope, topic constants, event DTOs — no business logic
  <name>-service/
```

`common-lib` and `events-lib` never depend on a service. A service never depends on another service.
ArchUnit enforces this; do not add an exception.

## Service internal structure

```
com.pos.<service>/
  api/          controllers, request/response DTOs, exception handlers
  domain/       entities, value objects, domain services, domain events
  repository/   Spring Data repositories
  service/      application services — orchestration and transactions
  messaging/    Kafka producers, consumers, outbox publisher
  config/       Spring configuration
  mapper/       MapStruct mappers
src/main/resources/db/migration/   Flyway V<n>__<description>.sql
```

Rules:
- Controllers hold no business logic — validate, delegate, map, return.
- Entities never leave the service layer. Controllers speak DTOs only.
- `@Transactional` belongs on application services, never on controllers or repositories.
- Constructor injection only. No field `@Autowired`.
- No `Optional` as a parameter type; no checked exceptions in signatures.

## Data rules

- **One schema per service, one DB role per schema.** Never query another service's schema — no
  cross-schema joins, no foreign keys across schemas. Need another service's data? Subscribe to its
  events and keep a local read model, or call its API.
- Money: `NUMERIC(19,4)` in Postgres, `BigDecimal` in Java, **never** `double`/`float`. Always carry
  a currency. Round only at display or at the documented rounding step, using `RoundingMode.HALF_UP`.
- Quantities: `NUMERIC(19,3)` — weighed goods are fractional.
- Timestamps: `TIMESTAMPTZ`, stored UTC, `Instant` in Java. Local time is a presentation concern.
- Primary keys: UUIDv7 generated in the application (time-ordered, index-friendly, safe for
  offline-created records).
- Every table gets `created_at`, `updated_at`, `created_by`, `updated_by`, and `version` for
  optimistic locking. Transactional tables also get `branch_id`.
- Flyway migrations are **immutable once merged** — fix forward with a new migration. Migrations must
  be backwards compatible with the running version (expand → migrate → contract).
- Never delete transactional records. Use status transitions and reversing entries; a void or refund
  is a new record, never an `UPDATE` that erases history.
- Sale lines snapshot price, unit cost, tax class and tax rate at sale time.

## Events

- Topic: `pos.<domain>.<event>.v<version>`, e.g. `pos.sales.sale-completed.v1`. Dead letter:
  same name + `.dlt`.
- Every event uses the shared envelope in `events-lib` (`eventId`, `eventType`, `schemaVersion`,
  `occurredAt`, `correlationId`, `causationId`, `branchId`, `actorId`, `payload`).
- **Producers use the transactional outbox.** Write the outbox row in the same transaction as the
  state change; the publisher relays it. Never call `kafkaTemplate.send()` inside business logic.
- **Consumers are idempotent.** Record `eventId` in `processed_event` before acting; a redelivery
  must be a no-op. Kafka guarantees at-least-once, not exactly-once.
- Message key = aggregate id, so per-aggregate ordering holds.
- Schema evolution is additive only. A breaking change means a new `.v2` topic and a period of dual
  publishing — never a redefinition of `.v1`.
- Consumer failures retry with backoff, then land in the DLT. DLT arrivals are alertable, never
  silent.

## Security

- Every endpoint carries an explicit `@PreAuthorize` on a **permission** (`sale:void`), never on a
  role name. Deny by default — an endpoint without an authorization check does not get merged.
- Every endpoint that touches branch-scoped data verifies the caller is assigned to that branch.
  Use the shared `BranchAccessGuard`; do not reimplement it.
- Services validate the JWT themselves via JWKS. The gateway is not the only line of defence.
- Never log tokens, OTPs, passwords, M-Pesa credentials, or full customer phone numbers. The
  logging masker in `common-lib` covers the known fields — extend it when adding new sensitive ones.
- Never store card data. Card payments record a terminal reference and approval code only.
- Privileged actions (price override, void, refund, stock adjustment, role change, till drop) write
  an audit record with actor, branch, before/after and reason.

## API conventions

- Base path `/api/v1/<resource>`, plural nouns, kebab-case multiword segments.
- Standard verbs and codes: `200`, `201` + `Location`, `204`, `400`, `401`, `403`, `404`, `409`,
  `422`, `429`, `500`.
- Errors use RFC 7807 `application/problem+json` via the shared handler — one shape everywhere:
  `type`, `title`, `status`, `detail`, `instance`, `correlationId`, `errors[]`.
- Collections are paginated (`page`, `size`, `sort`), max page size 100, wrapped in the shared
  `PageResponse<T>`.
- Every mutating endpoint that a cashier terminal can call accepts an `Idempotency-Key` header and
  honours it — offline terminals retry.
- Annotate with springdoc; the gateway aggregates the specs.

## Frontend

- App Router, TypeScript `strict`, Server Components by default; `"use client"` only where
  interactivity demands it.
- **Auth is BFF.** Next.js route handlers hold the refresh token in an `httpOnly`, `Secure`,
  `SameSite=Strict` cookie and mint short-lived access tokens server-side. The access token never
  goes to `localStorage` and never appears in client state.
- All server responses are parsed through Zod schemas at the boundary. No `any`, no unchecked casts.
- TanStack Query for server state, Zustand for terminal-local state (open cart, shift, device).
- Offline: writes queue in Dexie with a client-generated UUID and are replayed with an
  `Idempotency-Key`; the UI shows a clear online/offline/syncing state. Never silently drop a queued
  sale.
- The cashier lane is keyboard-first: every action has a shortcut, scanner input is captured
  globally, and nothing on the checkout path requires a mouse.
- Accessibility and touch targets matter — lanes run on touchscreens under time pressure. Large hit
  areas, high contrast, no hover-only affordances.

## Testing

- Unit tests for every domain rule: pricing, promotions, tax, FEFO, change due, loyalty accrual.
- Integration tests use **Testcontainers** — Postgres and Kafka. No H2, no embedded Kafka, no
  mocking the database.
- Every Kafka flow gets a produce-and-consume round-trip test, including the idempotent redelivery
  case.
- Test names read as sentences: `voidingASaleRestoresReservedStock()`.
- 80% line coverage gate on service modules; domain packages held higher. Do not lower the gate to
  make a build pass.

## Git

- Branches: `feat/…`, `fix/…`, `chore/…`, `docs/…`.
- Conventional Commits with a service scope: `feat(sales): add offline sale sync endpoint`.
- Commit only when asked. Never commit `.env`, keystores, credentials or `.DS_Store`.
- Keep commits scoped to one concern; migrations ship with the code that needs them.

## The rollback trap (this bit us three times in one phase)

**A state change that must survive a rejected request needs its own transaction.** The pattern is
easy to write and invisible until someone attacks the system:

```java
// WRONG - the counter is rolled back by the exception that follows it
@Transactional
void login(...) {
    recordFailure(user);          // same transaction
    throw new UnauthorizedException(...);   // rolls back the record
}
```

Caught three times in Phase 3, each looking correct from outside while doing nothing:

| What | Why it was silently broken |
|---|---|
| Failed-login counter | Reset on every failure, so lockout could never trigger |
| OTP attempt counter | Reset on every wrong guess, leaving a 6-digit code brute-forceable |
| Refresh-token reuse revocation | Request was refused, but the stolen family stayed live and worked next time |

The fix is a `@Transactional(propagation = REQUIRES_NEW)` method **on a different bean** (a
self-invocation does not go through the proxy). See `LoginAttemptService`, `OtpService.verify` and
`SessionRevocationService`.

**And never pass a managed JPA entity into that separate transaction.** The inner transaction
bumps the row's version while the caller still holds the old one, and the caller's next flush dies
with an optimistic-lock failure on a row it never meant to touch. Pass the id and let the inner
transaction load its own copy.

## Stack traps already hit (do not rediscover these)

- **Boot 4 starter names changed.** `spring-boot-starter-aop` no longer exists - it is
  `-aspectj`. There are also new `-flyway`, `-kafka`, `-webmvc`,
  `-security-oauth2-resource-server`, `-micrometer-metrics` and `-opentelemetry` starters.
- **Jackson 3 is the default.** Core and databind are `tools.jackson.*`; annotations stayed at
  `com.fasterxml.jackson.annotation`. Importing `com.fasterxml.jackson.databind.ObjectMapper`
  will not resolve.
- **Test auto-configuration is split per module.** `@AutoConfigureMockMvc` is now
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, from
  `spring-boot-starter-webmvc-test`. Not from `spring-boot-starter-test`.
- **Flyway needs its database module.** `flyway-core` alone rejects Postgres with
  "Unsupported Database". Every service running migrations must also depend on
  `org.flywaydb:flyway-database-postgresql`.
- **Testcontainers 2.x renamed everything.** Artifacts are `testcontainers-postgresql`,
  `testcontainers-kafka`, `testcontainers-junit-jupiter`; containers live in
  `org.testcontainers.postgresql` / `org.testcontainers.kafka`; and `PostgreSQLContainer` is no
  longer generic, so `new PostgreSQLContainer<>(...)` does not compile.
- **Testcontainers' apache/kafka container fails on this machine** with
  `/tmp/testcontainers_start.sh: Text file busy` (Docker Desktop + gVisor). Integration tests use
  `ConfluentKafkaContainer` with `confluentinc/cp-kafka`; Docker Compose still runs
  `apache/kafka`. Same protocol, so nothing under test is affected.
- **Never declare a dependency twice in one POM.** An `<optional>true</optional>` entry followed
  by a `<scope>test</scope>` entry for the same artifact silently makes it test-only and the main
  build stops compiling. Optional dependencies are already on the declaring module's own compile
  and test classpath.
- **`@ConditionalOnBean` must not name a bean from its own auto-configuration.** Conditions are
  evaluated against the beans registered *so far*, so the condition races its own declaration
  order and usually loses. The outbox relay scheduler was conditional on `OutboxPublisher`, which
  the same class defines, so the scheduler was silently never created: services started, the
  outbox accepted rows, and they sat at `PENDING` with zero attempts and **no error at all**,
  because nothing was asking to publish them. Latent from Phase 2 until Phase 5 needed a real
  event delivered. Name a bean from an auto-configuration you are explicitly ordered `after`.
  `OutboxRelayWiringIT` is the regression test: it waits for the relay instead of calling it.
- **A library auto-configuration must not activate on classes alone.** `@ConditionalOnClass` sees
  JPA on the classpath even in a service with no datasource. Guard on the bean that actually
  matters, e.g. `@ConditionalOnBean(EntityManagerFactory.class)`.
- **The MDC does not cross a circuit breaker's thread boundary.** Resilience4j enforces its
  timeout by running the call on its own thread pool, so anything reading the MDC there (a
  correlation id, for instance) sees nothing and silently invents a new one. Carry such values on
  the request (a request attribute) rather than the thread. Caught in Phase 4, where every proxied
  request was getting a fresh correlation id and breaking tracing.
- **A test-scoped dependency can mask a missing runtime one.** api-gateway needed
  `spring-boot-restclient` to run; it was declared test-scoped for `TestRestTemplate`, so every
  test passed and the container died at startup on `NoClassDefFoundError`. Run the image, not only
  the tests.
- **`ExponentialBackOffWithMaxRetries` is gone in Spring 7.** Use `ExponentialBackOff` with
  `setMaxAttempts(long)`.
- **`HttpHeaders` no longer implements `Map` in Spring 7.** `containsKey` is gone; use
  `getFirst(name) != null` or `containsHeader`.
- **Set the charset when writing a response by hand.** The servlet default is ISO-8859-1, so a
  hand-written `problem+json` body mangles any non-ASCII text. Always
  `response.setCharacterEncoding("UTF-8")`.
- **A null String parameter inside a SQL function breaks PostgreSQL.** `WHERE (:q IS NULL OR
  lower(x) LIKE lower(:q))` fails with `function lower(bytea) does not exist`, because the driver
  sends an untyped null. Use two code paths (`findAll` vs `search`) instead of a null-or branch.
- **`hibernate.default_schema` does not apply to plain JDBC.** The outbox is written with
  `JdbcClient`, which resolves unqualified names against the connection's `search_path`. Set
  `spring.datasource.hikari.schema` so JPA and JDBC agree.
- **Spring Security has no notion of a wildcard permission.** `hasAuthority('x')` compares strings,
  so a SUPER_ADMIN carrying only `*` is refused everywhere. `AccessTokenIssuer` expands `*` into
  the concrete permission list at issue time; do not "fix" this by teaching each check about `*`.
- **`@Container` stops the container when its test class finishes**, while Spring's context cache
  keeps handing out the context built for it - so the second test class talks to a dead port and
  every request 500s. Start the container in a `static {}` block and never stop it.
- **Never name a test config file `application.yml`.** In `src/test/resources` it shadows the main
  one instead of merging, silently dropping everything the real config sets. Use
  `application-test.yml` with `@ActiveProfiles("test")`.
- **`TestRestTemplate` is opt-in in Boot 4** via `@AutoConfigureTestRestTemplate`, needs
  `spring-boot-resttestclient`, and its auto-configuration additionally needs
  `spring-boot-restclient`. PATCH also needs `httpclient5` on the test classpath.
- **`@PreAuthorize` denials bypass the security filter chain.** They are thrown inside the
  application, so `AccessDeniedHandler` never sees them and a catch-all `@ExceptionHandler` turns
  every 403 into a 500. `SecurityExceptionHandler` in common-lib handles this - do not remove it.

## Testing traps

- **Tests that share a broker must select their own message**, not "the first record on the
  topic" - which is whichever test got there first.
- **Tests that share a database must not share a table when one of them has a live scheduler.**
  A relay running in one Spring context will happily publish rows another test is asserting stay
  `PENDING`. Give such a context its own schema.
- **Asynchronous retries continue after an assertion passes.** A second test truncating tables
  while the first message is still being retried produces rows belonging to neither. Follow one
  message to its end in one test.

## Traps specific to this codebase

- A sale's totals are computed **server-side, always.** Client-sent totals are advisory and must be
  revalidated — an offline terminal may hold stale prices, and the server flags the variance rather
  than trusting either side blindly.
- FEFO deduction must handle partial batch consumption across multiple batches in one sale line.
- M-Pesa callbacks arrive out of order, arrive twice, and sometimes never arrive. Treat the callback
  as a hint, reconcile against the query API, and make the handler idempotent on
  `CheckoutRequestID`.
- Tax-inclusive pricing means the line total is the source of truth and tax is extracted from it;
  do not add tax on top of an inclusive price.
- Weight-embedded scale barcodes encode price or weight in the digits — parse by configured prefix
  rule, never assume a single format.
- Refunds are not negative sales: they reference the original sale, respect a returns policy window,
  and restock to a batch only when the goods are resaleable.
