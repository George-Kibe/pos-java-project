package com.pos.notification.service;

import java.nio.charset.StandardCharsets;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends over SMTP.
 *
 * <p>The same code serves both environments: Gmail in development, AWS SES's SMTP interface in
 * production. Only configuration differs, so the path exercised in development is the path that
 * runs in production.
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();

            // Headers only; the body is assembled below.
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, UTF_8);
            helper.setFrom(from);
            helper.setSubject(message.subject());
            if (message.recipientName() != null && !message.recipientName().isBlank()) {
                helper.setTo(
                        new InternetAddress(message.recipient(), message.recipientName(), UTF_8));
            } else {
                helper.setTo(message.recipient());
            }

            mimeMessage.setContent(alternativeBody(message));
            mailSender.send(mimeMessage);

            // Recipient and subject only. The body holds the code.
            log.info("Sent '{}' to {}", message.subject(), message.recipient());

        } catch (Exception e) {
            throw new EmailDeliveryException(
                    "Could not send '%s' to %s".formatted(message.subject(), message.recipient()),
                    e);
        }
    }

    /**
     * Builds the body as {@code multipart/alternative} directly.
     *
     * <p>{@code MimeMessageHelper} in multipart mode wraps this in mixed/related, which is what you
     * want when there are attachments and needless structure when there are not - and some spam
     * filters treat the extra nesting on a message with no attachments as a small negative.
     *
     * <p>Order matters and is not arbitrary: in multipart/alternative the parts run from least to
     * most preferred, so the plain-text part goes first and the HTML second. Reverse them and every
     * capable client shows the plain-text version.
     */
    private MimeMultipart alternativeBody(EmailMessage message) throws Exception {
        MimeMultipart alternative = new MimeMultipart("alternative");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(message.text(), UTF_8);
        alternative.addBodyPart(textPart);

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(message.html(), "text/html; charset=" + UTF_8);
        alternative.addBodyPart(htmlPart);

        return alternative;
    }
}
