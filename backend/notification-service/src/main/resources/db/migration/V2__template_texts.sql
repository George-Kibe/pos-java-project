-- The wording of each kind of email, where it has been changed from what ships: its subject, the
-- paragraph that opens it and the one that closes it. The layout, the code or link it carries and
-- the branding stay in the templates. No row means the shipped wording.
CREATE TABLE template_texts (
    id          UUID PRIMARY KEY,
    type        VARCHAR(40)   NOT NULL,
    subject     VARCHAR(200)  NOT NULL,
    intro       VARCHAR(1000),
    closing     VARCHAR(1000),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_template_texts_type UNIQUE (type)
);
