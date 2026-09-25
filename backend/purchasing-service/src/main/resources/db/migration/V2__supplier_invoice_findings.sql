-- The three-way match's findings, kept. They used to live only in the response to recording the
-- invoice and a one-line summary, so reopening an invoice in exception could not say which product
-- was over-billed or by how much. Each finding is now a row, and the invoice keeps the total the
-- delivery justified.
--
-- A new column (nullable) and a new table, so it is safe with the previous version still running.
ALTER TABLE supplier_invoices ADD COLUMN justified_total NUMERIC(19, 4);

CREATE TABLE supplier_invoice_variances (
    id            UUID PRIMARY KEY,
    invoice_id    UUID           NOT NULL REFERENCES supplier_invoices (id),
    product_id    UUID,
    sku           VARCHAR(50),
    type          VARCHAR(30)    NOT NULL,
    expected      NUMERIC(19, 4),
    actual        NUMERIC(19, 4),
    difference    NUMERIC(19, 4),
    amount_effect NUMERIC(19, 4),
    description   VARCHAR(500),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_supplier_invoice_variances_invoice ON supplier_invoice_variances (invoice_id);
