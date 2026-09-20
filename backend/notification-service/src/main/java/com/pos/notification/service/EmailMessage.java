package com.pos.notification.service;

/**
 * A rendered message, ready to send.
 *
 * <p>Both an HTML and a plain-text body. The plain-text part is not a courtesy: some mail clients
 * and most corporate gateways prefer or require it, and a message with only an HTML part is more
 * likely to be treated as spam - which for a one-time code means the person never gets in.
 */
public record EmailMessage(
        String recipient, String recipientName, String subject, String html, String text) {}
