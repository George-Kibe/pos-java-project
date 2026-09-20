-- auth-service schema. Lives in the `auth` schema; the service's DB role has rights there only.
-- Shared messaging tables (outbox, processed_event) come from messaging-lib's V0_001.

-- ---------------------------------------------------------------------------
-- branches: the business has one company and many outlets. Every transactional
-- record elsewhere in the system carries a branch_id, and users are scoped to
-- the branches they may act in.
-- ---------------------------------------------------------------------------
CREATE TABLE branches (
    id          UUID PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'Africa/Nairobi',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_branches_code UNIQUE (code)
);

-- ---------------------------------------------------------------------------
-- users
--
-- email_normalized exists because email comparison must be case-insensitive and
-- whitespace-insensitive, but the address the user typed is worth keeping for
-- display and for sending mail. Uniqueness is enforced on the normalized form,
-- so Ada@Example.com and ada@example.com cannot both register.
--
-- token_version is bumped whenever a password or role changes. Access tokens
-- carry it, so every token issued before the change is stale.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                    UUID PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL,
    email_normalized      VARCHAR(255) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    full_name             VARCHAR(150) NOT NULL,
    phone                 VARCHAR(30),
    status                VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    token_version         INTEGER      NOT NULL DEFAULT 1,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email_normalized UNIQUE (email_normalized),
    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')
    )
);

-- ---------------------------------------------------------------------------
-- roles and permissions
--
-- Authorization is always checked against a permission, never a role name, so
-- that the business can invent a role at runtime without a deployment. Roles
-- are simply editable bundles of permissions.
--
-- system_role marks the seeded roles: they may have permissions added or
-- removed, but they cannot be deleted, because deleting SUPER_ADMIN would lock
-- everyone out permanently.
-- ---------------------------------------------------------------------------
CREATE TABLE permissions (
    id          UUID PRIMARY KEY,
    code        VARCHAR(100) NOT NULL,
    category    VARCHAR(50)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE roles (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    system_role BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_branches (
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    branch_id   UUID NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID,
    PRIMARY KEY (user_id, branch_id)
);

-- ---------------------------------------------------------------------------
-- refresh_tokens
--
-- Stored as SHA-256 of the opaque token, never the token itself: a database
-- dump must not hand an attacker working credentials. SHA-256 rather than a
-- slow hash because lookup is by hash on every refresh, and the token is 256
-- bits of entropy, so there is nothing to brute force.
--
-- family_id links every token descended from one login. Rotation issues a new
-- token in the same family and marks the old one used. If a token that has
-- already been used is presented again, the only two explanations are a stolen
-- token being replayed or a client bug - either way the entire family is
-- revoked and the user must log in again. That is what makes theft of a
-- refresh token survivable.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id      UUID         NOT NULL,
    token_hash     VARCHAR(64)  NOT NULL,
    issued_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    used_at        TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(100),
    replaced_by    UUID,
    ip_address     VARCHAR(45),
    user_agent     VARCHAR(255),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
-- Supports pruning expired tokens.
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------------------
-- otp_codes
--
-- The code is hashed, single-use and short-lived, and attempts are capped, so a
-- six-digit code cannot be guessed within its lifetime. Rows are kept after use
-- for audit and to enforce the resend cooldown.
-- ---------------------------------------------------------------------------
CREATE TABLE otp_codes (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose      VARCHAR(30) NOT NULL,
    code_hash    VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    max_attempts INTEGER     NOT NULL DEFAULT 5,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT otp_codes_purpose_check CHECK (
        purpose IN ('REGISTRATION', 'PASSWORD_RESET', 'EMAIL_CHANGE')
    )
);

CREATE INDEX idx_otp_codes_user_purpose ON otp_codes (user_id, purpose, created_at DESC);

-- ---------------------------------------------------------------------------
-- password_reset_tokens: a reset link carries a long random token rather than a
-- six-digit code, because a link is clicked rather than typed and there is no
-- reason to make it guessable.
-- ---------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_password_reset_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_user ON password_reset_tokens (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- login_attempts: drives lockout and gives an investigator the history of who
-- tried what from where. Recorded for unknown addresses too, otherwise the
-- table itself would reveal which addresses are registered.
-- ---------------------------------------------------------------------------
CREATE TABLE login_attempts (
    id               UUID PRIMARY KEY,
    email_normalized VARCHAR(255) NOT NULL,
    ip_address       VARCHAR(45),
    successful       BOOLEAN      NOT NULL,
    failure_reason   VARCHAR(100),
    user_agent       VARCHAR(255),
    attempted_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_attempts_email_time ON login_attempts (email_normalized, attempted_at DESC);
CREATE INDEX idx_login_attempts_ip_time ON login_attempts (ip_address, attempted_at DESC);

-- ---------------------------------------------------------------------------
-- audit_log: every privileged action. Append-only by convention; nothing in the
-- application ever updates or deletes a row here.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id             UUID PRIMARY KEY,
    actor_id       UUID,
    actor_email    VARCHAR(255),
    action         VARCHAR(100) NOT NULL,
    resource_type  VARCHAR(100),
    resource_id    VARCHAR(100),
    branch_id      UUID,
    correlation_id VARCHAR(64),
    details        JSONB,
    ip_address     VARCHAR(45),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_actor_time ON audit_log (actor_id, created_at DESC);
CREATE INDEX idx_audit_log_action_time ON audit_log (action, created_at DESC);
CREATE INDEX idx_audit_log_resource ON audit_log (resource_type, resource_id);
