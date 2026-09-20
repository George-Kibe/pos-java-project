package com.pos.notification.domain;

/** Where a message got to. */
public enum NotificationStatus {
    /** Accepted for delivery; the send has not completed. */
    PENDING,
    /** The SMTP server accepted it. Not the same as "the person read it". */
    SENT,
    /** This attempt failed. Kafka will redeliver, so it may still succeed. */
    FAILED,
    /**
     * Retries are exhausted and the event went to the dead-letter topic. Nothing further will
     * happen automatically. This is alertable: a person never received something they were waiting
     * for.
     */
    PERMANENTLY_FAILED
}
