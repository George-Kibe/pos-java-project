-- notification-service schema.
--
-- The log records that a message was sent, to whom, and what happened - never what was in it.
-- OTP codes and reset tokens pass through this service, and a table that kept the body would be a
-- store of live credentials sitting outside auth-service, readable by anyone with database access
-- and retained long after the code expired. The subject line is kept; the body is not.

CREATE TABLE notification_log (
    id                UUID PRIMARY KEY,
    -- The event that caused this message, so a delivery can be traced back to its trigger.
    event_id          VARCHAR(64),
    event_type        VARCHAR(100),
    type              VARCHAR(50)  NOT NULL,
    channel           VARCHAR(20)  NOT NULL DEFAULT 'EMAIL',
    recipient         VARCHAR(320) NOT NULL,
    subject           VARCHAR(255),
    status            VARCHAR(30)  NOT NULL,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT,
    correlation_id    VARCHAR(64),
    sent_at           TIMESTAMPTZ,
    failed_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT notification_log_status_check CHECK (
        status IN ('PENDING', 'SENT', 'FAILED', 'PERMANENTLY_FAILED')
    )
);

-- One row per event and message type. An event retried four times is one message with four
-- attempts, not four messages, and the count is what an operator reads to judge whether a
-- provider is struggling. NULLs are distinct in a Postgres unique index, so the rows written for
-- a dead-lettered message that could not even be parsed are unaffected.
CREATE UNIQUE INDEX uq_notification_log_event_type
    ON notification_log (event_id, type)
    WHERE event_id IS NOT NULL;

-- Supports "what happened to the message for this event", the usual support question.
CREATE INDEX idx_notification_log_event ON notification_log (event_id);
CREATE INDEX idx_notification_log_recipient_time ON notification_log (recipient, created_at DESC);
-- Supports the alert on permanent failures, which must never be silent.
CREATE INDEX idx_notification_log_failed
    ON notification_log (created_at DESC)
    WHERE status = 'PERMANENTLY_FAILED';
