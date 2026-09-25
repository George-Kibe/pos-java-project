-- What a branch prints on its receipts around the sale itself: lines above (a slogan, opening
-- hours), lines below (returns policy, thanks), and the branch's address, phone and tax PIN. The
-- layout stays in code; only this text is the branch's to change. Printed receipts, reprints and
-- emailed receipts all carry it.
--
-- A new table only, so it is safe with the previous version still running.
CREATE TABLE receipt_settings (
    id          UUID PRIMARY KEY,
    branch_id   UUID         NOT NULL,
    header      VARCHAR(500),
    footer      VARCHAR(500),
    address     VARCHAR(300),
    phone       VARCHAR(30),
    tax_pin     VARCHAR(30),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_receipt_settings_branch UNIQUE (branch_id)
);
