-- catalog-service schema: what a product is, what it costs, and what tax applies to it.
--
-- Money is NUMERIC(19,4) and quantities NUMERIC(19,3) throughout. Four decimal places because
-- intermediate arithmetic (a percentage of a weighed line) needs more precision than the two the
-- customer sees, and rounding happens once, deliberately, at the end. Three on quantities because
-- 1.235 kg of tomatoes is an ordinary transaction.

-- ---------------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------------
CREATE TABLE categories (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    -- Hierarchy: Grocery > Dairy > Milk. Self-referencing rather than a fixed depth, because
    -- every retailer nests differently and a fourth level should not need a migration.
    parent_id   UUID REFERENCES categories (id),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_categories_code UNIQUE (code)
);

CREATE INDEX idx_categories_parent ON categories (parent_id);

CREATE TABLE brands (
    id         UUID PRIMARY KEY,
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(150) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_brands_code UNIQUE (code)
);

CREATE TABLE units_of_measure (
    id             UUID PRIMARY KEY,
    code           VARCHAR(20)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    -- A unit sold by count cannot be sold as 0.5; a unit sold by weight must be.
    allows_decimal BOOLEAN      NOT NULL DEFAULT FALSE,
    decimal_places INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_uom_code UNIQUE (code)
);

-- ---------------------------------------------------------------------------
-- Tax
--
-- Rates are effective-dated rather than a single column on the class. When VAT changes, last
-- month's receipt must still reprint with last month's rate: a receipt is a tax document, and
-- rewriting history on it is both wrong and, in most jurisdictions, illegal. So a rate is always
-- resolved "as at" an instant, and the old row stays exactly as it was.
-- ---------------------------------------------------------------------------
CREATE TABLE tax_classes (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    description VARCHAR(255),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_tax_classes_code UNIQUE (code)
);

CREATE TABLE tax_rates (
    id           UUID PRIMARY KEY,
    tax_class_id UUID          NOT NULL REFERENCES tax_classes (id) ON DELETE CASCADE,
    -- 0.160000 for 16%. Six decimal places covers rates like 8.25% without rounding.
    rate         NUMERIC(9, 6) NOT NULL,
    valid_from   TIMESTAMPTZ   NOT NULL,
    -- NULL means "still in force". Exclusive upper bound, so periods abut without overlapping.
    valid_to     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT tax_rates_rate_check CHECK (rate >= 0 AND rate < 1),
    CONSTRAINT tax_rates_period_check CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE INDEX idx_tax_rates_lookup ON tax_rates (tax_class_id, valid_from DESC);

-- Two rates in force at the same instant for one class would make tax non-deterministic.
--
-- Guarded rather than a plain CREATE EXTENSION: the service's database role has rights on its own
-- schema only, and creating an extension needs more than that. The bootstrap script installs it as
-- the superuser, so this block does nothing in a real deployment and covers a bare test database.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        CREATE EXTENSION btree_gist;
    END IF;
END
$$;
--
-- Deferred to commit, not checked per statement. Changing a rate means closing the current period
-- and opening the next, and those are two statements: checked immediately, whichever order the ORM
-- emits them in, there is an instant where the periods overlap and the write fails for no good
-- reason. At commit the pair is consistent, which is the property actually worth enforcing.
ALTER TABLE tax_rates
    ADD CONSTRAINT tax_rates_no_overlap
    EXCLUDE USING gist (
        tax_class_id WITH =,
        tstzrange(valid_from, valid_to) WITH &&
    ) DEFERRABLE INITIALLY DEFERRED;

-- ---------------------------------------------------------------------------
-- Products
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id                UUID PRIMARY KEY,
    sku               VARCHAR(50)  NOT NULL,
    name              VARCHAR(200) NOT NULL,
    description       TEXT,
    category_id       UUID         NOT NULL REFERENCES categories (id),
    brand_id          UUID REFERENCES brands (id),
    unit_of_measure_id UUID        NOT NULL REFERENCES units_of_measure (id),
    tax_class_id      UUID         NOT NULL REFERENCES tax_classes (id),
    -- Sold by weight: the till asks for a quantity from the scale rather than counting units.
    sell_by_weight    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Whether base_price already contains tax. Per product, because a supermarket commonly shows
    -- shelf prices inclusive while wholesale lines are quoted exclusive.
    price_includes_tax BOOLEAN     NOT NULL DEFAULT TRUE,
    base_price        NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)      NOT NULL DEFAULT 'KES',
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    reorder_point     NUMERIC(19, 3),
    reorder_quantity  NUMERIC(19, 3),
    image_url         VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT products_base_price_check CHECK (base_price >= 0)
);

CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_active ON products (active) WHERE active;
-- Supports name search at the till without a full scan.
CREATE INDEX idx_products_name_lower ON products (lower(name));

-- ---------------------------------------------------------------------------
-- Barcodes
--
-- Several per product on purpose: a case, an inner and a single of the same item carry different
-- barcodes, and manufacturers reissue them. Scanning any of them must find the product.
-- ---------------------------------------------------------------------------
CREATE TABLE product_barcodes (
    id         UUID PRIMARY KEY,
    product_id UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    barcode    VARCHAR(64) NOT NULL,
    is_primary BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    -- One barcode cannot mean two products, or the till would have to ask the cashier which.
    CONSTRAINT uq_product_barcodes_barcode UNIQUE (barcode)
);

CREATE INDEX idx_product_barcodes_product ON product_barcodes (product_id);

-- ---------------------------------------------------------------------------
-- Scale barcodes
--
-- A deli or butchery scale prints an EAN-13 that encodes the item and either its weight or its
-- price in the digits. There is no single standard: the prefix and the digit positions vary by
-- retailer and by scale vendor, so the format is configuration rather than code.
-- ---------------------------------------------------------------------------
CREATE TABLE scale_barcode_rules (
    id               UUID PRIMARY KEY,
    -- Leading digits that mark a barcode as scale-printed, e.g. '20' or '21'.
    prefix           VARCHAR(4)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    -- Zero-based offsets into the 13 digits.
    item_code_start  INTEGER     NOT NULL,
    item_code_length INTEGER     NOT NULL,
    value_start      INTEGER     NOT NULL,
    value_length     INTEGER     NOT NULL,
    -- WEIGHT (grams, usually) or PRICE (minor units).
    embedded_type    VARCHAR(10) NOT NULL,
    -- What the embedded integer must be divided by: 1000 for grams to kilograms, 100 for cents.
    value_divisor    NUMERIC(19, 4) NOT NULL,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_scale_rules_prefix UNIQUE (prefix),
    CONSTRAINT scale_rules_type_check CHECK (embedded_type IN ('WEIGHT', 'PRICE')),
    CONSTRAINT scale_rules_divisor_check CHECK (value_divisor > 0)
);

-- ---------------------------------------------------------------------------
-- Price lists
--
-- A branch may price differently from the chain. Resolution is: the highest-priority active price
-- list for that branch that contains the product, else the product's base price. The base price
-- is therefore always a valid answer, and a missing price list entry is not an error.
-- ---------------------------------------------------------------------------
CREATE TABLE price_lists (
    id         UUID PRIMARY KEY,
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(150) NOT NULL,
    -- NULL means it applies to every branch.
    branch_id  UUID,
    -- Higher wins. Ties broken by code, so resolution is deterministic.
    priority   INTEGER      NOT NULL DEFAULT 0,
    valid_from TIMESTAMPTZ,
    valid_to   TIMESTAMPTZ,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_price_lists_code UNIQUE (code),
    CONSTRAINT price_lists_period_check CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE INDEX idx_price_lists_branch ON price_lists (branch_id, priority DESC) WHERE active;

CREATE TABLE price_list_items (
    id            UUID PRIMARY KEY,
    price_list_id UUID           NOT NULL REFERENCES price_lists (id) ON DELETE CASCADE,
    product_id    UUID           NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    price         NUMERIC(19, 4) NOT NULL,
    currency      VARCHAR(3)        NOT NULL DEFAULT 'KES',
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_price_list_items UNIQUE (price_list_id, product_id),
    CONSTRAINT price_list_items_price_check CHECK (price >= 0)
);

CREATE INDEX idx_price_list_items_product ON price_list_items (product_id);

-- ---------------------------------------------------------------------------
-- Promotions
--
-- priority and stackable together make the outcome deterministic. Without them, "which discount
-- did this customer get" depends on row order, and two tills can price the same basket
-- differently - which is the kind of thing that ends up in a newspaper.
-- ---------------------------------------------------------------------------
CREATE TABLE promotions (
    id            UUID PRIMARY KEY,
    code          VARCHAR(50)  NOT NULL,
    name          VARCHAR(150) NOT NULL,
    type          VARCHAR(30)  NOT NULL,
    -- Percentage (0.10 = 10%) for PERCENTAGE_OFF; an amount for AMOUNT_OFF and BUNDLE.
    value         NUMERIC(19, 4),
    currency      VARCHAR(3)      NOT NULL DEFAULT 'KES',
    -- BUY_X_GET_Y: buy this many, get that many free.
    buy_quantity  NUMERIC(19, 3),
    get_quantity  NUMERIC(19, 3),
    -- Below this the promotion does not apply at all.
    min_quantity  NUMERIC(19, 3),
    -- Lower runs first. Ties broken by code.
    priority      INTEGER      NOT NULL DEFAULT 100,
    -- False means it cannot be combined: the single best-value promotion wins instead.
    stackable     BOOLEAN      NOT NULL DEFAULT FALSE,
    member_only   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- NULL means every branch.
    branch_id     UUID,
    valid_from    TIMESTAMPTZ,
    valid_to      TIMESTAMPTZ,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_promotions_code UNIQUE (code),
    CONSTRAINT promotions_type_check CHECK (
        type IN ('PERCENTAGE_OFF', 'AMOUNT_OFF', 'BUY_X_GET_Y', 'BUNDLE')
    ),
    CONSTRAINT promotions_period_check CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from)
);

CREATE INDEX idx_promotions_active ON promotions (active, priority) WHERE active;

-- What a promotion applies to: one product, a whole category, or everything.
CREATE TABLE promotion_rules (
    id           UUID PRIMARY KEY,
    promotion_id UUID        NOT NULL REFERENCES promotions (id) ON DELETE CASCADE,
    scope_type   VARCHAR(20) NOT NULL,
    scope_id     UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT promotion_rules_scope_check CHECK (
        (scope_type = 'ALL' AND scope_id IS NULL)
        OR (scope_type IN ('PRODUCT', 'CATEGORY') AND scope_id IS NOT NULL)
    )
);

CREATE INDEX idx_promotion_rules_promotion ON promotion_rules (promotion_id);
CREATE INDEX idx_promotion_rules_scope ON promotion_rules (scope_type, scope_id);
