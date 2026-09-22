-- customer-service schema.
--
-- Who the members are, and what the shop owes them.
--
-- Three ideas shape it:
--
--   1. Points are a liability, so the ledger is the truth. A balance is never edited: every change
--      is a row saying why, and the balance after it. "Where did my points go" has to be
--      answerable months later.
--   2. Points are held in dated lots. Each accrual keeps its own remaining count and expiry, so
--      spending takes the soonest-to-expire first and expiry writes off exactly what lapsed -
--      the same shape as FEFO on the shelf.
--   3. A person can ask to be erased, and the ledger still has to balance. Erasure blanks the
--      personal details and keeps the transactions, which are a financial record, not personal
--      correspondence.

-- ---------------------------------------------------------------------------
-- membership_tiers: the ladder, as data rather than code.
--
-- Rolling spend, not lifetime: a member who stops shopping comes back down, which is the whole
-- point of a tier meaning something.
-- ---------------------------------------------------------------------------
CREATE TABLE membership_tiers (
    id                     UUID PRIMARY KEY,
    code                   VARCHAR(30)    NOT NULL,
    name                   VARCHAR(80)    NOT NULL,
    minimum_rolling_spend  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- Multiplies what a shilling earns, e.g. 1.500 for gold.
    points_multiplier      NUMERIC(6, 3)  NOT NULL DEFAULT 1,
    sort_order             INTEGER        NOT NULL DEFAULT 0,
    active                 BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             UUID,
    updated_by             UUID,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_membership_tiers_code UNIQUE (code),
    CONSTRAINT membership_tiers_spend_check CHECK (minimum_rolling_spend >= 0),
    CONSTRAINT membership_tiers_multiplier_check CHECK (points_multiplier > 0)
);

-- ---------------------------------------------------------------------------
-- customers: the member, and how a lane finds them.
--
-- phone is the identifier that matters at the till - it is what a customer says out loud - so it
-- is stored normalised (2547XXXXXXXX) and unique. A card number is optional; some shops issue one.
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id                UUID PRIMARY KEY,
    customer_number   VARCHAR(20)  NOT NULL,
    first_name        VARCHAR(80),
    last_name         VARCHAR(80),
    -- Kept alongside the parts so search does not have to concatenate on every row.
    display_name      VARCHAR(160) NOT NULL,
    phone             VARCHAR(20),
    alternate_phone   VARCHAR(20),
    email             VARCHAR(255),
    card_number       VARCHAR(40),
    date_of_birth     DATE,
    -- ACTIVE | INACTIVE | ERASED
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    notes             VARCHAR(1000),
    enrolled_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    erased_at         TIMESTAMPTZ,
    erased_by         UUID,
    erasure_reason    VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_customers_number UNIQUE (customer_number)
);

-- Unique only among the living: an erased customer's blanked details must not block a new member.
CREATE UNIQUE INDEX uq_customers_phone ON customers (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_customers_card ON customers (card_number) WHERE card_number IS NOT NULL;
CREATE UNIQUE INDEX uq_customers_email ON customers (lower(email)) WHERE email IS NOT NULL;
-- Name search at the lane, while someone waits: a trigram index finds "wanj" inside a name, not
-- only at its start.
--
-- Guarded rather than a plain CREATE EXTENSION: the service's database role has rights on its own
-- schema only, and creating an extension needs more than that. The bootstrap script installs it as
-- the superuser, so this block does nothing in a real deployment and covers a bare test database.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE EXTENSION pg_trgm;
    END IF;
END
$$;

CREATE INDEX idx_customers_name_trgm ON customers USING gin (lower(display_name) gin_trgm_ops);
CREATE INDEX idx_customers_status ON customers (status);

-- ---------------------------------------------------------------------------
-- customer_addresses: where deliveries and receipts go.
-- ---------------------------------------------------------------------------
CREATE TABLE customer_addresses (
    id           UUID PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    label        VARCHAR(40),
    line1        VARCHAR(160) NOT NULL,
    line2        VARCHAR(160),
    town         VARCHAR(80),
    county       VARCHAR(80),
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_customer_addresses_customer ON customer_addresses (customer_id);
-- One default, enforced rather than hoped for.
CREATE UNIQUE INDEX uq_customer_addresses_default ON customer_addresses (customer_id)
    WHERE is_default;

-- ---------------------------------------------------------------------------
-- customer_consents: append-only. What was agreed, when, and through what.
--
-- History rather than a flag, because "we had consent at the time" is the only defensible answer
-- to a complaint about a message sent last year. Current state is the newest row per channel.
-- ---------------------------------------------------------------------------
CREATE TABLE customer_consents (
    id           UUID PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    -- MARKETING_SMS | MARKETING_EMAIL | DATA_PROCESSING
    channel      VARCHAR(30)  NOT NULL,
    granted      BOOLEAN      NOT NULL,
    -- Where it came from: TILL | SELF_SERVICE | IMPORT | SUPPORT
    source       VARCHAR(30)  NOT NULL DEFAULT 'TILL',
    note         VARCHAR(500),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_customer_consents_customer ON customer_consents (customer_id, channel, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- loyalty_accounts: one per customer. The balance is a cached sum of the ledger.
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty_accounts (
    id               UUID PRIMARY KEY,
    customer_id      UUID           NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    tier_id          UUID           REFERENCES membership_tiers (id),
    points_balance   BIGINT         NOT NULL DEFAULT 0,
    lifetime_points  BIGINT         NOT NULL DEFAULT 0,
    -- Spend inside the tier window, as at last_evaluated_at.
    rolling_spend    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'KES',
    last_activity_at TIMESTAMPTZ,
    last_evaluated_at TIMESTAMPTZ,
    enrolled_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_loyalty_accounts_customer UNIQUE (customer_id),
    CONSTRAINT loyalty_accounts_balance_check CHECK (points_balance >= 0)
);

CREATE INDEX idx_loyalty_accounts_tier ON loyalty_accounts (tier_id);

-- ---------------------------------------------------------------------------
-- loyalty_transactions: the ledger, and the lots.
--
-- points is signed: an accrual adds, a redemption takes away. An ACCRUAL row is also a lot -
-- points_remaining and expires_at belong to it - and spending walks the lots by expiry, soonest
-- first, so points lapse in the order a member would expect.
--
-- sale_id is unique per accrual, so a redelivered sale-completed cannot pay twice; the same goes
-- for a redemption against a payment intent.
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty_transactions (
    id                 UUID PRIMARY KEY,
    account_id         UUID           NOT NULL REFERENCES loyalty_accounts (id) ON DELETE CASCADE,
    customer_id        UUID           NOT NULL,
    -- ACCRUAL | REDEMPTION | REVERSAL | CLAWBACK | EXPIRY | ADJUSTMENT
    type               VARCHAR(20)    NOT NULL,
    points             BIGINT         NOT NULL,
    balance_after      BIGINT         NOT NULL,
    -- Lot columns: set on rows that add points (ACCRUAL, REVERSAL, positive ADJUSTMENT).
    points_remaining   BIGINT         NOT NULL DEFAULT 0,
    expires_at         TIMESTAMPTZ,
    -- What the points were earned on or spent against.
    amount             NUMERIC(19, 4),
    currency           VARCHAR(3)     NOT NULL DEFAULT 'KES',
    sale_id            UUID,
    payment_intent_id  UUID,
    return_id          UUID,
    branch_id          UUID,
    reason             VARCHAR(500),
    actor_id           UUID,
    occurred_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT loyalty_transactions_remaining_check
        CHECK (points_remaining >= 0 AND points_remaining <= GREATEST(points, 0))
);

CREATE INDEX idx_loyalty_transactions_account ON loyalty_transactions (account_id, occurred_at DESC);
-- One accrual per sale, one redemption per payment intent: the ledger's own idempotency.
CREATE UNIQUE INDEX uq_loyalty_accrual_sale ON loyalty_transactions (sale_id)
    WHERE type = 'ACCRUAL' AND sale_id IS NOT NULL;
CREATE UNIQUE INDEX uq_loyalty_redemption_intent ON loyalty_transactions (payment_intent_id)
    WHERE type = 'REDEMPTION' AND payment_intent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_loyalty_clawback_return ON loyalty_transactions (return_id)
    WHERE type = 'CLAWBACK' AND return_id IS NOT NULL;
-- A voided sale takes its points back once: a claw-back with no return behind it.
CREATE UNIQUE INDEX uq_loyalty_void_clawback ON loyalty_transactions (sale_id)
    WHERE type = 'CLAWBACK' AND return_id IS NULL AND sale_id IS NOT NULL;
-- The lots a spend walks, and the sweep's queue.
CREATE INDEX idx_loyalty_lots ON loyalty_transactions (account_id, expires_at)
    WHERE points_remaining > 0;
CREATE INDEX idx_loyalty_transactions_sale ON loyalty_transactions (sale_id)
    WHERE sale_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- loyalty_lot_takes: which lots a spend came out of.
--
-- Without this a reversal cannot put points back where they were, and would have to invent a new
-- expiry - handing a member a fresh year on points that were weeks from lapsing, or taking months
-- off them. A cancelled sale should leave someone exactly as they were.
-- ---------------------------------------------------------------------------
CREATE TABLE loyalty_lot_takes (
    id              UUID PRIMARY KEY,
    transaction_id  UUID        NOT NULL REFERENCES loyalty_transactions (id) ON DELETE CASCADE,
    lot_id          UUID        NOT NULL REFERENCES loyalty_transactions (id) ON DELETE CASCADE,
    points          BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT loyalty_lot_takes_points_check CHECK (points > 0)
);

CREATE INDEX idx_loyalty_lot_takes_transaction ON loyalty_lot_takes (transaction_id);

-- ---------------------------------------------------------------------------
-- idempotency_records: the answer already given to a retried request.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(120)  NOT NULL,
    principal        VARCHAR(64)   NOT NULL,
    request_hash     VARCHAR(64)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    response_status  INTEGER,
    response_body    TEXT,
    content_type     VARCHAR(100),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_key UNIQUE (principal, idempotency_key)
);

-- ---------------------------------------------------------------------------
-- The ladder a shop starts with. Thresholds and multipliers are data: a manager changes them
-- without a deployment, and a changed threshold takes effect at the next evaluation.
-- ---------------------------------------------------------------------------
INSERT INTO membership_tiers (id, code, name, minimum_rolling_spend, points_multiplier, sort_order)
VALUES
    (gen_random_uuid(), 'BRONZE', 'Bronze',      0.0000, 1.000, 1),
    (gen_random_uuid(), 'SILVER', 'Silver',  50000.0000, 1.250, 2),
    (gen_random_uuid(), 'GOLD',   'Gold',   150000.0000, 1.500, 3);
