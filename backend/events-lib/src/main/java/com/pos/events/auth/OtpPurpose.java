package com.pos.events.auth;

/** Why a one-time code was issued. Drives which email template is rendered. */
public enum OtpPurpose {
    REGISTRATION,
    PASSWORD_RESET,
    EMAIL_CHANGE
}
