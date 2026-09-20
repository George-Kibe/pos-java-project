package com.pos.notification.domain;

/**
 * What a message is for. Determines the template and the subject.
 *
 * <p>Templates for sales receipts and stock alerts arrive with the services that produce those
 * events. Writing them now would mean guessing at payloads that have not been designed yet, and a
 * template that cannot be rendered from a real event is a template that has never been tested.
 */
public enum NotificationType {
    OTP_CODE,
    WELCOME,
    PASSWORD_RESET
}
