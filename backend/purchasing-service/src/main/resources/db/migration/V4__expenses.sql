-- What running the shops costs, beyond the goods: rent, wages, power. With the margin on what was
-- sold, it is what turns gross profit into net profit.
--
-- An expense is never deleted. A mistake is voided, with a reason, and entered again; a large one
-- waits for someone other than the person who recorded it before it counts.
--
-- New tables: safe with the previous version still running.

-- One row: the amount above which an expense needs a second person's approval.
CREATE TABLE expense_settings (
    id              UUID PRIMARY KEY,
    approval_limit  NUMERIC(19, 4) NOT NULL CHECK (approval_limit >= 0),
    currency        VARCHAR(3)     NOT NULL DEFAULT 'KES',
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT         NOT NULL DEFAULT 0
);

-- A starting point, changed under Settings by whoever holds settings:manage.
INSERT INTO expense_settings (id, approval_limit)
VALUES ('01920000-0000-7000-8000-000000000e01', 10000);

CREATE TABLE expenses (
    id              UUID PRIMARY KEY,
    expense_number  VARCHAR(30)    NOT NULL,
    -- NULL is head office: spent for the business as a whole, not for one branch.
    branch_id       UUID,
    category        VARCHAR(30)    NOT NULL,
    description     VARCHAR(300)   NOT NULL,
    payee           VARCHAR(200),
    -- The receipt or invoice it was paid against.
    reference       VARCHAR(100),
    -- The business day it belongs to, in the shop's time zone.
    incurred_on     DATE           NOT NULL,
    -- Without VAT; the VAT is reclaimable and kept beside it.
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    tax_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    currency        VARCHAR(3)     NOT NULL DEFAULT 'KES',
    status          VARCHAR(20)    NOT NULL,
    recorded_by     UUID           NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL,
    needs_approval  BOOLEAN        NOT NULL,
    decided_by      UUID,
    decided_at      TIMESTAMPTZ,
    -- Why it was rejected or voided.
    reason          VARCHAR(500),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_expenses_number UNIQUE (expense_number),
    CONSTRAINT expenses_category_check CHECK (category IN
        ('RENT', 'WAGES', 'ELECTRICITY', 'WATER', 'TRANSPORT', 'SECURITY', 'REPAIRS',
         'COMMUNICATION', 'LICENCES', 'BANK_CHARGES', 'OTHER')),
    CONSTRAINT expenses_status_check
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'VOIDED')),
    CONSTRAINT expenses_reason_check
        CHECK (status NOT IN ('REJECTED', 'VOIDED') OR reason IS NOT NULL),
    -- Whoever decides is someone else.
    CONSTRAINT expenses_decider_check CHECK (decided_by IS NULL OR decided_by <> recorded_by
        OR status = 'VOIDED')
);

CREATE INDEX idx_expenses_branch ON expenses (branch_id, incurred_on DESC);
CREATE INDEX idx_expenses_pending ON expenses (status) WHERE status = 'PENDING_APPROVAL';
