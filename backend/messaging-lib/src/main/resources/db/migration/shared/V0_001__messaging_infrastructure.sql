-- Shared messaging infrastructure: the transactional outbox and the idempotency ledger.
--
-- Applied into each service's own schema. Services include this location alongside their own:
--   spring.flyway.locations=classpath:db/migration,classpath:db/migration/shared
--
-- Shared migrations occupy version range V0_*; service migrations start at V1. Flyway requires
-- versions to be unique across all locations, so keeping the ranges apart avoids collisions as
-- services and this library evolve independently.

-- ---------------------------------------------------------------------------
-- outbox: rows written in the same transaction as the state change they describe.
-- A relay publishes them to Kafka afterwards. This is what makes "the database changed
-- but the event was lost" impossible.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox (
    id              UUID PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    -- Used as the Kafka message key, so all events for one aggregate land on one partition
    -- and therefore keep their relative order.
    aggregate_id    UUID         NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT outbox_status_check CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Partial index: the relay only ever asks for due, pending rows, and published rows are the
-- overwhelming majority over time. Keeping them out of the index keeps it small and hot.
CREATE INDEX IF NOT EXISTS idx_outbox_due
    ON outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Supports the alert on permanently failed publications.
CREATE INDEX IF NOT EXISTS idx_outbox_failed
    ON outbox (created_at)
    WHERE status = 'FAILED';

-- ---------------------------------------------------------------------------
-- processed_event: the idempotency ledger.
--
-- Kafka delivers at least once, so a consumer WILL see the same event twice: after a rebalance,
-- after a retry, after a redeployment mid-batch. Deducting stock or accruing loyalty points
-- twice is a real financial error, so every consumer records what it has handled.
--
-- Keyed by (event_id, consumer) rather than event_id alone: several consumers within one service
-- legitimately handle the same event, and each must be allowed to do so exactly once.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processed_event (
    event_id     VARCHAR(64)  NOT NULL,
    consumer     VARCHAR(150) NOT NULL,
    event_type   VARCHAR(255),
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- Supports pruning old ledger entries once they are far outside the topic retention window.
CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at ON processed_event (processed_at);
