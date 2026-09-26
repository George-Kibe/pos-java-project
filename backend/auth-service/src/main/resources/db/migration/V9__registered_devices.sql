-- Registered devices: the tills and back-office computers staff may sign in from.
--
-- A manager registers a device by name and branch and is given a short enrolment code; the code,
-- typed on that device's sign-in page, makes it ACTIVE and gives the browser a secret it presents
-- with every sign-in. Where registration is required (production), a sign-in without an active
-- device's secret is refused. Codes and secrets are stored only as SHA-256 hashes.
--
-- A device is never deleted: revoking it ends every session started on it and keeps the record.
CREATE TABLE devices (
    id                   UUID PRIMARY KEY,
    branch_id            UUID         NOT NULL REFERENCES branches (id),
    name                 VARCHAR(80)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    enrolment_code_hash  VARCHAR(64),
    enrolment_expires_at TIMESTAMPTZ,
    secret_hash          VARCHAR(64),
    enrolled_at          TIMESTAMPTZ,
    last_seen_at         TIMESTAMPTZ,
    last_seen_ip         VARCHAR(45),
    revoked_at           TIMESTAMPTZ,
    revoked_reason       VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           UUID,
    updated_by           UUID,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_devices_secret UNIQUE (secret_hash),
    CONSTRAINT uq_devices_enrolment_code UNIQUE (enrolment_code_hash),
    CONSTRAINT ck_devices_status CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_devices_branch ON devices (branch_id, created_at DESC);

-- The device a session was started on, so revoking the device ends the session at its next
-- refresh. Null for sessions from before registration existed, or where it is not required.
ALTER TABLE refresh_tokens ADD COLUMN device_id UUID REFERENCES devices (id);
CREATE INDEX idx_refresh_tokens_device ON refresh_tokens (device_id) WHERE device_id IS NOT NULL;

-- device:view    see a branch's registered devices.
-- device:manage  register a device (issue its enrolment code) and revoke one.
-- Branch managers run their branches' devices; auditors may look. SUPER_ADMIN holds '*'.
INSERT INTO permissions (id, code, category, description) VALUES
    (gen_random_uuid(), 'device:view',   'devices', 'See the devices registered at a branch'),
    (gen_random_uuid(), 'device:manage', 'devices', 'Register and revoke the devices staff sign in from')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('device:view', 'device:manage')
WHERE r.code = 'BRANCH_MANAGER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'device:view'
WHERE r.code = 'AUDITOR'
ON CONFLICT DO NOTHING;
