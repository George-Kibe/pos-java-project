# CLAUDE.md

Working agreement for this repository. Read this before writing code here.

## Project

Multi-branch supermarket POS("Realhive Group of Supermarkets"). Spring Boot microservices (Java 21, Maven multi-module) behind a
Spring Cloud Gateway, Kafka for inter-service events, PostgreSQL with a schema per service, Redis,
and a Next.js 16 frontend serving both the cashier lane and the back office. Docker Compose for dev
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
- **The user manuals follow the screens.** `docs/user-manuals` describes, role by role, what each
  person sees and clicks. A change to a screen, a label, a shortcut or who may do something updates
  the manual for every role it touches, in the same commit. The POS renders these same files on
  its **Manual** page; a new manual file is listed in `frontend/web/src/lib/manuals.ts` or the
  page cannot show it.

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
src/main/resources/db/migration/   Flyway V<n>__<description>.sql
```

Rules:
- Controllers hold no business logic — validate, delegate, map, return.
- Entities never leave the service layer. Controllers speak DTOs only; a response DTO maps itself
  with a static `from(entity)`, no mapping framework. It runs after the transaction has closed, so
  everything it reads must be fetched with the aggregate.
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
  The one exception is a **provider callback** (Daraja cannot present a token): it is
  `permitAll()`, listed as a public path in both the service and the gateway, and authenticated
  by a secret path token checked in constant time before the body is read (`CallbackGuard`).
- Every endpoint that touches branch-scoped data verifies the caller is assigned to that branch.
  Use the shared `BranchAccessGuard`; do not reimplement it. **Check before the write, not after**:
  controllers are not transactional, so a check on the object just created answers 403 with the
  row already committed. Guard on the parent (the shift, the cart) the request names.
- Forward the caller's token to a downstream service with `AuthenticatedUser.bearerToken()` - the
  token the resource server verified - rather than re-reading the `Authorization` header.
- Services validate the JWT themselves via JWKS. The gateway is not the only line of defence.
- **Staff work only from a branch's or head office's network.** Production's Traefik admits
  `ALLOWED_CLIENT_NETWORKS` alone and the gateway's `ClientNetworkFilter` checks the same list;
  M-Pesa's callbacks are the one open path (`pos.gateway.client-access.open-paths`). A new
  public path that a provider calls needs listing there and as a Traefik route. Development binds
  every published port to `127.0.0.1` instead - do not publish one on all interfaces.
- **And only on registered devices** (production, `pos.auth.devices.required`). The device's
  secret lives in the `pos_device` cookie, which sign-out never clears; the login route presents
  it, and a refresh re-checks the session's device. A new way to start a session must do the same,
  or it is a way round the rule.
- Never log tokens, OTPs, passwords, M-Pesa credentials, or full customer phone numbers. The
  logging masker in `common-lib` covers the known fields — extend it when adding new sensitive ones.
- Never store card data. Card payments record a terminal reference and approval code only.
- Privileged actions (price override, void, refund, stock adjustment, role change, till drop) write
  an audit record with actor, branch, before/after and reason.
- **Only the administrator adds a supplier.** `POST /suppliers` needs `supplier:create`, which no
  role is granted - SUPER_ADMIN holds it through `*`. Everyone else who deals with suppliers keeps
  `supplier:manage` for editing, holds and price lists. Do not grant `supplier:create` to a seeded
  role; a new supplier is a new place the business's money can go.
- **No one grants or manages beyond their own rights.** Creating a user or changing their roles
  needs every permission the granted roles carry; changing an account at all needs every permission
  that account holds. So only an administrator makes or touches an administrator, and a branch
  manager with `user:manage` cannot hand out SUPER_ADMIN - to anyone, themselves included
  (`UserAdminService.requireMayGrant` / `requireMayManage`).

## API conventions

- Base path `/api/v1/<resource>`, plural nouns, kebab-case multiword segments.
- Standard verbs and codes: `200`, `201` + `Location`, `204`, `400`, `401`, `403`, `404`, `409`,
  `422`, `429`, `500`, `503`. Use `503` (`Errors.ServiceUnavailableException`) when a service this
  one depends on could not be reached — the caller needs to know nothing was recorded and a retry
  is worth making, which a `500` does not say.
- Errors use RFC 7807 `application/problem+json` via the shared handler — one shape everywhere:
  `type`, `title`, `status`, `detail`, `instance`, `correlationId`, `errors[]`.
- Collections are paginated (`page`, `size`, `sort`), max page size 100, wrapped in the shared
  `PageResponse<T>`.
- Every mutating endpoint that a cashier terminal can call accepts an `Idempotency-Key` header and
  honours it — offline terminals retry. The filter lives in messaging-lib: set
  `pos.idempotency.enabled: true` and create the `idempotency_records` table in the service's own
  migration (a shared migration added after services are past V1 would fail their validation).
- Annotate with springdoc; the gateway aggregates the specs.

## Frontend

- **Next.js 16 is not the Next.js you remember.** Middleware is `proxy.ts`, request APIs are async,
  route types are generated. Read `frontend/web/node_modules/next/dist/docs/` before writing code.
- App Router, TypeScript `strict`, Server Components by default; `"use client"` only where
  interactivity demands it. Route groups: `(auth)`, `(pos)` for the lane, `(admin)` for the back
  office.
- **Auth is BFF.** Route handlers sign in against auth-service and keep both tokens in encrypted
  (JWE) `httpOnly`, `Secure`, `SameSite=Strict` cookies. No token ever reaches the browser - not
  `localStorage`, not client state, not the page. Client components get a `SessionProvider` with
  name, permissions and branches, never a token.
- **`proxy.ts` is the only place a session is refreshed.** Pages learn who is asking through the
  data access layer (`lib/auth/dal.ts`); browser code reaches the services through
  `/api/gateway/<path>`, which never proxies `auth/*`.
- Permission checks in the UI (`<Can>`, `can()`, the navigation) are conveniences. The services
  enforce the same permissions; never rely on hiding a button.
- All server responses are parsed through Zod schemas at the boundary. No `any`, no unchecked casts.
- TanStack Query for server state, Zustand for terminal-local state (open cart, shift, device).
- Offline: writes queue in Dexie with a client-generated UUID and are replayed with an
  `Idempotency-Key`; the UI shows a clear online/offline/syncing state. Never silently drop a queued
  sale.
- The cashier lane is keyboard-first: every action has a shortcut, scanner input is captured
  globally, and nothing on the checkout path requires a mouse.
- Accessibility and touch targets matter — lanes run on touchscreens under time pressure. Large hit
  areas, high contrast, no hover-only affordances. The design system's buttons and inputs are 44px
  by default; the smaller sizes are for dense back-office tables only.
- Frontend checks: `make web-check` (lint, typecheck, Vitest, build) and `make web-e2e` (Playwright
  against the running stack; notification-service captures mail to files for the run instead of
  sending it, and the tests read the codes with `docker exec`).

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

**This also applies to an exception you fully intend to catch.** customer-service's redemption
threw "not enough points" from a service the listener called; the listener caught it and published
a `payment-failed`, but the throw had already marked the transaction rollback-only, so the failure
rolled back with it and the till waited for an answer that never came. A refusal the caller is
expected to handle is a **returned result**, not an exception.

The fix is a `@Transactional(propagation = REQUIRES_NEW)` method **on a different bean** (a
self-invocation does not go through the proxy). See `LoginAttemptService`, `OtpService.verify` and
`SessionRevocationService`.

**And never pass a managed JPA entity into that separate transaction.** The inner transaction
bumps the row's version while the caller still holds the old one, and the caller's next flush dies
with an optimistic-lock failure on a row it never meant to touch. Pass the id and let the inner
transaction load its own copy.

**A self-invoked `REQUIRES_NEW` is not a separate transaction - it is no transaction at all.** Phase
9's offline sync called its per-sale `@Transactional(REQUIRES_NEW)` method on `this`, so nothing
was transactional; the receipt-number service (`MANDATORY`) then threw on every sale, and a
catch-all turned that into a per-sale `REJECTED`. The endpoint refused every sale it was sent and
looked like it was working. Put the per-item transaction on another bean (`OfflineSaleWriter`) and
catch the failure **outside** it: an exception caught inside a transaction has already marked it
rollback-only, so the commit fails anyway and takes the batch with it.

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
- **Jackson 3 rejects a missing primitive.** `FAIL_ON_NULL_FOR_PRIMITIVES` is on by default, so a
  request that omits an optional `boolean` is answered "Request body could not be parsed" without
  naming the field. Use boxed `Boolean` in request DTOs with an explicit default accessor.
- **Request binding runs before method security.** An invalid body is answered 400 before
  `@PreAuthorize` is ever reached, so a test asserting 403 must send a *valid* body.
- **`CHAR(n)` fails Hibernate's schema validation** against a `String` field (`bpchar` vs
  `varchar`). Use `VARCHAR(n)`; `CHAR` pads with spaces anyway.
- **An `EXCLUDE` constraint needs `DEFERRABLE INITIALLY DEFERRED`** when the normal way to change
  a row is close-one-period-open-the-next. Checked per statement, the two writes overlap for an
  instant whichever order the ORM emits them in.
- **`CREATE EXTENSION` needs privileges a per-service DB role does not have.** Install extensions
  in `infra/postgres/init` as the superuser. Note that init scripts only run on an **empty** data
  directory, so an existing volume needs the extension installed by hand.
- **Every path a service exposes needs a gateway route.** `/api/v1/units-of-measure` and
  `/api/v1/pricing` were missing from catalog's `Path=` predicate and 404'd at the edge while the
  service was healthy and the other paths worked.
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
- **`@EntityGraph` defaults to `FETCH`, which makes every attribute it does not name lazy** -
  including a collection declared `EAGER` on the entity. Adding a graph to eagerly fetch a
  to-one association therefore breaks the collections that used to load. Use
  `@EntityGraph(type = EntityGraphType.LOAD, ...)`, which adds to the declared fetch plan instead
  of replacing it.
- **A lazy association mapped in a controller is a 500, not a second query.** `open-in-view` is
  off, so the session is gone by the time a DTO maps `order.getSupplier().getName()`. Fetch what
  the response needs with the aggregate.
- **A partial unique index needs the old row flushed before the new one.** "One default address per
  customer" is `UNIQUE (customer_id) WHERE is_default`; clearing the old default in memory and
  saving the new one sends the insert first, and the index refuses it. Same shape as the orphan
  removal trap below: `saveAndFlush` the clear.
- **Orphan removal flushes its deletes after the inserts.** Clearing a child collection and adding
  replacements in one go violates a unique constraint on the child's ordinal, because the new row 1
  is inserted while the old row 1 is still there. Flush the clear (`saveAndFlush`) before adding.
- **A new Kafka topic does not exist in an already-running environment.** `kafka-init` is a
  one-shot that ran when the volume was created, so adding a topic to
  `infra/kafka/create-topics.sh` does nothing for a broker that is already up - and auto-creation
  is off on purpose. The first event on the new topic then sits in the outbox retrying while the
  broker answers `UNKNOWN_TOPIC_OR_PARTITION`. Run `make kafka-topics-sync`, which is idempotent.
- **Do not read the outbox mid-pass and conclude the relay is broken.** A pass claims a batch and
  commits once at the end, so a row already sent still reads `PENDING` from another connection
  until the pass commits - and a row whose topic is missing holds the batch for the full
  `pos.outbox.send-timeout` first. Sample twice before diagnosing.

- **An exception thrown inside an `@ExceptionHandler` is nearly silent.** The resolver logs one
  WARN, abandons the handler and rethrows the *original* exception, which then escapes the
  dispatcher - so a deliberate 404 reaches the client as an unhandled 500 with no body, and the log
  line blames the 404. The cause here was an error code interpolated into the problem `type` URI:
  `NotFoundException.of("Stock item", id)` produced `stock item.not_found` and `URI.create`
  rejected the space. `Errors.slug()` now slugs the resource name and `typeUri()` never throws.
  Anything built inside an error handler needs the same treatment.
- **Check the gateway route table with a token, not by reading it.** An unauthenticated request is
  rejected before routing, so every path - routed or not - answers 401 and proves nothing. Only an
  authenticated request distinguishes a live route from a missing one.

- **`@PreAuthorize` denials bypass the security filter chain.** They are thrown inside the
  application, so `AccessDeniedHandler` never sees them and a catch-all `@ExceptionHandler` turns
  every 403 into a 500. `SecurityExceptionHandler` in common-lib handles this - do not remove it.

- **A circuit breaker's fallback sees every exception, 4xx included.** Catalog answering 404 for
  an unknown barcode came out of the fallback as "catalog unavailable" (503), and counted towards
  opening the breaker for every lane. An offline batch holding one delisted product became a
  permanent 503 that the terminal would retry forever. Translate `HttpClientErrorException` into
  the business error it is inside the fallback, and list it under the breaker's
  `ignoreExceptions`.

- **Adding an optional web dependency to a library turns its `@SpringBootTest`s into web
  contexts**, which then load every web handler on the classpath - common-lib's among them, which
  need security. messaging-lib's tests broke this way when the idempotency filter moved in. A
  library's non-web tests set `spring.main.web-application-type: none`.

- **An extension a service names explicitly must live in that service's schema.** A role's
  `search_path` is its own schema alone and `public` is revoked, so `pg_trgm` installed in `public`
  left `gin_trgm_ops` invisible: the tests passed (the extension landed in the test schema) and the
  container died on `operator class "gin_trgm_ops" does not exist`. The bootstrap script installs
  such an extension `WITH SCHEMA "<service>"`, and the migration keeps a guarded
  `DO $$ ... IF NOT EXISTS ... $$` block so a bare Testcontainers database works. An existing
  database needs `ALTER EXTENSION ... SET SCHEMA` by hand, like any other init-script change.
  Default operator classes (catalog's `btree_gist`) are found without this; a named one is not.

- **`forward-headers-strategy: framework` believes the leftmost `X-Forwarded-For`**, which the
  client writes itself: anyone could name their own address past the network allowlist, the login
  rate limit and the callback IP check. The gateway uses `native` - Tomcat's RemoteIpValve reads
  the header from the right, past proxies on the private networks - and `getRemoteAddr()` is then
  the address the edge saw. Never read `X-Forwarded-For` by hand for a security decision.
- **WireMock's Jetty accepts the JDK client's h2c upgrade and then resets streams**, which the
  circuit breaker reports as the service down (a 503 from the fallback) - intermittently, and
  first in a fresh context. Tomcat ignores the upgrade, so production is unaffected; the gateway's
  stub runs with `http2PlainDisabled(true)`.
- **Kafka listeners must not run on virtual threads (Java 21).** `spring.threads.virtual.enabled`
  puts listener containers on virtual threads, and the consumer coordinator works - and logs -
  inside `synchronized` methods. In a rebalance every listener blocks there on the log appender's
  lock and pins a carrier; with more listener threads than CPUs, all carriers are pinned and the
  lock holder is never scheduled. Inventory (15 listeners, 8 CPUs) froze outright: consumers,
  HTTP and health checks, near-zero CPU, no error. `KafkaListenerThreadsAutoConfiguration` in
  common-lib gives listeners platform threads; do not remove it. To diagnose a silent hang, a
  plain thread dump hides virtual threads - use `jcmd <pid> Thread.dump_to_file`, which the
  runtime image lacks, so install a JDK into the running container temporarily.
- **Infrastructure images follow the current stable line** (Postgres 18, Redis 8, Kafka 4; Confluent
  8 for Testcontainers' Kafka). Check Docker Hub before pinning. There is no local SMTP sink: mail
  goes through Gmail in development and AWS SES in production, and e2e runs use
  `MAIL_TRANSPORT=capture`.
- **MinIO no longer publishes Docker images** (`minio/minio` is gone from Docker Hub and quay).
  Development's S3-compatible store is RustFS (`rustfs/rustfs:1.0.0`, service `object-store`);
  catalog talks path-style S3 to it and creates its bucket at first use. Production uses S3 itself:
  `S3_ENDPOINT` empty, path style and bucket creation off, credentials from the environment or
  the instance's role - never invented.
- **Postgres 18 moved its data directory.** The volume mounts at `/var/lib/postgresql`, not
  `.../data` - the 18 entrypoint refuses the old path. A major-version bump never reuses the
  previous major's volume: dump and restore, or start fresh.
- **A full host disk looks like a Docker bug.** Docker Desktop keeps its VM disk on `/`, and every
  image rebuild leaves build cache behind; at 100% the daemon stops answering and Testcontainers
  reports "Could not find a valid Docker environment". Check `df -h /` first, and
  `docker builder prune` now and then.
- **The editor's problem count is not the build's.** VS Code's Java extension runs Eclipse's own
  compiler with null analysis against Spring's annotations; Maven does not. Its settings live in
  `.vscode/java-compiler.prefs` (unchecked-conversion null warnings off: they fire on every method
  reference into Spring 7's null-marked API; definite and potential null dereferences stay on).
  A pile of "cannot be resolved" errors in code that `mvn verify` compiles is a stale editor
  classpath, not a bug: run "Java: Clean Java Language Server Workspace". To check headlessly, run
  the extension's bundled `org.eclipse.jdt.core.compiler.batch` jar over a module with those prefs.
- **OpenPDF's `openpdf` 2.x artifact is the deprecated legacy API** (`com.lowagie.*`, every class
  deprecated). Use `openpdf-core-modern` (`org.openpdf.*`), same version.
- **A list tested against an empty table proves nothing about its mapping.** Four reads answered
  500 the first time real rows existed (`GET /products`, `/products/{id}`, `/adjustments`,
  `/supplier-invoices`): each response read a lazy association after the transaction had closed,
  and every test listed an empty table, so the mapping never ran. A read test creates the record
  first, then reads it back through the list and the single endpoint. `make api-smoke` sends every
  GET in the collection against seeded data and fails on a 5xx.
- **Numbers that restart per branch are unique per branch.** Receipt numbers count from R-000001 at
  each branch, but V1 made them unique across the business, so the second branch's first sale was
  refused - invisible while every test traded at one branch. Unique on `(branch_id, number)`, and a
  lookup by number either names the branch or searches only the caller's own.
- **The catch-all error handler keeps a framework exception's status.** Spring's
  `HttpMediaTypeNotSupportedException` (415), `HttpRequestMethodNotSupportedException` (405) and
  their kind implement `ErrorResponse`; turning them into 500 told clients the server broke when
  they had sent the wrong thing.
- **Every service's OpenAPI spec is routed at the gateway**: `/v3/api-docs/<service>`, listed in
  the gateway's Swagger UI and read by `make postman`. A new service needs its route there too.
- **An entity's `@Version` moves at the flush, not the setter.** An event that carries it as a
  revision (`expense-changed`) must `saveAndFlush` first, or an approval goes out with the same
  revision as the recording and the reader keeps the older state.
- **A new consumer group on an old topic starts from the beginning** with `auto-offset-reset:
  earliest`. Catalog's price reviews start at `latest` on purpose: replaying every past delivery
  would judge old costs against today's prices. Its IT sets `earliest`, because the listener may
  join after the test's first publish.
- **Consumers' retry-then-dead-letter policy is common-lib's**
  (`KafkaErrorHandlingAutoConfiguration`). Seven services had identical copies of it; do not add an
  eighth. A service with a different policy declares its own `CommonErrorHandler`.
- **ArchUnit's `layeredArchitecture()` fails on an empty layer.** A service with no `repository`
  package (reporting writes with `JdbcClient` from its services) must leave that layer out of its
  rules, not create an empty package to satisfy it.

## Frontend traps

- **Prettier is not this repo's formatter.** There is no config, and running it reflows whole files
  to its defaults - a 1,000-line diff for a 10-line change. Match the surrounding style by hand;
  `npm run lint` and `tsc` are the checks.

- **A dropdown filled from one page of results hides everything past it.** Add stock and the
  purchasing forms listed `suppliers?size=100`, and the 101st supplier could never be chosen -
  found only when test runs had created that many. Choices from a table that grows are searched
  (`SupplierSelect`, `ProductPicker`), keeping the current choice in the list.

- **The menu's Alt shortcuts are live on the till too**, so they must never take one of the
  checkout's own (C, E, F, L, P, R, V, X, Y). Expenses was given Alt+E, which the till uses to
  exchange notes: a manager at a till would have left a sale for the Expenses page. `nav.test.ts` now
  reads the checkout's `SHORTCUTS` rather than a hand-kept list; every letter is taken, so a new
  entry gets a digit (Alt+1).

- **A file download is a plain link to `/api/gateway/...`, without a `download` attribute.** The
  service answers `Content-Disposition: attachment`, which is enough; with the attribute Chrome
  cancelled the product CSV export before a request left the browser, while the reports' plain
  links worked.

- **Two refreshers revoke the session.** auth-service treats a refresh token spent twice as theft
  and revokes the whole family. The proxy is bundled apart from the route handlers, so each had
  its own single-flight map: a `<Link>` prefetch (which goes through the proxy) racing an API call
  spent the same token twice and signed the user out. Only `proxy.ts` refreshes; route handlers
  read the cookies it renewed on the request. Unit tests could not see this - the browser run did.
- **Behind a BFF every user has the BFF's address.** The gateway limits login and refresh per
  address (10 a minute), so without forwarding the browser's `X-Forwarded-For` a shift of cashiers
  signing in shares one bucket. `clientHeaders()` goes on every credential call, refresh included.
- **A function returned from `beforeEach` is run as that test's teardown.**
  `beforeEach(() => mock.mockReset())` returns the mock, so Vitest calls it after every test - and a
  mock that throws then fails a test whose code handled the error. Use braces.
- **`mockRejectedValue` builds its rejection at once.** Left pending across an `await` in the test,
  it counts as unhandled and fails the test. Use `mockImplementation(async () => { throw ... })`.
- **Next's route announcer is `role="alert"` too.** Select an alert by its text, not its role.
- **Leaving a page aborts its fetch.** An e2e step that navigates straight after a click can cancel
  the request the click started - wait for the outcome (the redirect, the message) first.
- **`localhost` in an Alpine container is `::1` first**, and Next listens on IPv4 `0.0.0.0`. Health
  checks use `127.0.0.1`.
- **shadcn now builds on Base UI**, not Radix: compose with the `render` prop, not `asChild`.
- **Serwist's `defaultCache` caches `/api/*` (NetworkFirst).** On the lane that would answer the
  connectivity ping from cache - an offline till that believes it is online - and keep people's
  data in the Cache Storage. `src/app/sw.ts` lists its own rules: API calls are `NetworkOnly`,
  only the lane's pages and static chunks are cached. Do not swap in `defaultCache`.
- **An approval token never reaches the browser.** `/api/lane/approved` obtains it and spends it on
  the one call its permission's allow-list names (`lib/lane/approvals.ts`). A new approvable action
  goes on that list and on auth-service's `pos.auth.approval.permissions`, or it cannot be approved.
- **The UI knows permissions by name, never the wildcard.** `/me` answers the expanded list, as the
  token carries it; before that, an administrator (whose role holds only `*`) got an empty menu,
  because `hasAny` compares names. Keep `/me` expanded rather than teaching the UI about `*`.
- **Every brand image comes from `scripts/brand/generate.py`**: the favicon, app icons, the header
  mark, the receipt raster (`lib/lane/receipt-logo.ts`) and the email and PDF logos in the
  notification and reporting resources. Change the mark or a colour there and re-run it; do not
  edit a generated file. Names live in `lib/brand.ts` and the services' `BRAND_NAME`.
- **The lane adds money in bigint, not `number`** (`lib/lane/decimal.ts`): four places for amounts,
  three for quantities, HALF_UP like the server. That needs `target` ES2020 or later in
  `tsconfig.json`; after changing it, delete `tsconfig.tsbuildinfo` or `tsc` keeps the old errors.

## Testing traps

- **Tests that share a broker must select their own message**, not "the first record on the
  topic" - which is whichever test got there first.
- **Tests that share a database must not share a table when one of them has a live scheduler.**
  A relay running in one Spring context will happily publish rows another test is asserting stay
  `PENDING`. Give such a context its own schema.
- **A raw-JDBC test that creates a table in `public` breaks every Flyway test after it** on the
  same container: "Found non-empty schema(s) public but no schema history table". Give such a test
  its own schema (`CREATE SCHEMA ...; setCurrentSchema(...)`).
- **Test data outlives the run, and lookups by suffix collide with it.** A scale label names its
  product by the SKU's last digits, and a clock-derived code eventually matched an older run's SKU:
  the label went ambiguous and the lane refused it. Choose such codes by checking none exist.

- **A class-level reset of a shared stub wipes what the next class's context needs.** The gateway's
  `@AfterAll` called WireMock's `resetAll()`, which removed the JWKS stub too. Locally `GatewayIT`
  ran first and passed; CI's filesystem order ran it after `ClientNetworkIT`, whose own
  `@TestPropertySource` context fetched fresh keys and got a 404, so every token was refused.
  Re-stub fixtures after a reset, and reproduce with `-Dfailsafe.runOrder=alphabetical`.

- **Asynchronous retries continue after an assertion passes.** A second test truncating tables
  while the first message is still being retried produces rows belonging to neither. Follow one
  message to its end in one test.

## Traps specific to this codebase

- **Costs are held without VAT; the business reclaims it.** A delivery or an order keyed in as
  invoiced has its VAT taken out by catalog's `/pricing/cost-check`, and purchasing keeps the typed
  cost, the rate and the input VAT beside the net one. Margin always compares a price without
  output VAT with a cost without input VAT.
- **A price review proposes; nothing reprices by itself.** A delivery that leaves an item below its
  target margin (product, else category, else parent) or below cost opens one per product and
  branch; a later delivery replaces it. Accepting changes the price the branch was charging - its
  list price when a list set it, the base price otherwise.
- **Expenses count once approved.** Above the approval limit (Settings) an expense waits for
  someone other than its recorder; head office's need `expense:head-office` (administrator only)
  and count once, in the business-wide profit and loss - never shared out over branches. A mistake
  is voided with a reason, never deleted.

- **A read model fed by an event keeps what the event said, not only patches rows that already
  exist.** Inventory refreshed product names on existing stock rows only, so a product's first
  delivery to a branch - made after catalog had announced it - arrived nameless. The details are
  kept per product (`product_details`) and new rows are filled from them.
- **Every way goods leave a branch must reach inventory.** A return to a supplier was recorded in
  purchasing and nowhere else: the goods left, the shelf count stayed. It now sends
  `supplier-return-sent`, and inventory takes them off the named batch first.
- **Shrinkage is stock taken off at its batches' cost**: write-offs and a stock take's shortfalls,
  both announced as `adjustment-posted` (a count as reason `STOCK_TAKE`) with a signed
  `valueAtCost`. Stock put back on has no delivery behind it, so it carries no value and is not
  shrinkage.

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
  rule, never assume a single format. **A product barcode must not start with a scale prefix**
  (20 and 21 here): catalog tries the scale rules first, so such a barcode is read as a label and
  the scan answers 404. The demo seed did exactly that; it now uses 29, also in GS1's in-store
  range. The lane decodes labels offline from the same rules (`GET /scale-barcode-rules`).
- The price on a purchase order is not what the goods cost. Freight and duty arrive with the
  delivery, so the **landed** cost is computed at receipt and it is that figure - not the invoice
  price - that inventory values stock at. An allocation must sum to the charge exactly; the
  remainder goes to the largest line.
- Three-way matching compares **per product**, never on totals: quantity against the receipt, price
  against the order. A supplier can bill the correct grand total while charging for goods that never
  arrived. A tolerance absorbs price drift only - a quantity or never-received finding is an
  exception at any size.
- Refunds are not negative sales: they reference the original sale, respect a returns policy window,
  and restock to a batch only when the goods are resaleable.
- **A tender is settled by whoever holds the value behind it.** Loyalty points live in
  customer-service, so it consumes `payment-requested` for the LOYALTY method and answers with
  `payment-authorized` / `payment-failed` itself; payment-service lists that method under
  `pos.payment.delegated-methods` and ignores it. Adding a tender settled elsewhere means adding it
  to that list, or every such sale is cancelled as METHOD_NOT_SUPPORTED.
- Points are held in dated lots and spent soonest-to-expire first, and `loyalty_lot_takes` records
  which lots a spend drew on. A reversal refills those lots rather than creating new points: a
  cancelled sale must leave a member exactly as they were, not a year better off.
- **Anything a Kafka listener or a scheduler triggers has no caller token to forward.** Work that
  needs to happen in another service from those paths goes by event, never by a call that would
  borrow the user's token. A basket's stock holds are the example that bit twice: inventory
  consumes them on `sale-completed.cartId` (with the FEFO deduction) and releases them on
  `sale-cancelled.cartId`. Both sales paths - an M-Pesa authorisation, a payment failure - run in
  a listener; a skipped call left the goods unsellable until the hold expired.
- M-Pesa takes whole shillings. The push is the amount rounded `HALF_UP` (`MpesaAmount`); paying
  exactly that settles the four-decimal amount in full, and the difference is recorded as rounding
  on the payment. Reporting 1052 as "part paid" against 1052.40 leaves a sale waiting for forty
  cents nobody can send.
- **Daraja's `404.001.03 Invalid Access Token` means two different things.** For a cached token
  it is an early expiry (Daraja does not use 401), and the client refreshes and retries once. For a
  token fetched moments ago it means the Daraja *app* is not subscribed to that API product -
  diagnose by calling Account Balance with the same token: accepted there, refused on STK Push, is
  a missing M-Pesa Express subscription in the portal, not a code or `.env` problem.
- `.env` values cannot carry an inline comment on an otherwise empty value: `KEY= # note` is an
  empty `KEY`. Put the note on its own line.
- **Sales' payment timeout is a backstop and must outlast the M-Pesa give-up.** Payment-service
  times a push out at 3 minutes (`give-up-after`) and that `TIMEOUT` releases the lane; sales'
  `payment-timeout` (4 minutes) only covers an answer that never comes. At 2 minutes it cancelled
  sales whose prompt was still live, found in the first real sandbox run.
- An STK Push that timed out is **never resent** - "no answer" includes "the prompt reached the
  phone". A payment that arrives after the intent was declared failed is recorded as a late payment
  and announced, never dropped: the customer paid.
- **A cash refund is paid from the drawer that is open now**, not the one the sale went through -
  that shift may have closed yesterday, and booking the refund there changes a till already
  counted. A return names the paying `tillSessionId` (required for CASH), and a void is refused
  once the sale's shift has closed: after close it is a return.
- **reporting-service mirrors the till's arithmetic** (`ShiftArithmetic`) so a Z-report can be
  reconciled against `shift-closed` figure by figure. Change how sales-service computes expected
  cash and the mirror must change in the same commit, or every Z-report starts disagreeing.
- **Reporting projections must stay order-independent.** Each event writes only its own rows and
  every figure is aggregated at read time, which is what lets a rebuild from `event_log` reproduce
  the incremental numbers exactly. A projection that updates a running total from another event's
  row breaks that the first time events arrive out of order.
- A cash sale's grand total carries four decimals; the change handed back is rounded to cents
  (`HALF_UP`) and that is the only rounding the drawer sees. The payments keep the 4dp figure.
- **A tracked drawer is a ledger of notes and coins** (`drawer_movements`), and change comes from
  what it holds via `ChangeMaker.exact` - a bounded search, because greedy fails a real drawer (60
  from one 50 and three 20s). Every new way cash enters or leaves a till must write its rows, or the
  calculator and the closing count drift from the money. Only whole shillings move; the cents of a
  four-decimal total are the drawer's `unaccounted`. The lane mirrors the same search
  (`lib/lane/cash.ts`) to preview change; the server's answer stands.
- **Change the cashier chooses is checked, never trusted**: it must equal the payable change and be
  covered by the drawer plus the customer's notes (`CashDrawerService.checkedChange`). An exchange
  (`EXCHANGE_IN` / `EXCHANGE_OUT`) must balance and touches no money counter.
- **Cash between a till and the intraday is approved by someone else.** Deposits, replenishments
  and the closing handover need `cash:intraday` and are refused to the cashier on the shift
  (`till.approver_is_cashier`); the lane always asks for the PIN. A shift closes only after the
  handover, and the amount the supervisor received is its count - `close` takes no count of its own.
- **Replenishments are float in and deposits are drops** in the money arithmetic, on purpose: the
  shift-closed figures, and reporting's `ShiftArithmetic`, are unchanged by the intraday ledger.
- **A device has one till identity per branch** (lane meta `registerId:<branchId>`), because a till's
  number belongs to its branch; the server refuses a device's id at a second branch.
