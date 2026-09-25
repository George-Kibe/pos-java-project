-- Expenses, as purchasing announces them, for net profit.
--
-- One row per expense holding its latest revision: each event carries the whole expense and a
-- revision that only rises, so the row is replaced only by a higher one and events arriving out of
-- order - or replayed by a rebuild - leave the same row. Only APPROVED ones are counted, at read
-- time. A NULL branch is head office, counted once in the business-wide figure.
CREATE TABLE report_expenses (
    expense_id     UUID PRIMARY KEY,
    expense_number VARCHAR(30)    NOT NULL,
    branch_id      UUID,
    category       VARCHAR(30)    NOT NULL,
    description    VARCHAR(300)   NOT NULL,
    incurred_on    DATE           NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    tax_amount     NUMERIC(19, 4) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    revision       BIGINT         NOT NULL
);

CREATE INDEX idx_report_expenses_period ON report_expenses (incurred_on, branch_id)
    WHERE status = 'APPROVED';
