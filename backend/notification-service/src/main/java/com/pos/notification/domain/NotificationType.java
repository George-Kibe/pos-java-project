package com.pos.notification.domain;

/**
 * What a message is for. Determines the template and the subject.
 *
 * <p>A template arrives with the event that feeds it. Writing one ahead of its payload would mean
 * guessing, and a template that cannot be rendered from a real event has never been tested.
 */
public enum NotificationType {
    OTP_CODE,
    WELCOME,
    PASSWORD_RESET,
    /** A copy of a sale's receipt, asked for at the lane. */
    RECEIPT
}
