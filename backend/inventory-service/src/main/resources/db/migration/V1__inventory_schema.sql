-- inventory-service schema.
--
-- The movement ledger is the source of truth. Every change to stock writes an append-only row
-- saying what moved, why, and who caused it; stock_items.quantity_on_hand is a cache derived from
-- those rows, kept for speed because a till cannot sum a year of movements per scan.
--
-- That split is deliberate. A single mutable quantity column answers "how much is there" and
-- nothing else - not "why is it wrong", which is the question actually asked when a count does not
-- match. With a ledger the two can be compared, and a reconciliation that disagrees is itself the
-- alarm.

-- ---------------------------------------------------------------------------
-- stock_items: one row per product per branch.
--
-- product_id is a reference into catalog's schema and deliberately not a foreign key: services do
-- not share tables, so the constraint cannot exist and must not be faked. Product metadata arrives
-- on catalog.product-changed and is cached locally.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_items (
    id                 UUID PRIMARY KEY,
    product_id         UUID           NOT NULL,
    branch_id          UUID           NOT NULL,
    -- Cached product details, so a stock report does not need a call to catalog per line.
    sku                VARCHAR(50),
    product_name       VARCHAR(200),
    unit_of_measure    VARCHAR(20),
    -- Derived from the ledger. Never the only record of a change.
    quantity_on_hand   NUMERIC(19, 3) NOT NULL DEFAULT 0,
    -- Held for open carts. Does not reduce on-hand; it reduces what may be promised.
    quantity_reserved  NUMERIC(19, 3) NOT NULL DEFAULT 0,
    reorder_point      NUMERIC(19, 3),
    reorder_quantity   NUMERIC(19, 3),
    last_movement_at   TIMESTAMPTZ,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_items_product_branch UNIQUE (product_id, branch_id),
    CONSTRAINT stock_items_reserved_check CHECK (quantity_reserved >= 0)
);

CREATE INDEX idx_stock_items_branch ON stock_items (branch_id);
-- Supports the low-stock sweep without scanning every row.
CREATE INDEX idx_stock_items_low
    ON stock_items (branch_id, product_id)
    WHERE reorder_point IS NOT NULL;

-- ---------------------------------------------------------------------------
-- stock_batches
--
-- Stock is held in batches, not as one number, because expiry belongs to a delivery rather than to
-- a product: the milk received on Monday and the milk received on Thursday are the same product
-- and must not be sold in the wrong order. Unit cost is per batch for the same reason - the margin
-- on a sale depends on what that particular delivery cost.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_batches (
    id             UUID PRIMARY KEY,
    stock_item_id  UUID           NOT NULL REFERENCES stock_items (id) ON DELETE CASCADE,
    batch_number   VARCHAR(100)   NOT NULL,
    -- A calendar day, because that is what is stamped on the carton.
    expiry_date    DATE,
    quantity       NUMERIC(19, 3) NOT NULL DEFAULT 0,
    unit_cost      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'KES',
    received_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    status         VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    -- The GRN or adjustment this batch came from.
    source_type    VARCHAR(30),
    source_id      UUID,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT stock_batches_status_check CHECK (status IN ('ACTIVE', 'DEPLETED', 'WRITTEN_OFF')),
    CONSTRAINT stock_batches_quantity_check CHECK (quantity >= 0)
);

-- The FEFO index: soonest expiry first, then oldest received. NULLS LAST so a batch with no
-- expiry is used only once dated stock is gone - it can wait, perishables cannot.
CREATE INDEX idx_stock_batches_fefo
    ON stock_batches (stock_item_id, expiry_date ASC NULLS LAST, received_at ASC)
    WHERE status = 'ACTIVE' AND quantity > 0;

-- Supports the near-expiry sweep across all branches.
CREATE INDEX idx_stock_batches_expiry
    ON stock_batches (expiry_date)
    WHERE status = 'ACTIVE' AND quantity > 0 AND expiry_date IS NOT NULL;

-- ---------------------------------------------------------------------------
-- stock_movements: the ledger. Append-only by convention - nothing in this service updates or
-- deletes a row here. A correction is a new movement in the opposite direction, so the history of
-- what was believed and when survives.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_movements (
    id              UUID PRIMARY KEY,
    stock_item_id   UUID           NOT NULL REFERENCES stock_items (id) ON DELETE CASCADE,
    batch_id        UUID REFERENCES stock_batches (id) ON DELETE SET NULL,
    branch_id       UUID           NOT NULL,
    product_id      UUID           NOT NULL,
    type            VARCHAR(30)    NOT NULL,
    -- Signed: negative takes stock away, positive puts it on. Summing the column for an item
    -- gives its on-hand quantity, which is what the reconciliation check does.
    quantity        NUMERIC(19, 3) NOT NULL,
    unit_cost       NUMERIC(19, 4),
    currency        VARCHAR(3),
    reason_code     VARCHAR(50),
    -- What caused this: a sale, a GRN, an adjustment, a transfer.
    reference_type  VARCHAR(50),
    reference_id    UUID,
    actor_id        UUID,
    correlation_id  VARCHAR(64),
    occurred_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT stock_movements_type_check CHECK (
        type IN ('RECEIPT', 'SALE', 'RETURN', 'ADJUSTMENT', 'WRITE_OFF',
                 'TRANSFER_OUT', 'TRANSFER_IN', 'STOCK_TAKE', 'OPENING_BALANCE')
    ),
    CONSTRAINT stock_movements_quantity_check CHECK (quantity <> 0)
);

CREATE INDEX idx_stock_movements_item_time ON stock_movements (stock_item_id, occurred_at DESC);
CREATE INDEX idx_stock_movements_reference ON stock_movements (reference_type, reference_id);
CREATE INDEX idx_stock_movements_branch_time ON stock_movements (branch_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- stock_reservations
--
-- A held quantity for an open cart. Does not move stock: the goods are still on the shelf, they
-- are simply not promised to anyone else. Reservations expire so an abandoned cart cannot hold
-- stock out of sale indefinitely.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_reservations (
    id             UUID PRIMARY KEY,
    stock_item_id  UUID           NOT NULL REFERENCES stock_items (id) ON DELETE CASCADE,
    quantity       NUMERIC(19, 3) NOT NULL,
    reference_type VARCHAR(50)    NOT NULL,
    reference_id   UUID           NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'HELD',
    expires_at     TIMESTAMPTZ    NOT NULL,
    released_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT stock_reservations_status_check CHECK (status IN ('HELD', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT stock_reservations_quantity_check CHECK (quantity > 0)
);

CREATE INDEX idx_stock_reservations_reference ON stock_reservations (reference_type, reference_id);
CREATE INDEX idx_stock_reservations_due ON stock_reservations (expires_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------------------
-- Adjustments: a deliberate correction, always with a reason. The reason code is what makes
-- shrinkage analysable - "stock went down by 40" is useless, "40 written off as damage in
-- butchery" is a management report.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_adjustments (
    id           UUID PRIMARY KEY,
    branch_id    UUID         NOT NULL,
    reason_code  VARCHAR(50)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    notes        TEXT,
    posted_at    TIMESTAMPTZ,
    posted_by    UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT stock_adjustments_status_check CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    CONSTRAINT stock_adjustments_reason_check CHECK (
        reason_code IN ('DAMAGE', 'EXPIRY', 'THEFT', 'COUNT_CORRECTION',
                        'SUPPLIER_RETURN', 'SAMPLE', 'OTHER')
    )
);

CREATE INDEX idx_stock_adjustments_branch ON stock_adjustments (branch_id, created_at DESC);

CREATE TABLE stock_adjustment_lines (
    id             UUID PRIMARY KEY,
    adjustment_id  UUID           NOT NULL REFERENCES stock_adjustments (id) ON DELETE CASCADE,
    stock_item_id  UUID           NOT NULL REFERENCES stock_items (id),
    batch_id       UUID REFERENCES stock_batches (id),
    -- Signed, like a movement.
    quantity_delta NUMERIC(19, 3) NOT NULL,
    notes          VARCHAR(255),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT stock_adjustment_lines_delta_check CHECK (quantity_delta <> 0)
);

CREATE INDEX idx_stock_adjustment_lines_adjustment ON stock_adjustment_lines (adjustment_id);

-- ---------------------------------------------------------------------------
-- Transfers: stock leaves one branch before it arrives at the other, so there is a period where
-- it belongs to neither shelf. Modelling that in-transit state is the difference between a van
-- full of stock being visible and it simply disappearing for a day.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_transfers (
    id              UUID PRIMARY KEY,
    reference       VARCHAR(50)  NOT NULL,
    from_branch_id  UUID         NOT NULL,
    to_branch_id    UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    notes           TEXT,
    dispatched_at   TIMESTAMPTZ,
    dispatched_by   UUID,
    received_at     TIMESTAMPTZ,
    received_by     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_transfers_reference UNIQUE (reference),
    CONSTRAINT stock_transfers_status_check CHECK (
        status IN ('DRAFT', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')
    ),
    CONSTRAINT stock_transfers_branches_differ CHECK (from_branch_id <> to_branch_id)
);

CREATE TABLE stock_transfer_lines (
    id                  UUID PRIMARY KEY,
    transfer_id         UUID           NOT NULL REFERENCES stock_transfers (id) ON DELETE CASCADE,
    product_id          UUID           NOT NULL,
    sku                 VARCHAR(50),
    quantity_sent       NUMERIC(19, 3) NOT NULL,
    -- May differ from what was sent: shortages are found at the receiving end, and the difference
    -- is a real event rather than an error to hide.
    quantity_received   NUMERIC(19, 3),
    batch_number        VARCHAR(100),
    expiry_date         DATE,
    unit_cost           NUMERIC(19, 4),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT stock_transfer_lines_sent_check CHECK (quantity_sent > 0)
);

CREATE INDEX idx_stock_transfer_lines_transfer ON stock_transfer_lines (transfer_id);

-- ---------------------------------------------------------------------------
-- Stock takes: snapshot what the system believes, count what is actually there, review the
-- differences, then post them as adjustments. The snapshot is taken at the start and kept, so the
-- variance is measured against what was believed at counting time rather than against a number
-- that moved while the counting was going on.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_takes (
    id           UUID PRIMARY KEY,
    reference    VARCHAR(50)  NOT NULL,
    branch_id    UUID         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    notes        TEXT,
    snapshot_at  TIMESTAMPTZ,
    posted_at    TIMESTAMPTZ,
    posted_by    UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_takes_reference UNIQUE (reference),
    CONSTRAINT stock_takes_status_check CHECK (
        status IN ('OPEN', 'COUNTING', 'REVIEW', 'POSTED', 'CANCELLED')
    )
);

CREATE INDEX idx_stock_takes_branch ON stock_takes (branch_id, created_at DESC);

CREATE TABLE stock_take_lines (
    id                UUID PRIMARY KEY,
    stock_take_id     UUID           NOT NULL REFERENCES stock_takes (id) ON DELETE CASCADE,
    stock_item_id     UUID           NOT NULL REFERENCES stock_items (id),
    product_id        UUID           NOT NULL,
    sku               VARCHAR(50),
    -- What the system believed when counting began.
    snapshot_quantity NUMERIC(19, 3) NOT NULL,
    -- What was actually on the shelf. Null until somebody counts it.
    counted_quantity  NUMERIC(19, 3),
    counted_at        TIMESTAMPTZ,
    counted_by        UUID,
    notes             VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_take_lines UNIQUE (stock_take_id, stock_item_id)
);

CREATE INDEX idx_stock_take_lines_take ON stock_take_lines (stock_take_id);
