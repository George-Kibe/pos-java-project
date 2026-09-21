-- purchasing-service schema.
--
-- How stock enters the shop, and what it really cost.
--
-- Two ideas shape this schema. First, a purchase order is a commitment that changes state over
-- time rather than a document that is edited: every transition is recorded and nothing is deleted,
-- because "who approved this and when" is the question an auditor asks. Second, the price on the
-- order is not the cost of the goods. Freight and duty arrive on the delivery, not on the order,
-- so the landed cost is computed at receipt and it is that figure - not the order price - that
-- inventory values stock at.

-- ---------------------------------------------------------------------------
-- suppliers
--
-- payment_terms_days drives when an invoice is due; lead_time_days drives the reorder suggestion,
-- because ordering on the day stock runs out is already too late.
-- ---------------------------------------------------------------------------
CREATE TABLE suppliers (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(30)  NOT NULL,
    name                VARCHAR(200) NOT NULL,
    contact_name        VARCHAR(200),
    email               VARCHAR(320),
    phone               VARCHAR(30),
    address             VARCHAR(500),
    tax_identifier      VARCHAR(50),
    payment_terms_days  INTEGER      NOT NULL DEFAULT 30,
    lead_time_days      INTEGER      NOT NULL DEFAULT 7,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'KES',
    -- ACTIVE | ON_HOLD | INACTIVE. On hold blocks new orders but leaves history readable.
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    notes               VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_suppliers_code UNIQUE (code),
    CONSTRAINT suppliers_terms_check CHECK (payment_terms_days >= 0),
    CONSTRAINT suppliers_lead_time_check CHECK (lead_time_days >= 0)
);

CREATE INDEX idx_suppliers_status ON suppliers (status);
CREATE INDEX idx_suppliers_name ON suppliers (lower(name));

-- ---------------------------------------------------------------------------
-- supplier_products: what a supplier sells us, and for how much.
--
-- product_id points into catalog's schema and is deliberately not a foreign key - services do not
-- share tables. last_cost is what the most recent delivery actually charged, which is usually the
-- more honest number than the agreed one.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_products (
    id                 UUID PRIMARY KEY,
    supplier_id        UUID           NOT NULL REFERENCES suppliers (id) ON DELETE CASCADE,
    product_id         UUID           NOT NULL,
    sku                VARCHAR(50),
    product_name       VARCHAR(200),
    -- The supplier's own code for it, which is what appears on their invoice.
    supplier_sku       VARCHAR(50),
    agreed_unit_cost   NUMERIC(19, 4) NOT NULL,
    last_unit_cost     NUMERIC(19, 4),
    last_received_at   TIMESTAMPTZ,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'KES',
    minimum_order_qty  NUMERIC(19, 3) NOT NULL DEFAULT 1,
    lead_time_days     INTEGER,
    -- The default source for this product when a reorder is suggested.
    is_preferred       BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_supplier_products UNIQUE (supplier_id, product_id),
    CONSTRAINT supplier_products_cost_check CHECK (agreed_unit_cost >= 0),
    CONSTRAINT supplier_products_moq_check CHECK (minimum_order_qty > 0)
);

CREATE INDEX idx_supplier_products_product ON supplier_products (product_id);
-- At most one preferred supplier per product, enforced rather than left to the application.
CREATE UNIQUE INDEX uq_supplier_products_preferred
    ON supplier_products (product_id)
    WHERE is_preferred;

-- ---------------------------------------------------------------------------
-- purchase_orders
--
-- Status is the whole story of the document:
--   DRAFT -> SUBMITTED -> APPROVED -> SENT -> PARTIALLY_RECEIVED -> RECEIVED -> CLOSED
-- with CANCELLED reachable until goods arrive. Approval is recorded with actor and time because
-- it is a spending authorisation, and approved_total freezes what was authorised - editing a line
-- afterwards must not quietly raise the amount someone signed for.
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id                       UUID PRIMARY KEY,
    order_number             VARCHAR(30)    NOT NULL,
    supplier_id              UUID           NOT NULL REFERENCES suppliers (id),
    branch_id                UUID           NOT NULL,
    status                   VARCHAR(25)    NOT NULL DEFAULT 'DRAFT',
    order_date               DATE           NOT NULL DEFAULT CURRENT_DATE,
    expected_delivery_date   DATE,
    net_total                NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_total                NUMERIC(19, 4) NOT NULL DEFAULT 0,
    grand_total              NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency                 VARCHAR(3)     NOT NULL DEFAULT 'KES',
    -- What was authorised, frozen at approval.
    approved_total           NUMERIC(19, 4),
    submitted_by             UUID,
    submitted_at             TIMESTAMPTZ,
    approved_by              UUID,
    approved_at              TIMESTAMPTZ,
    sent_at                  TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ,
    cancelled_at             TIMESTAMPTZ,
    cancellation_reason      VARCHAR(500),
    notes                    VARCHAR(1000),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by               UUID,
    updated_by               UUID,
    version                  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_purchase_orders_number UNIQUE (order_number),
    CONSTRAINT purchase_orders_totals_check CHECK (net_total >= 0 AND tax_total >= 0 AND grand_total >= 0)
);

CREATE INDEX idx_purchase_orders_supplier ON purchase_orders (supplier_id);
CREATE INDEX idx_purchase_orders_branch_status ON purchase_orders (branch_id, status);
-- Supports "what is still coming", the list a stock controller lives in.
CREATE INDEX idx_purchase_orders_open
    ON purchase_orders (branch_id, expected_delivery_date)
    WHERE status IN ('SENT', 'PARTIALLY_RECEIVED');

-- ---------------------------------------------------------------------------
-- purchase_order_lines
--
-- quantity_received is a running total maintained as receipts post, so "is this order complete"
-- does not require summing the GRNs on every list page.
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_order_lines (
    id                 UUID PRIMARY KEY,
    purchase_order_id  UUID           NOT NULL REFERENCES purchase_orders (id) ON DELETE CASCADE,
    line_number        INTEGER        NOT NULL,
    product_id         UUID           NOT NULL,
    sku                VARCHAR(50),
    product_name       VARCHAR(200),
    quantity_ordered   NUMERIC(19, 3) NOT NULL,
    quantity_received  NUMERIC(19, 3) NOT NULL DEFAULT 0,
    unit_cost          NUMERIC(19, 4) NOT NULL,
    tax_rate           NUMERIC(9, 6)  NOT NULL DEFAULT 0,
    tax_amount         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_po_lines_number UNIQUE (purchase_order_id, line_number),
    CONSTRAINT uq_po_lines_product UNIQUE (purchase_order_id, product_id),
    CONSTRAINT po_lines_qty_check CHECK (quantity_ordered > 0),
    CONSTRAINT po_lines_received_check CHECK (quantity_received >= 0),
    CONSTRAINT po_lines_cost_check CHECK (unit_cost >= 0)
);

CREATE INDEX idx_po_lines_product ON purchase_order_lines (product_id);

-- ---------------------------------------------------------------------------
-- goods_received_notes
--
-- A GRN is what actually turned up, which is frequently not what was ordered. It may reference a
-- purchase order or stand alone, because deliveries do arrive without one.
--
-- freight_amount and duty_amount are charges for the delivery as a whole. They are spread across
-- the lines at posting to give each one a landed unit cost; see landed_unit_cost on grn_lines.
-- ---------------------------------------------------------------------------
CREATE TABLE goods_received_notes (
    id                  UUID PRIMARY KEY,
    grn_number          VARCHAR(30)    NOT NULL,
    purchase_order_id   UUID           REFERENCES purchase_orders (id),
    supplier_id         UUID           NOT NULL REFERENCES suppliers (id),
    branch_id           UUID           NOT NULL,
    -- DRAFT | POSTED | CANCELLED. Only a posted GRN moves stock.
    status              VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    -- The supplier's delivery note, for matching against a paper trail.
    delivery_note_ref   VARCHAR(50),
    received_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    received_by         UUID,
    posted_at           TIMESTAMPTZ,
    posted_by           UUID,
    freight_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    duty_amount         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    -- BY_VALUE spreads charges in proportion to line value, BY_QUANTITY per unit.
    allocation_basis    VARCHAR(20)    NOT NULL DEFAULT 'BY_VALUE',
    goods_total         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    landed_total        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KES',
    notes               VARCHAR(1000),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_grn_number UNIQUE (grn_number),
    CONSTRAINT grn_charges_check CHECK (freight_amount >= 0 AND duty_amount >= 0)
);

CREATE INDEX idx_grn_supplier ON goods_received_notes (supplier_id);
CREATE INDEX idx_grn_po ON goods_received_notes (purchase_order_id);
CREATE INDEX idx_grn_branch_status ON goods_received_notes (branch_id, status);

-- ---------------------------------------------------------------------------
-- grn_lines
--
-- batch_number and expiry_date are captured here and carried to inventory unchanged: this is the
-- only point at which a human is holding the carton and can read what is stamped on it.
--
-- unit_cost is what the supplier charged. landed_unit_cost adds this line's share of freight and
-- duty and is what stock is valued at, so a margin calculated later includes the cost of getting
-- the goods here.
-- ---------------------------------------------------------------------------
CREATE TABLE grn_lines (
    id                       UUID PRIMARY KEY,
    grn_id                   UUID           NOT NULL REFERENCES goods_received_notes (id) ON DELETE CASCADE,
    purchase_order_line_id   UUID           REFERENCES purchase_order_lines (id),
    line_number              INTEGER        NOT NULL,
    product_id               UUID           NOT NULL,
    sku                      VARCHAR(50),
    product_name             VARCHAR(200),
    quantity_ordered         NUMERIC(19, 3),
    quantity_received        NUMERIC(19, 3) NOT NULL,
    quantity_rejected        NUMERIC(19, 3) NOT NULL DEFAULT 0,
    rejection_reason         VARCHAR(500),
    batch_number             VARCHAR(100),
    expiry_date              DATE,
    unit_cost                NUMERIC(19, 4) NOT NULL,
    landed_unit_cost         NUMERIC(19, 4),
    allocated_charges        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    line_total               NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency                 VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by               UUID,
    updated_by               UUID,
    version                  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_grn_lines_number UNIQUE (grn_id, line_number),
    CONSTRAINT grn_lines_qty_check CHECK (quantity_received > 0),
    CONSTRAINT grn_lines_rejected_check CHECK (quantity_rejected >= 0),
    CONSTRAINT grn_lines_cost_check CHECK (unit_cost >= 0)
);

CREATE INDEX idx_grn_lines_product ON grn_lines (product_id);
CREATE INDEX idx_grn_lines_po_line ON grn_lines (purchase_order_line_id);

-- ---------------------------------------------------------------------------
-- supplier_invoices
--
-- Three-way matching lives here: the invoice is compared against what was ordered and what was
-- received, and the verdict is stored rather than recomputed, because it is a decision someone
-- may have made deliberately.
--
-- match_status: PENDING | MATCHED | WITHIN_TOLERANCE | EXCEPTION | DISPUTED | APPROVED_FOR_PAYMENT
-- An EXCEPTION never becomes payable by itself; a human either fixes it or accepts it with a
-- reason, and that acceptance is recorded.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_invoices (
    id                    UUID PRIMARY KEY,
    invoice_number        VARCHAR(50)    NOT NULL,
    supplier_id           UUID           NOT NULL REFERENCES suppliers (id),
    purchase_order_id     UUID           REFERENCES purchase_orders (id),
    grn_id                UUID           REFERENCES goods_received_notes (id),
    branch_id             UUID,
    invoice_date          DATE           NOT NULL,
    due_date              DATE,
    net_amount            NUMERIC(19, 4) NOT NULL,
    tax_amount            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_amount          NUMERIC(19, 4) NOT NULL,
    currency              VARCHAR(3)     NOT NULL DEFAULT 'KES',
    match_status          VARCHAR(25)    NOT NULL DEFAULT 'PENDING',
    -- The difference the match found: invoice minus what the receipt justifies.
    variance_amount       NUMERIC(19, 4),
    match_notes           VARCHAR(1000),
    matched_at            TIMESTAMPTZ,
    matched_by            UUID,
    -- Set only when someone knowingly accepts an exception.
    override_reason       VARCHAR(500),
    approved_for_payment_at TIMESTAMPTZ,
    approved_for_payment_by UUID,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_supplier_invoices UNIQUE (supplier_id, invoice_number),
    CONSTRAINT supplier_invoices_amounts_check CHECK (net_amount >= 0 AND tax_amount >= 0 AND total_amount >= 0)
);

CREATE INDEX idx_supplier_invoices_supplier ON supplier_invoices (supplier_id);
CREATE INDEX idx_supplier_invoices_status ON supplier_invoices (match_status);
CREATE INDEX idx_supplier_invoices_due
    ON supplier_invoices (due_date)
    WHERE match_status <> 'DISPUTED';

-- ---------------------------------------------------------------------------
-- supplier_returns
--
-- Goods going back: damaged on arrival, short-dated, or simply wrong. A return references the GRN
-- it reverses so the cost credited is the cost that was booked, not today's price.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_returns (
    id              UUID PRIMARY KEY,
    return_number   VARCHAR(30)    NOT NULL,
    supplier_id     UUID           NOT NULL REFERENCES suppliers (id),
    grn_id          UUID           REFERENCES goods_received_notes (id),
    branch_id       UUID           NOT NULL,
    -- DRAFT | SENT | CREDITED | CANCELLED
    status          VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    reason_code     VARCHAR(30)    NOT NULL,
    total_amount    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency        VARCHAR(3)     NOT NULL DEFAULT 'KES',
    sent_at         TIMESTAMPTZ,
    credited_at     TIMESTAMPTZ,
    credit_note_ref VARCHAR(50),
    notes           VARCHAR(1000),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_supplier_returns_number UNIQUE (return_number)
);

CREATE INDEX idx_supplier_returns_supplier ON supplier_returns (supplier_id);
CREATE INDEX idx_supplier_returns_branch_status ON supplier_returns (branch_id, status);

CREATE TABLE supplier_return_lines (
    id                 UUID PRIMARY KEY,
    supplier_return_id UUID           NOT NULL REFERENCES supplier_returns (id) ON DELETE CASCADE,
    line_number        INTEGER        NOT NULL,
    product_id         UUID           NOT NULL,
    sku                VARCHAR(50),
    product_name       VARCHAR(200),
    batch_number       VARCHAR(100),
    quantity           NUMERIC(19, 3) NOT NULL,
    unit_cost          NUMERIC(19, 4) NOT NULL,
    line_total         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_supplier_return_lines UNIQUE (supplier_return_id, line_number),
    CONSTRAINT supplier_return_lines_qty_check CHECK (quantity > 0)
);

CREATE INDEX idx_supplier_return_lines_product ON supplier_return_lines (product_id);

-- ---------------------------------------------------------------------------
-- reorder_suggestions
--
-- Built from inventory.low-stock events plus the preferred supplier's lead time and minimum order
-- quantity. Kept as rows rather than computed on request so a suggestion can be dismissed and stay
-- dismissed - a list that keeps re-proposing what someone already rejected gets ignored entirely.
-- ---------------------------------------------------------------------------
CREATE TABLE reorder_suggestions (
    id                  UUID PRIMARY KEY,
    product_id          UUID           NOT NULL,
    branch_id           UUID           NOT NULL,
    sku                 VARCHAR(50),
    product_name        VARCHAR(200),
    supplier_id         UUID           REFERENCES suppliers (id),
    quantity_on_hand    NUMERIC(19, 3) NOT NULL,
    reorder_point       NUMERIC(19, 3),
    suggested_quantity  NUMERIC(19, 3) NOT NULL,
    unit_cost           NUMERIC(19, 4),
    currency            VARCHAR(3)     NOT NULL DEFAULT 'KES',
    -- OPEN | ORDERED | DISMISSED
    status              VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    dismissed_reason    VARCHAR(500),
    purchase_order_id   UUID           REFERENCES purchase_orders (id),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0
);

-- One open suggestion per product per branch: a product that keeps emitting low-stock events
-- should sharpen the existing suggestion, not pile up duplicates.
CREATE UNIQUE INDEX uq_reorder_suggestions_open
    ON reorder_suggestions (product_id, branch_id)
    WHERE status = 'OPEN';

CREATE INDEX idx_reorder_suggestions_branch ON reorder_suggestions (branch_id, status);
