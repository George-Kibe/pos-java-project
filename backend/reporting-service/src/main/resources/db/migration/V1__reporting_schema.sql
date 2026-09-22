-- reporting-service schema.
--
-- The numbers the business runs on, built only from what other services announced.
--
-- Three ideas shape it:
--
--   1. The event log is the source; everything else can be thrown away. Every event consumed is
--      kept, verbatim and in arrival order, and the fact tables are a projection of that log. A
--      rebuild truncates the facts and replays the log - which Kafka could not do reliably, since
--      it keeps sales for 30 days and everything else for 7.
--   2. Each kind of event writes only its own table. Kafka orders events within a topic, not
--      across them: a stock deduction can arrive before the sale it belongs to, a void before the
--      sale it voids. Facts that never overwrite one another come out the same whatever order they
--      arrive in, which is what makes a rebuild reproduce the incremental numbers exactly.
--   3. Aggregates are computed from the facts when they are read. There is then one thing for a
--      rebuild to reproduce, not a dozen summary tables that could each drift.
--
-- Fact tables are derived data: no audit columns and no optimistic locking, because nothing edits
-- them except the projection, and a rebuild recreates every row.

-- ---------------------------------------------------------------------------
-- event_log: every event consumed, exactly once, in the order it arrived.
--
-- event_id is unique, so a redelivery is refused here and never projected twice: this table is the
-- consumer's idempotency ledger as well as the rebuild's source.
-- ---------------------------------------------------------------------------
CREATE TABLE event_log (
    seq          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL,
    topic        VARCHAR(120) NOT NULL,
    event_type   VARCHAR(120),
    occurred_at  TIMESTAMPTZ,
    payload      TEXT         NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_log_event UNIQUE (event_id)
);

CREATE INDEX idx_event_log_topic ON event_log (topic, seq);

-- ---------------------------------------------------------------------------
-- rebuild_runs: when the facts were last rebuilt from the log, and what it found.
-- ---------------------------------------------------------------------------
CREATE TABLE rebuild_runs (
    id              UUID PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    events_replayed BIGINT      NOT NULL DEFAULT 0,
    requested_by    UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT      NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- Products, as catalog last described them. Category is what a margin report groups by.
-- ---------------------------------------------------------------------------
CREATE TABLE report_products (
    product_id     UUID PRIMARY KEY,
    sku            VARCHAR(60),
    name           VARCHAR(255),
    category_id    UUID,
    category_code  VARCHAR(60),
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    -- When catalog said this; an older description arriving late never overwrites a newer one.
    as_of          TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------------------
-- Sales, as completed. business_date is the Nairobi calendar day, fixed when the sale is
-- projected: a report for "Tuesday" means the shop's Tuesday, not UTC's.
-- ---------------------------------------------------------------------------
CREATE TABLE report_sales (
    sale_id         UUID PRIMARY KEY,
    receipt_number  VARCHAR(40),
    branch_id       UUID           NOT NULL,
    register_id     UUID,
    shift_id        UUID,
    cashier_id      UUID,
    customer_id     UUID,
    completed_at    TIMESTAMPTZ    NOT NULL,
    business_date   DATE           NOT NULL,
    net_total       NUMERIC(19, 4) NOT NULL,
    tax_total       NUMERIC(19, 4) NOT NULL,
    grand_total     NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL
);

CREATE INDEX idx_report_sales_date ON report_sales (business_date, branch_id);
CREATE INDEX idx_report_sales_shift ON report_sales (shift_id);
CREATE INDEX idx_report_sales_cashier ON report_sales (cashier_id, business_date);

CREATE TABLE report_sale_lines (
    sale_id         UUID           NOT NULL,
    line_number     INTEGER        NOT NULL,
    product_id      UUID           NOT NULL,
    sku             VARCHAR(60),
    product_name    VARCHAR(255),
    quantity        NUMERIC(19, 3) NOT NULL,
    line_total      NUMERIC(19, 4) NOT NULL,
    tax_amount      NUMERIC(19, 4) NOT NULL,
    tax_class_code  VARCHAR(30),
    PRIMARY KEY (sale_id, line_number)
);

CREATE INDEX idx_report_sale_lines_product ON report_sale_lines (product_id);

-- How each sale was paid, as the drawer keeps it: cash net of change.
CREATE TABLE report_sale_tenders (
    sale_id  UUID           NOT NULL,
    ordinal  INTEGER        NOT NULL,
    method   VARCHAR(20)    NOT NULL,
    amount   NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (sale_id, ordinal)
);

-- What the goods cost, from inventory's deduction: batch quantity at landed cost. May arrive
-- before the sale does.
CREATE TABLE report_sale_costs (
    sale_id     UUID           NOT NULL,
    product_id  UUID           NOT NULL,
    quantity    NUMERIC(19, 3) NOT NULL,
    cost        NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (sale_id, product_id)
);

-- A void is a fact about a sale, kept apart from it: it can arrive first.
CREATE TABLE report_sale_voids (
    sale_id     UUID PRIMARY KEY,
    voided_at   TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(60),
    approved_by UUID
);

CREATE TABLE report_returns (
    return_id        UUID PRIMARY KEY,
    sale_id          UUID           NOT NULL,
    branch_id        UUID           NOT NULL,
    till_session_id  UUID,
    refund_method    VARCHAR(20)    NOT NULL,
    refund_total     NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    processed_at     TIMESTAMPTZ    NOT NULL,
    business_date    DATE           NOT NULL
);

CREATE INDEX idx_report_returns_date ON report_returns (business_date, branch_id);
CREATE INDEX idx_report_returns_till ON report_returns (till_session_id);
CREATE INDEX idx_report_returns_sale ON report_returns (sale_id);

CREATE TABLE report_return_lines (
    return_id   UUID           NOT NULL,
    ordinal     INTEGER        NOT NULL,
    product_id  UUID           NOT NULL,
    quantity    NUMERIC(19, 3) NOT NULL,
    resaleable  BOOLEAN        NOT NULL,
    PRIMARY KEY (return_id, ordinal)
);

-- ---------------------------------------------------------------------------
-- Shifts as sales closed them: the till's own figures, to reconcile ours against.
-- ---------------------------------------------------------------------------
CREATE TABLE report_shifts (
    shift_id        UUID PRIMARY KEY,
    branch_id       UUID           NOT NULL,
    register_id     UUID,
    cashier_id      UUID,
    closed_by       UUID,
    opened_at       TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ    NOT NULL,
    business_date   DATE           NOT NULL,
    opening_float   NUMERIC(19, 4) NOT NULL,
    cash_sales      NUMERIC(19, 4) NOT NULL,
    cash_refunds    NUMERIC(19, 4) NOT NULL,
    cash_drops      NUMERIC(19, 4) NOT NULL,
    expected_cash   NUMERIC(19, 4) NOT NULL,
    counted_cash    NUMERIC(19, 4) NOT NULL,
    variance        NUMERIC(19, 4) NOT NULL,
    non_cash_sales  NUMERIC(19, 4) NOT NULL,
    sale_count      INTEGER        NOT NULL,
    currency        VARCHAR(3)     NOT NULL
);

CREATE INDEX idx_report_shifts_date ON report_shifts (business_date, branch_id);

-- ---------------------------------------------------------------------------
-- Stock at cost, as inventory valued it. A snapshot arrives in pages and only counts once every
-- page is here: half a branch's stock reported as its value would be worse than none.
-- ---------------------------------------------------------------------------
CREATE TABLE report_stock_valuations (
    snapshot_id       UUID           NOT NULL,
    page              INTEGER        NOT NULL,
    page_count        INTEGER        NOT NULL,
    branch_id         UUID           NOT NULL,
    valued_at         TIMESTAMPTZ    NOT NULL,
    product_id        UUID           NOT NULL,
    sku               VARCHAR(60),
    quantity_on_hand  NUMERIC(19, 3) NOT NULL,
    value_at_cost     NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);

CREATE INDEX idx_report_stock_valuations_branch ON report_stock_valuations (branch_id, valued_at DESC);

-- ---------------------------------------------------------------------------
-- Batches nearing expiry, as inventory last reported each one.
-- ---------------------------------------------------------------------------
CREATE TABLE report_expiring_batches (
    batch_id            UUID PRIMARY KEY,
    batch_number        VARCHAR(60),
    product_id          UUID           NOT NULL,
    sku                 VARCHAR(60),
    product_name        VARCHAR(255),
    branch_id           UUID           NOT NULL,
    expiry_date         DATE           NOT NULL,
    quantity_remaining  NUMERIC(19, 3) NOT NULL,
    value_at_cost       NUMERIC(19, 4) NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    reported_at         TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_report_expiring_branch ON report_expiring_batches (branch_id, expiry_date);
