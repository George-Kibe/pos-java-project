-- Stock written off, corrected or found by a count, as inventory announces it (adjustment-posted,
-- a stock take's differences included), for the shrinkage report. One row per line, keyed by the
-- adjustment and the line's place in it so a redelivered event writes nothing twice; business_date
-- is the shop's calendar day, as for sales.
--
-- A new table only, so it is safe with the previous version still running; a rebuild replays the
-- event log into it like every other projection.
CREATE TABLE report_stock_adjustments (
    adjustment_id   UUID           NOT NULL,
    line_number     INTEGER        NOT NULL,
    branch_id       UUID           NOT NULL,
    reason_code     VARCHAR(50)    NOT NULL,
    posted_at       TIMESTAMPTZ    NOT NULL,
    business_date   DATE           NOT NULL,
    product_id      UUID           NOT NULL,
    sku             VARCHAR(60),
    quantity_delta  NUMERIC(19, 3) NOT NULL,
    value_at_cost   NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    PRIMARY KEY (adjustment_id, line_number)
);

CREATE INDEX idx_report_stock_adjustments_date ON report_stock_adjustments (business_date, branch_id);
