-- Tills, the drawer by note and coin, the branch's intraday cash, and cash limits.

-- ---------------------------------------------------------------------------
-- registers: a till. The id is the device's own register id; the number is what people call it.
-- Numbered per branch in order of first use (Till 1, Till 2, ...), renamable by a manager.
-- ---------------------------------------------------------------------------
CREATE TABLE registers (
    id          UUID PRIMARY KEY,
    branch_id   UUID         NOT NULL,
    number      INTEGER      NOT NULL,
    name        VARCHAR(60),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_registers_branch_number UNIQUE (branch_id, number),
    CONSTRAINT registers_number_check CHECK (number > 0)
);

-- Every device that has already opened a shift gets its number, in order of first use. A device
-- that moved between branches keeps the branch it last worked at.
INSERT INTO registers (id, branch_id, number)
SELECT register_id,
       branch_id,
       row_number() OVER (PARTITION BY branch_id ORDER BY first_opened, register_id)
FROM (
    SELECT DISTINCT ON (register_id) register_id, branch_id, first_opened
    FROM (
        SELECT register_id, branch_id, min(opened_at) AS first_opened, max(opened_at) AS last_opened
        FROM till_sessions
        GROUP BY register_id, branch_id
    ) per_branch
    ORDER BY register_id, last_opened DESC
) latest;

-- ---------------------------------------------------------------------------
-- The drawer by denomination. A shift opened with its float counted note by note tracks every
-- movement this way; one opened with a total alone (older clients) keeps to money totals.
-- ---------------------------------------------------------------------------
ALTER TABLE till_sessions ADD COLUMN tracks_denominations BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE drawer_movements (
    id               UUID PRIMARY KEY,
    till_session_id  UUID           NOT NULL REFERENCES till_sessions (id),
    branch_id        UUID           NOT NULL,
    -- OPENING_FLOAT | FLOAT_IN | REPLENISH | SALE_IN | SALE_CHANGE | VOID_OUT | REFUND_OUT | DEPOSIT
    kind             VARCHAR(20)    NOT NULL,
    source_id        UUID,
    denomination     NUMERIC(19, 4) NOT NULL,
    -- Signed: into the drawer positive, out of it negative.
    count            INTEGER        NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT drawer_movements_count_check CHECK (count <> 0),
    CONSTRAINT drawer_movements_denomination_check CHECK (denomination > 0)
);

CREATE INDEX idx_drawer_movements_session ON drawer_movements (till_session_id, denomination);

-- The count at close, note by note, beside what the ledger said the drawer held.
CREATE TABLE till_session_counts (
    id               UUID PRIMARY KEY,
    till_session_id  UUID           NOT NULL REFERENCES till_sessions (id),
    branch_id        UUID           NOT NULL,
    denomination     NUMERIC(19, 4) NOT NULL,
    counted          INTEGER        NOT NULL,
    expected         INTEGER        NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_till_session_counts UNIQUE (till_session_id, denomination),
    CONSTRAINT till_session_counts_counted_check CHECK (counted >= 0)
);

-- The notes a customer handed over and the change given back, captured at tender and entered in
-- the drawer when the sale completes - a sale cancelled before then hands the notes back.
CREATE TABLE sale_cash_denominations (
    id            UUID PRIMARY KEY,
    sale_id       UUID           NOT NULL REFERENCES sales (id),
    branch_id     UUID           NOT NULL,
    denomination  NUMERIC(19, 4) NOT NULL,
    received      INTEGER        NOT NULL DEFAULT 0,
    change_given  INTEGER        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_sale_cash_denominations UNIQUE (sale_id, denomination),
    CONSTRAINT sale_cash_denominations_counts_check CHECK (received >= 0 AND change_given >= 0)
);

-- ---------------------------------------------------------------------------
-- The branch's intraday cash, held by the supervisor: tills deposit into it when they hold too
-- much, and are replenished from it when they run short of change. By denomination, like a drawer.
-- ---------------------------------------------------------------------------
CREATE TABLE intraday_movements (
    id               UUID PRIMARY KEY,
    branch_id        UUID           NOT NULL,
    -- TOP_UP (brought in) | BANKED (taken to the bank) | FROM_TILL (a deposit) | TO_TILL (a replenishment)
    kind             VARCHAR(20)    NOT NULL,
    till_session_id  UUID,
    denomination     NUMERIC(19, 4) NOT NULL,
    count            INTEGER        NOT NULL,
    reason           VARCHAR(500),
    occurred_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT intraday_movements_count_check CHECK (count <> 0),
    CONSTRAINT intraday_movements_denomination_check CHECK (denomination > 0)
);

CREATE INDEX idx_intraday_movements_branch ON intraday_movements (branch_id, occurred_at);

-- ---------------------------------------------------------------------------
-- cash_limits: how much cash a till may hold. A branch default (user_id NULL) and per-person
-- overrides. Past the limit the lane asks for a deposit; past the ceiling it takes no more cash.
-- ---------------------------------------------------------------------------
CREATE TABLE cash_limits (
    id              UUID PRIMARY KEY,
    branch_id       UUID           NOT NULL,
    user_id         UUID,
    limit_amount    NUMERIC(19, 4) NOT NULL,
    ceiling_amount  NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT cash_limits_amounts_check CHECK (limit_amount > 0 AND ceiling_amount >= limit_amount)
);

CREATE UNIQUE INDEX uq_cash_limits_branch_default ON cash_limits (branch_id) WHERE user_id IS NULL;
CREATE UNIQUE INDEX uq_cash_limits_branch_user ON cash_limits (branch_id, user_id) WHERE user_id IS NOT NULL;
