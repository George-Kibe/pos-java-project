-- Supervisor PINs: a short code a supervisor enters at a lane to approve one action - a price
-- override, a void, a refund - without signing the cashier out or typing a password at a shared
-- terminal. Hashed like a password; locked after repeated failures, separately from the login
-- lockout, so a mistyped PIN never locks anyone out of signing in.
ALTER TABLE users
    ADD COLUMN pin_hash            VARCHAR(100),
    ADD COLUMN pin_set_at          TIMESTAMPTZ,
    ADD COLUMN pin_failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until    TIMESTAMPTZ;
