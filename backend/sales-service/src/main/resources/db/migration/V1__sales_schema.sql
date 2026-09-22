-- sales-service schema.
--
-- The till. Everything here is written from the point of view of a lane that must keep working
-- while the network does not, and of an auditor asking questions months later.
--
-- Three ideas shape it:
--
--   1. Nothing is ever deleted or overwritten. A void is a new row and a status change; a refund
--      references the sale it reverses. "What did we sell at 14:32 and who changed it" has to be
--      answerable, and an UPDATE that erases history cannot answer it.
--   2. Totals are the server's, and they are snapshots. Every sale line keeps the price, tax class,
--      tax rate and unit cost that applied at the moment of sale, because a reprint two months
--      later must show what the customer actually paid, not what the product costs now.
--   3. A sale may be created by a terminal that was offline. Ids are client-generatable (UUIDv7)
--      and client_sale_id is unique, so replaying a queued batch cannot duplicate a sale.

-- ---------------------------------------------------------------------------
-- till_sessions: one cashier, one register, one shift.
--
-- The money reconciliation lives here. expected_cash is derived at close from the float, the cash
-- taken, the cash refunded and anything dropped to the safe; variance is the difference against
-- what was physically counted. Both are stored rather than recomputed on read, because they are a
-- statement about a moment that has passed - recomputing later against changed data would quietly
-- rewrite history.
-- ---------------------------------------------------------------------------
CREATE TABLE till_sessions (
    id                UUID PRIMARY KEY,
    branch_id         UUID           NOT NULL,
    register_id       UUID           NOT NULL,
    cashier_id        UUID           NOT NULL,
    -- OPEN | CLOSING | CLOSED. CLOSING blocks new sales while the count is keyed in.
    status            VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    opened_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    closed_at         TIMESTAMPTZ,
    closed_by         UUID,
    opening_float     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    cash_sales        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    cash_refunds      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    cash_drops        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    non_cash_sales    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    expected_cash     NUMERIC(19, 4),
    counted_cash      NUMERIC(19, 4),
    variance          NUMERIC(19, 4),
    sale_count        INTEGER        NOT NULL DEFAULT 0,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'KES',
    notes             VARCHAR(1000),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT till_sessions_float_check CHECK (opening_float >= 0)
);

-- One open session per register: two cashiers sharing a drawer makes the count meaningless.
CREATE UNIQUE INDEX uq_till_sessions_open_register
    ON till_sessions (register_id)
    WHERE status <> 'CLOSED';

CREATE INDEX idx_till_sessions_branch ON till_sessions (branch_id, opened_at DESC);
CREATE INDEX idx_till_sessions_cashier ON till_sessions (cashier_id, opened_at DESC);

-- ---------------------------------------------------------------------------
-- cash_movements: float in, drops out, and why.
--
-- A drop to the safe mid-shift is the difference between a drawer that reconciles and one that is
-- thousands over. Recorded as its own rows so the arithmetic at close can be shown, not asserted.
-- ---------------------------------------------------------------------------
CREATE TABLE cash_movements (
    id               UUID PRIMARY KEY,
    till_session_id  UUID           NOT NULL REFERENCES till_sessions (id) ON DELETE CASCADE,
    branch_id        UUID           NOT NULL,
    -- FLOAT_IN | DROP | PAY_OUT | CORRECTION
    type             VARCHAR(20)    NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'KES',
    reason           VARCHAR(500),
    reference        VARCHAR(100),
    occurred_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT cash_movements_amount_check CHECK (amount > 0)
);

CREATE INDEX idx_cash_movements_session ON cash_movements (till_session_id, occurred_at);

-- ---------------------------------------------------------------------------
-- carts: a basket in progress.
--
-- Separate from sales because most carts never become sales, and a cart is mutable by nature while
-- a sale must not be. A suspended cart is how a cashier parks a customer who forgot something and
-- serves the queue meanwhile.
-- ---------------------------------------------------------------------------
CREATE TABLE carts (
    id               UUID PRIMARY KEY,
    till_session_id  UUID           REFERENCES till_sessions (id),
    branch_id        UUID           NOT NULL,
    register_id      UUID,
    cashier_id       UUID,
    customer_id      UUID,
    -- OPEN | SUSPENDED | CHECKED_OUT | ABANDONED
    status           VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    -- Printed on the parked ticket so the customer can be recalled by a short code.
    suspend_code     VARCHAR(12),
    suspended_at     TIMESTAMPTZ,
    is_member        BOOLEAN        NOT NULL DEFAULT FALSE,
    net_total        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_total        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    discount_total   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    grand_total      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_carts_session ON carts (till_session_id, status);
CREATE INDEX idx_carts_branch_status ON carts (branch_id, status);
-- A suspend code only has to be unique among the carts currently parked at a branch.
CREATE UNIQUE INDEX uq_carts_suspend_code
    ON carts (branch_id, suspend_code)
    WHERE status = 'SUSPENDED';

-- ---------------------------------------------------------------------------
-- cart_lines
--
-- Every priced figure on a line comes from catalog's pricing engine and is stored as returned.
-- sales-service never recomputes tax: two implementations of the same rule drift, and the receipt
-- would then disagree with the price the shelf edge promised.
-- ---------------------------------------------------------------------------
CREATE TABLE cart_lines (
    id                  UUID PRIMARY KEY,
    cart_id             UUID           NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    line_number         INTEGER        NOT NULL,
    product_id          UUID           NOT NULL,
    sku                 VARCHAR(50),
    product_name        VARCHAR(200),
    barcode             VARCHAR(50),
    -- Fractional for weighed goods.
    quantity            NUMERIC(19, 3) NOT NULL,
    unit_price          NUMERIC(19, 4) NOT NULL,
    -- Where the price came from: BASE | PRICE_LIST | OVERRIDE
    price_source        VARCHAR(20)    NOT NULL DEFAULT 'BASE',
    price_list_id       UUID,
    tax_inclusive       BOOLEAN        NOT NULL DEFAULT TRUE,
    tax_class_code      VARCHAR(30),
    tax_rate            NUMERIC(9, 6)  NOT NULL DEFAULT 0,
    subtotal            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    discount_total      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    net_amount          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_amount          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- The promotions that fired, as returned by catalog, for the receipt to print.
    applied_discounts   JSONB,
    -- Set only when a supervisor overrode the price; both are required together.
    override_reason     VARCHAR(500),
    overridden_by       UUID,
    original_unit_price NUMERIC(19, 4),
    voided              BOOLEAN        NOT NULL DEFAULT FALSE,
    void_reason         VARCHAR(500),
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_cart_lines_number UNIQUE (cart_id, line_number),
    CONSTRAINT cart_lines_quantity_check CHECK (quantity > 0),
    -- An override without a reason is an unexplained discount.
    CONSTRAINT cart_lines_override_check
        CHECK ((override_reason IS NULL AND overridden_by IS NULL)
               OR (override_reason IS NOT NULL AND overridden_by IS NOT NULL))
);

CREATE INDEX idx_cart_lines_cart ON cart_lines (cart_id, line_number);

-- ---------------------------------------------------------------------------
-- sales
--
-- Immutable once PAID except for status. client_sale_id is the offline terminal's own id and is
-- unique, which is what makes replaying a queued batch safe.
--
-- price_variance_flagged marks a sale whose client-side totals disagreed with the server's
-- revalidation. The sale is still accepted - the customer has gone, and refusing it would lose the
-- takings - but it is flagged rather than quietly trusted.
-- ---------------------------------------------------------------------------
CREATE TABLE sales (
    id                      UUID PRIMARY KEY,
    -- Assigned only once the sale is paid, from a gapless per-branch sequence.
    receipt_number          VARCHAR(30),
    client_sale_id          UUID,
    cart_id                 UUID           REFERENCES carts (id),
    till_session_id         UUID           REFERENCES till_sessions (id),
    branch_id               UUID           NOT NULL,
    register_id             UUID,
    cashier_id              UUID           NOT NULL,
    customer_id             UUID,
    -- PENDING | AWAITING_PAYMENT | PAID | CANCELLED | VOIDED
    status                  VARCHAR(25)    NOT NULL DEFAULT 'PENDING',
    -- ONLINE | OFFLINE_SYNC. Kept because an offline sale carries different guarantees.
    origin                  VARCHAR(20)    NOT NULL DEFAULT 'ONLINE',
    is_member               BOOLEAN        NOT NULL DEFAULT FALSE,
    net_total               NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_total               NUMERIC(19, 4) NOT NULL DEFAULT 0,
    discount_total          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    grand_total             NUMERIC(19, 4) NOT NULL DEFAULT 0,
    amount_tendered         NUMERIC(19, 4),
    change_given            NUMERIC(19, 4),
    currency                VARCHAR(3)     NOT NULL DEFAULT 'KES',
    -- What the terminal thought the total was, when it told us.
    client_grand_total      NUMERIC(19, 4),
    price_variance_flagged  BOOLEAN        NOT NULL DEFAULT FALSE,
    price_variance_amount   NUMERIC(19, 4),
    occurred_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    cancellation_reason     VARCHAR(500),
    voided_at               TIMESTAMPTZ,
    voided_by               UUID,
    void_approved_by        UUID,
    void_reason             VARCHAR(500),
    -- Set when stock was reserved for this sale, so it can be released on compensation.
    reservation_reference   UUID,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    version                 BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_sales_receipt_number UNIQUE (receipt_number),
    CONSTRAINT uq_sales_client_id UNIQUE (client_sale_id),
    CONSTRAINT sales_totals_check CHECK (grand_total >= 0)
);

CREATE INDEX idx_sales_branch_occurred ON sales (branch_id, occurred_at DESC);
CREATE INDEX idx_sales_session ON sales (till_session_id);
CREATE INDEX idx_sales_customer ON sales (customer_id, occurred_at DESC);
CREATE INDEX idx_sales_status ON sales (status);
-- The saga's recovery query: sales stuck waiting for a payment that may never come.
CREATE INDEX idx_sales_awaiting
    ON sales (occurred_at)
    WHERE status = 'AWAITING_PAYMENT';

-- ---------------------------------------------------------------------------
-- sale_lines
--
-- A snapshot, deliberately duplicating cart_lines. unit_cost is captured for margin reporting: the
-- cost of the goods at the moment they were sold, which changes with every delivery.
-- ---------------------------------------------------------------------------
CREATE TABLE sale_lines (
    id                  UUID PRIMARY KEY,
    sale_id             UUID           NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    line_number         INTEGER        NOT NULL,
    product_id          UUID           NOT NULL,
    sku                 VARCHAR(50),
    product_name        VARCHAR(200),
    barcode             VARCHAR(50),
    quantity            NUMERIC(19, 3) NOT NULL,
    quantity_returned   NUMERIC(19, 3) NOT NULL DEFAULT 0,
    unit_price          NUMERIC(19, 4) NOT NULL,
    unit_cost           NUMERIC(19, 4),
    price_source        VARCHAR(20)    NOT NULL DEFAULT 'BASE',
    tax_inclusive       BOOLEAN        NOT NULL DEFAULT TRUE,
    tax_class_code      VARCHAR(30),
    tax_rate            NUMERIC(9, 6)  NOT NULL DEFAULT 0,
    subtotal            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    discount_total      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    net_amount          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_amount          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    applied_discounts   JSONB,
    override_reason     VARCHAR(500),
    overridden_by       UUID,
    original_unit_price NUMERIC(19, 4),
    batch_number        VARCHAR(100),
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_sale_lines_number UNIQUE (sale_id, line_number),
    CONSTRAINT sale_lines_quantity_check CHECK (quantity > 0),
    CONSTRAINT sale_lines_returned_check CHECK (quantity_returned >= 0 AND quantity_returned <= quantity)
);

CREATE INDEX idx_sale_lines_sale ON sale_lines (sale_id, line_number);
CREATE INDEX idx_sale_lines_product ON sale_lines (product_id);

-- ---------------------------------------------------------------------------
-- sale_payments: one row per tender.
--
-- A basket split across cash and M-Pesa is two rows, each authorised on its own. Card rows carry a
-- terminal reference and an approval code and nothing else - card data is never stored.
-- ---------------------------------------------------------------------------
CREATE TABLE sale_payments (
    id                  UUID PRIMARY KEY,
    sale_id             UUID           NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    payment_intent_id   UUID           NOT NULL,
    -- CASH | MPESA | CARD | VOUCHER | ACCOUNT
    method              VARCHAR(20)    NOT NULL,
    -- PENDING | AUTHORIZED | FAILED | REFUNDED
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    amount              NUMERIC(19, 4) NOT NULL,
    amount_authorized   NUMERIC(19, 4),
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KES',
    -- The M-Pesa receipt or card approval code: what a dispute is settled with.
    provider_reference  VARCHAR(100),
    approval_code       VARCHAR(50),
    terminal_reference  VARCHAR(100),
    -- Masked before it ever reaches this column; never the full number.
    phone_number_masked VARCHAR(30),
    failure_reason      VARCHAR(100),
    failure_message     VARCHAR(500),
    requested_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_sale_payments_intent UNIQUE (payment_intent_id),
    CONSTRAINT sale_payments_amount_check CHECK (amount > 0)
);

CREATE INDEX idx_sale_payments_sale ON sale_payments (sale_id);
CREATE INDEX idx_sale_payments_status ON sale_payments (status);

-- ---------------------------------------------------------------------------
-- returns
--
-- A refund is a new document referencing the sale it reverses, never an edit of it. The policy
-- window is evaluated and its outcome recorded, because an override of that window is a decision
-- someone made and must be attributable.
-- ---------------------------------------------------------------------------
CREATE TABLE returns (
    id                    UUID PRIMARY KEY,
    return_number         VARCHAR(30)    NOT NULL,
    original_sale_id      UUID           NOT NULL REFERENCES sales (id),
    till_session_id       UUID           REFERENCES till_sessions (id),
    branch_id             UUID           NOT NULL,
    register_id           UUID,
    cashier_id            UUID           NOT NULL,
    customer_id           UUID,
    -- PENDING | COMPLETED | CANCELLED
    status                VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    reason_code           VARCHAR(30)    NOT NULL,
    notes                 VARCHAR(1000),
    refund_method         VARCHAR(20)    NOT NULL DEFAULT 'CASH',
    refund_total          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_refunded          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency              VARCHAR(3)     NOT NULL DEFAULT 'KES',
    -- Days between the sale and the return, and whether that broke the policy.
    days_since_sale       INTEGER,
    outside_policy_window BOOLEAN        NOT NULL DEFAULT FALSE,
    policy_override_by    UUID,
    policy_override_reason VARCHAR(500),
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_returns_number UNIQUE (return_number),
    CONSTRAINT returns_override_check
        CHECK (NOT outside_policy_window
               OR policy_override_by IS NULL
               OR policy_override_reason IS NOT NULL)
);

CREATE INDEX idx_returns_sale ON returns (original_sale_id);
CREATE INDEX idx_returns_branch ON returns (branch_id, created_at DESC);

CREATE TABLE return_lines (
    id                UUID PRIMARY KEY,
    return_id         UUID           NOT NULL REFERENCES returns (id) ON DELETE CASCADE,
    sale_line_id      UUID           NOT NULL REFERENCES sale_lines (id),
    line_number       INTEGER        NOT NULL,
    product_id        UUID           NOT NULL,
    sku               VARCHAR(50),
    product_name      VARCHAR(200),
    quantity          NUMERIC(19, 3) NOT NULL,
    unit_price        NUMERIC(19, 4) NOT NULL,
    tax_class_code    VARCHAR(30),
    tax_rate          NUMERIC(9, 6)  NOT NULL DEFAULT 0,
    net_amount        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    refund_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- Drives whether inventory puts it back on the shelf or writes it off.
    resaleable        BOOLEAN        NOT NULL DEFAULT TRUE,
    batch_number      VARCHAR(100),
    condition_note    VARCHAR(500),
    currency          VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_return_lines_number UNIQUE (return_id, line_number),
    CONSTRAINT return_lines_quantity_check CHECK (quantity > 0)
);

CREATE INDEX idx_return_lines_return ON return_lines (return_id);

-- ---------------------------------------------------------------------------
-- receipt_sequences: gapless numbering, per branch.
--
-- A Postgres sequence is the wrong tool here: it is designed to leave gaps when a transaction rolls
-- back, and "receipt 4,412 does not exist" is exactly the question a tax audit asks. A counter row
-- taken with SELECT ... FOR UPDATE serialises the assignment and rolls back with the sale, so the
-- numbers are contiguous. The lock is held for microseconds and only against other sales at the
-- same branch.
-- ---------------------------------------------------------------------------
CREATE TABLE receipt_sequences (
    branch_id    UUID PRIMARY KEY,
    prefix       VARCHAR(10)  NOT NULL DEFAULT 'R',
    next_number  BIGINT       NOT NULL DEFAULT 1,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT receipt_sequences_next_check CHECK (next_number > 0)
);

-- ---------------------------------------------------------------------------
-- receipts
--
-- The rendered document, kept so a reprint is byte-identical to what the customer was handed. The
-- tax breakdown is stored per class, which is what a VAT return is built from.
-- ---------------------------------------------------------------------------
CREATE TABLE receipts (
    id                UUID PRIMARY KEY,
    sale_id           UUID           NOT NULL REFERENCES sales (id) ON DELETE CASCADE,
    return_id         UUID           REFERENCES returns (id),
    receipt_number    VARCHAR(30)    NOT NULL,
    branch_id         UUID           NOT NULL,
    -- SALE | RETURN | REPRINT
    type              VARCHAR(20)    NOT NULL DEFAULT 'SALE',
    issued_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- [{taxClassCode, taxRate, net, tax, gross}], summing to the sale's totals.
    tax_breakdown     JSONB          NOT NULL,
    net_total         NUMERIC(19, 4) NOT NULL,
    tax_total         NUMERIC(19, 4) NOT NULL,
    grand_total       NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'KES',
    print_count       INTEGER        NOT NULL DEFAULT 0,
    last_printed_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_receipts_number_type UNIQUE (receipt_number, type)
);

CREATE INDEX idx_receipts_sale ON receipts (sale_id);
CREATE INDEX idx_receipts_branch_issued ON receipts (branch_id, issued_at DESC);

-- ---------------------------------------------------------------------------
-- price_overrides: an audit trail of every discount a human decided on.
--
-- Its own table as well as columns on the line, because the question "who has been overriding
-- prices" is asked across sales, not within one.
-- ---------------------------------------------------------------------------
CREATE TABLE price_overrides (
    id                 UUID PRIMARY KEY,
    cart_id            UUID           REFERENCES carts (id) ON DELETE CASCADE,
    cart_line_id       UUID,
    sale_id            UUID           REFERENCES sales (id),
    branch_id          UUID           NOT NULL,
    product_id         UUID           NOT NULL,
    sku                VARCHAR(50),
    original_unit_price NUMERIC(19, 4) NOT NULL,
    new_unit_price     NUMERIC(19, 4) NOT NULL,
    quantity           NUMERIC(19, 3) NOT NULL,
    -- What the shop gave away, which is the figure a report ranks by.
    value_given_away   NUMERIC(19, 4) NOT NULL,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'KES',
    reason             VARCHAR(500)   NOT NULL,
    approved_by        UUID           NOT NULL,
    cashier_id         UUID,
    occurred_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_price_overrides_branch ON price_overrides (branch_id, occurred_at DESC);
CREATE INDEX idx_price_overrides_approver ON price_overrides (approved_by, occurred_at DESC);
CREATE INDEX idx_price_overrides_product ON price_overrides (product_id);

-- ---------------------------------------------------------------------------
-- offline_sync_batches: what a terminal sent, and what we made of it.
--
-- Keyed on the terminal's Idempotency-Key so resubmitting the same batch returns the first
-- answer instead of processing it twice. Kept rather than discarded because "the till says it
-- synced and head office has no record" is the worst failure this system can have, and the only
-- defence is a record of every batch that arrived.
-- ---------------------------------------------------------------------------
CREATE TABLE offline_sync_batches (
    id                UUID PRIMARY KEY,
    idempotency_key   VARCHAR(120)   NOT NULL,
    branch_id         UUID           NOT NULL,
    register_id       UUID,
    cashier_id        UUID,
    submitted_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    sale_count        INTEGER        NOT NULL DEFAULT 0,
    accepted_count    INTEGER        NOT NULL DEFAULT 0,
    duplicate_count   INTEGER        NOT NULL DEFAULT 0,
    rejected_count    INTEGER        NOT NULL DEFAULT 0,
    variance_count    INTEGER        NOT NULL DEFAULT 0,
    -- The per-sale answer, replayed verbatim if the same key arrives again.
    result            JSONB          NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_offline_sync_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_offline_sync_branch ON offline_sync_batches (branch_id, submitted_at DESC);

-- ---------------------------------------------------------------------------
-- idempotency_records: the answer already given to a retried request.
--
-- A lane that loses its connection mid-request does not know whether the server acted, so it
-- retries with the same Idempotency-Key. Without this table the retry adds the tin twice or takes
-- the payment twice. The request hash guards against a key being reused for a different request,
-- which is a client bug and is refused rather than answered with someone else's response.
--
-- Infrastructure rather than a business record, like the outbox: no audit columns, and rows are
-- safe to purge once no terminal could still be retrying.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(120)  NOT NULL,
    -- Scoped per caller, so two terminals cannot collide on a key by accident.
    principal        VARCHAR(64)   NOT NULL,
    request_hash     VARCHAR(64)   NOT NULL,
    -- IN_PROGRESS | COMPLETED
    status           VARCHAR(20)   NOT NULL,
    response_status  INTEGER,
    response_body    TEXT,
    content_type     VARCHAR(100),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_key UNIQUE (principal, idempotency_key)
);

CREATE INDEX idx_idempotency_created ON idempotency_records (created_at);
