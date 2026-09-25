-- What the business wants an item to earn, and the price reviews a delivery opens when its cost
-- would earn less.
--
-- A target margin is a fraction of the selling price without tax (0.15 = 15%). A category's
-- applies to its sub-categories unless they set their own, and a product's overrides both. NULL
-- means none is set: the only warning then is selling below cost.
--
-- Nullable columns and a new table: safe with the previous version still running.
ALTER TABLE categories ADD COLUMN target_margin NUMERIC(7, 4)
    CONSTRAINT categories_target_margin_check CHECK (target_margin >= 0 AND target_margin < 1);
ALTER TABLE products ADD COLUMN target_margin NUMERIC(7, 4)
    CONSTRAINT products_target_margin_check CHECK (target_margin >= 0 AND target_margin < 1);

-- A delivery whose cost puts an item below its target, or below cost, at the branch it arrived at.
-- The figures are as they stood at the check, so the review still reads right after the price
-- or the cost moves on. One open review per product and branch: a later delivery replaces it.
CREATE TABLE price_reviews (
    id                 UUID PRIMARY KEY,
    product_id         UUID          NOT NULL REFERENCES products (id),
    branch_id          UUID          NOT NULL,
    receipt_id         UUID          NOT NULL,
    received_at        TIMESTAMPTZ   NOT NULL,
    -- One unit's landed cost without VAT.
    unit_cost          NUMERIC(19, 4) NOT NULL,
    -- The price at the branch when checked, as entered (with VAT when the product's price has it).
    price              NUMERIC(19, 4) NOT NULL,
    price_includes_tax BOOLEAN       NOT NULL,
    tax_rate           NUMERIC(9, 6) NOT NULL,
    -- Where that price came from: the product's base price, or a price list for the branch.
    price_source       VARCHAR(20)   NOT NULL,
    price_list_id      UUID,
    margin             NUMERIC(9, 4),
    target_margin      NUMERIC(7, 4),
    finding            VARCHAR(20)   NOT NULL,
    suggested_price    NUMERIC(19, 4) NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    decided_by         UUID,
    decided_at         TIMESTAMPTZ,
    -- The price set on accepting, or why the old one was kept.
    new_price          NUMERIC(19, 4),
    reason             VARCHAR(500),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT price_reviews_finding_check CHECK (finding IN ('BELOW_TARGET', 'BELOW_COST')),
    CONSTRAINT price_reviews_status_check
        CHECK (status IN ('OPEN', 'ACCEPTED', 'KEPT', 'SUPERSEDED')),
    CONSTRAINT price_reviews_source_check CHECK (price_source IN ('BASE_PRICE', 'PRICE_LIST')),
    CONSTRAINT price_reviews_decision_check
        CHECK (status <> 'ACCEPTED' OR new_price IS NOT NULL),
    CONSTRAINT price_reviews_kept_check CHECK (status <> 'KEPT' OR reason IS NOT NULL)
);

CREATE UNIQUE INDEX uq_price_reviews_open ON price_reviews (product_id, branch_id)
    WHERE status = 'OPEN';
CREATE INDEX idx_price_reviews_branch ON price_reviews (branch_id, status, received_at DESC);
