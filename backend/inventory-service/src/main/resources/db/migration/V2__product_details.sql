-- The few catalog details a stock row shows - SKU, name, unit - kept per product as catalog
-- announces them (product-changed), not only on stock rows that already exist. A product's first
-- stock at a branch used to arrive with no name: the announcement had come before there was a row
-- to copy it onto. New rows are now filled from here.
--
-- A new table only, so it is safe with the previous version still running.
CREATE TABLE product_details (
    id              UUID PRIMARY KEY,
    product_id      UUID         NOT NULL,
    sku             VARCHAR(50),
    name            VARCHAR(200),
    unit_of_measure VARCHAR(20),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_details_product UNIQUE (product_id)
);
