-- payment-service schema.
--
-- Where money is actually taken, and where it is proved to have been taken.
--
-- Three ideas shape it:
--
--   1. The provider is the authority, not the callback. An M-Pesa callback may arrive twice, arrive
--      before we have recorded the request it answers, or never arrive. Every provider transaction
--      is keyed by the provider's own id (CheckoutRequestID) so a repeat is recognisable, and a
--      status query settles what a callback did not.
--   2. Real money is never dropped. An authorisation that arrives after a sale was given up on is
--      still recorded as a payment, flagged late, and announced - sales routes it to
--      reconciliation. A payment row exists for every shilling received.
--   3. Nothing is overwritten. Status changes are logged in payment_events; a refund is its own
--      row against the payment it returns.

-- ---------------------------------------------------------------------------
-- payment_intents: one tender sales asked to be settled.
--
-- The id is sales' paymentIntentId, so a redelivered payment-requested finds the row it already
-- created instead of charging the customer again.
--
-- phone_number is held only until the STK Push is sent, then cleared; phone_masked stays for
-- support. A full MSISDN at rest for longer than the request needs it is a liability.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_intents (
    id                  UUID PRIMARY KEY,
    sale_id             UUID           NOT NULL,
    receipt_number      VARCHAR(40),
    branch_id           UUID           NOT NULL,
    register_id         UUID,
    cashier_id          UUID,
    -- CASH | MPESA | CARD | VOUCHER | ACCOUNT
    method              VARCHAR(20)    NOT NULL,
    amount              NUMERIC(19, 4) NOT NULL,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KES',
    phone_number        VARCHAR(20),
    phone_masked        VARCHAR(20),
    terminal_reference  VARCHAR(100),
    -- REQUESTED | DISPATCHING | AWAITING_CUSTOMER | AWAITING_CAPTURE | AUTHORIZED | FAILED
    status              VARCHAR(20)    NOT NULL DEFAULT 'REQUESTED',
    dispatch_attempts   INTEGER        NOT NULL DEFAULT 0,
    dispatched_at       TIMESTAMPTZ,
    amount_authorized   NUMERIC(19, 4),
    provider_reference  VARCHAR(100),
    approval_code       VARCHAR(50),
    failure_code        VARCHAR(50),
    failure_message     VARCHAR(500),
    -- True when money arrived after the intent had already been declared failed.
    late                BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Carried from the request so the authorisation, emitted later from a callback or a sweep
    -- with no request in scope, still traces back to the sale that asked for it.
    correlation_id      VARCHAR(64),
    causation_id        VARCHAR(64),
    requested_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT payment_intents_amount_check CHECK (amount > 0)
);

CREATE INDEX idx_payment_intents_sale ON payment_intents (sale_id);
CREATE INDEX idx_payment_intents_branch ON payment_intents (branch_id, requested_at DESC);
-- The dispatcher's queue.
CREATE INDEX idx_payment_intents_dispatch ON payment_intents (status, created_at)
    WHERE status IN ('REQUESTED', 'DISPATCHING');

-- ---------------------------------------------------------------------------
-- payments: money received. One row per authorised intent.
--
-- amount is what settles the sale; amount_charged is what the provider actually took. They differ
-- only by M-Pesa's whole-shilling rounding, which is recorded rather than absorbed so the
-- statement reconciles to the shilling and the sales ledger to the cent.
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                    UUID PRIMARY KEY,
    intent_id             UUID           NOT NULL REFERENCES payment_intents (id),
    sale_id               UUID           NOT NULL,
    branch_id             UUID           NOT NULL,
    method                VARCHAR(20)    NOT NULL,
    amount                NUMERIC(19, 4) NOT NULL,
    amount_charged        NUMERIC(19, 4) NOT NULL,
    rounding_difference   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    amount_refunded       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency              VARCHAR(3)     NOT NULL DEFAULT 'KES',
    provider_reference    VARCHAR(100),
    approval_code         VARCHAR(50),
    -- The M-Pesa receipt (e.g. QKR12XYZ9). Null when the payment was recovered by a status query,
    -- which does not return it; reconciliation fills it in from the statement.
    mpesa_receipt_number  VARCHAR(30),
    late                  BOOLEAN        NOT NULL DEFAULT FALSE,
    received_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_intent UNIQUE (intent_id),
    CONSTRAINT payments_refund_check CHECK (amount_refunded >= 0 AND amount_refunded <= amount)
);

CREATE INDEX idx_payments_sale ON payments (sale_id);
CREATE INDEX idx_payments_received ON payments (method, received_at);
CREATE UNIQUE INDEX uq_payments_mpesa_receipt ON payments (mpesa_receipt_number)
    WHERE mpesa_receipt_number IS NOT NULL;

-- ---------------------------------------------------------------------------
-- payment_events: what happened to an intent, in order. Append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_events (
    id           UUID PRIMARY KEY,
    intent_id    UUID          REFERENCES payment_intents (id),
    branch_id    UUID,
    type         VARCHAR(40)   NOT NULL,
    detail       VARCHAR(1000),
    occurred_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_events_intent ON payment_events (intent_id, occurred_at);

-- ---------------------------------------------------------------------------
-- mpesa_transactions: one STK Push, keyed by Daraja's CheckoutRequestID.
--
-- intent_id is nullable on purpose: a callback can beat the push's own response to our database,
-- and it is parked here until the push is recorded rather than thrown away.
-- ---------------------------------------------------------------------------
CREATE TABLE mpesa_transactions (
    id                    UUID PRIMARY KEY,
    intent_id             UUID           REFERENCES payment_intents (id),
    branch_id             UUID,
    merchant_request_id   VARCHAR(100),
    checkout_request_id   VARCHAR(100)   NOT NULL,
    phone_masked          VARCHAR(20),
    amount_requested      NUMERIC(19, 4),
    -- PENDING | SUCCEEDED | FAILED
    status                VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    result_code           INTEGER,
    result_desc           VARCHAR(500),
    mpesa_receipt_number  VARCHAR(30),
    amount_paid           NUMERIC(19, 4),
    transaction_date      TIMESTAMPTZ,
    -- CALLBACK | QUERY: which source settled it.
    settled_by            VARCHAR(20),
    callback_received_at  TIMESTAMPTZ,
    query_attempts        INTEGER        NOT NULL DEFAULT 0,
    last_queried_at       TIMESTAMPTZ,
    -- Null once the sweep has given up; a callback can still settle it after that.
    next_query_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_mpesa_checkout_request UNIQUE (checkout_request_id)
);

CREATE INDEX idx_mpesa_transactions_query ON mpesa_transactions (next_query_at)
    WHERE status = 'PENDING' AND next_query_at IS NOT NULL;
CREATE INDEX idx_mpesa_transactions_intent ON mpesa_transactions (intent_id);

-- ---------------------------------------------------------------------------
-- refunds: money going back through the provider it came in by.
--
-- One row per (return, payment): a redelivered return-processed cannot refund twice. Cash
-- refunds never appear here - they leave the drawer at the till.
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                          UUID PRIMARY KEY,
    payment_id                  UUID           NOT NULL REFERENCES payments (id),
    intent_id                   UUID           NOT NULL,
    sale_id                     UUID           NOT NULL,
    return_id                   UUID           NOT NULL,
    branch_id                   UUID           NOT NULL,
    method                      VARCHAR(20)    NOT NULL,
    amount                      NUMERIC(19, 4) NOT NULL,
    currency                    VARCHAR(3)     NOT NULL DEFAULT 'KES',
    -- PENDING_DISPATCH | PROCESSING | AWAITING_CAPTURE | REQUIRES_ACTION | COMPLETED
    status                      VARCHAR(20)    NOT NULL,
    -- Why a person is needed, e.g. MPESA_PARTIAL_REVERSAL.
    action_reason               VARCHAR(50),
    detail                      VARCHAR(500),
    provider_reference          VARCHAR(100),
    originator_conversation_id  VARCHAR(100),
    conversation_id             VARCHAR(100),
    -- PROVIDER, or how a person settled it (CASH, BANK_TRANSFER, ...).
    settled_via                 VARCHAR(30),
    settled_by                  UUID,
    settlement_note             VARCHAR(500),
    requested_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_refunds_return_payment UNIQUE (return_id, payment_id),
    CONSTRAINT refunds_amount_check CHECK (amount > 0)
);

CREATE INDEX idx_refunds_branch_status ON refunds (branch_id, status, requested_at DESC);
CREATE UNIQUE INDEX uq_refunds_originator ON refunds (originator_conversation_id)
    WHERE originator_conversation_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- reconciliation_runs / reconciliation_items: the M-Pesa statement against what we recorded.
--
-- Organisation-wide rather than per branch: one shortcode takes every branch's money.
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                  UUID PRIMARY KEY,
    statement_date      DATE           NOT NULL,
    source_filename     VARCHAR(255),
    statement_lines     INTEGER        NOT NULL DEFAULT 0,
    matched             INTEGER        NOT NULL DEFAULT 0,
    amount_mismatches   INTEGER        NOT NULL DEFAULT 0,
    statement_only      INTEGER        NOT NULL DEFAULT 0,
    recorded_only       INTEGER        NOT NULL DEFAULT 0,
    statement_total     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    recorded_total      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    variance_total      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    run_by              UUID,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_reconciliation_runs_date ON reconciliation_runs (statement_date DESC);

CREATE TABLE reconciliation_items (
    id                    UUID PRIMARY KEY,
    run_id                UUID           NOT NULL REFERENCES reconciliation_runs (id),
    -- MATCHED | MATCHED_BY_AMOUNT | AMOUNT_MISMATCH | STATEMENT_ONLY | RECORDED_ONLY
    kind                  VARCHAR(30)    NOT NULL,
    mpesa_receipt_number  VARCHAR(30),
    payment_id            UUID,
    statement_amount      NUMERIC(19, 4),
    recorded_amount       NUMERIC(19, 4),
    difference            NUMERIC(19, 4),
    note                  VARCHAR(500),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_reconciliation_items_run ON reconciliation_items (run_id, kind);

-- ---------------------------------------------------------------------------
-- idempotency_records: the answer already given to a retried request (Idempotency-Key).
-- The shared filter in messaging-lib reads and writes it; each service carries the table.
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
