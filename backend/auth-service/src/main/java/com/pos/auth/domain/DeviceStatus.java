package com.pos.auth.domain;

/** Where a registered device stands. An unused code past its expiry reads as expired. */
public enum DeviceStatus {
    /** Registered by a manager; waiting for its enrolment code to be typed on the device. */
    PENDING,
    /** Enrolled: staff may sign in from it. */
    ACTIVE,
    /** No longer trusted. Its sessions end and it cannot be reinstated - register it again. */
    REVOKED
}
