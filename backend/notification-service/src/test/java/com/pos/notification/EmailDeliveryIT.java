package com.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.events.auth.PasswordResetRequestedPayload;
import com.pos.events.auth.UserRegisteredPayload;
import com.pos.events.sales.ReceiptEmailRequestedPayload;
import com.pos.notification.service.ReceiptEmailModel;

/** Events in, email out, against a real SMTP server. */
class EmailDeliveryIT extends NotificationTestBase {

    private static final int SMTP_PORT = freePort();

    private static final GreenMail SMTP =
            new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    static {
        SMTP.start();
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.port", () -> SMTP_PORT);
    }

    @BeforeEach
    void emptyTheMailbox() throws com.icegreen.greenmail.store.FolderException {
        SMTP.purgeEmailFromAllMailboxes();
    }

    // --- the registration loop ------------------------------------------------

    @Test
    @DisplayName("an otp-requested event delivers the code by email")
    void otpRequestedDeliversTheCode() throws Exception {
        String code = "483920";
        EventEnvelope<OtpRequestedPayload> event =
                otpEvent("ada@example.com", "Ada Lovelace", code);

        publish(Topics.AUTH_OTP_REQUESTED, event, UUID.randomUUID());

        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();
        MimeMessage message = SMTP.getReceivedMessages()[0];

        assertThat(message.getAllRecipients()[0].toString()).contains("ada@example.com");
        assertThat(message.getSubject()).isEqualTo("Your Test Supermarket verification code");
        assertThat(GreenMailUtil.getBody(message)).contains(code);

        eventually(
                Duration.ofSeconds(15),
                "the delivery to be logged as SENT",
                () -> "SENT".equals(statusOf(event.eventId())));
    }

    @Test
    @DisplayName("the subject line never carries the code")
    void theSubjectNeverCarriesTheCode() throws Exception {
        String code = "119977";
        publish(
                Topics.AUTH_OTP_REQUESTED,
                otpEvent("lock@example.com", "Lock Screen", code),
                UUID.randomUUID());

        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();

        // Subjects show on lock screens, in notification previews, and in mail gateway logs.
        assertThat(SMTP.getReceivedMessages()[0].getSubject()).doesNotContain(code);
    }

    @Test
    @DisplayName("the code is never written to the delivery log")
    void theCodeIsNeverStoredInTheLog() {
        String code = "246813";
        EventEnvelope<OtpRequestedPayload> event = otpEvent("private@example.com", "Private", code);

        publish(Topics.AUTH_OTP_REQUESTED, event, UUID.randomUUID());
        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();

        eventually(
                Duration.ofSeconds(15),
                "the delivery to reach its terminal state",
                () -> "SENT".equals(statusOf(event.eventId())));

        // The whole row as text: a live credential must not appear in any column, now or after
        // someone adds one later.
        String row =
                jdbc.sql("SELECT notification_log::text FROM notification.notification_log")
                        .query(String.class)
                        .single();

        assertThat(row).doesNotContain(code);
        assertThat(row).contains("private@example.com").contains("SENT");
    }

    @Test
    @DisplayName("a message carries both an HTML and a plain-text body")
    void bothBodiesAreSent() throws Exception {
        String code = "555111";
        publish(
                Topics.AUTH_OTP_REQUESTED,
                otpEvent("multi@example.com", "Multi Part", code),
                UUID.randomUUID());

        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();
        MimeMessage message = SMTP.getReceivedMessages()[0];

        // A message with only an HTML part is more likely to be filtered as spam - fatal when the
        // content is a code somebody needs to sign in.
        assertThat(message.getContentType()).containsIgnoringCase("multipart/alternative");

        String body = GreenMailUtil.getBody(message);
        assertThat(body).contains("<!DOCTYPE html>");
        assertThat(body).contains("Use this code to verify your email address");
        assertThat(body).contains(code);
    }

    // --- idempotency ----------------------------------------------------------

    @Test
    @DisplayName("a redelivered event does not send a second email")
    void redeliveryDoesNotSendTwice() {
        EventEnvelope<OtpRequestedPayload> event =
                otpEvent("once@example.com", "Only Once", "777333");
        UUID key = UUID.randomUUID();

        publish(Topics.AUTH_OTP_REQUESTED, event, key);
        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();

        // The same event again: a rebalance, a retry, a redeployment mid-batch.
        publish(Topics.AUTH_OTP_REQUESTED, event, key);

        eventually(
                Duration.ofSeconds(10),
                "the duplicate to be processed and skipped",
                () -> {
                    Long processed =
                            jdbc.sql("SELECT count(*) FROM notification.processed_event")
                                    .query(Long.class)
                                    .single();
                    return processed != null && processed >= 1;
                });

        // Two codes in an inbox, only one of which works, is worse than none.
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
        assertThat(logCount()).isEqualTo(1);
    }

    // --- receipts -------------------------------------------------------------

    @Test
    @DisplayName("a receipt request emails the receipt once, however often it is delivered")
    void aReceiptIsEmailedOnce() throws Exception {
        EventEnvelope<ReceiptEmailRequestedPayload> event = receiptEvent();
        UUID saleId = event.payload().saleId();

        publish(Topics.SALES_RECEIPT_EMAIL_REQUESTED, event, saleId);
        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();
        MimeMessage message = SMTP.getReceivedMessages()[0];
        assertThat(message.getAllRecipients()[0].toString()).contains("someone@example.com");
        assertThat(message.getSubject()).isEqualTo("Your receipt from Test Supermarket");
        assertThat(GreenMailUtil.getBody(message))
                .contains("R-000000")
                .contains("218.20")
                .contains("0.735");

        // The same event again, then a second request from the lane on the same key. The key keeps
        // them in order, so once the second request's email arrives the duplicate has been seen.
        publish(Topics.SALES_RECEIPT_EMAIL_REQUESTED, event, saleId);
        publish(Topics.SALES_RECEIPT_EMAIL_REQUESTED, receiptEvent(), saleId);
        assertThat(SMTP.waitForIncomingEmail(20_000, 2)).isTrue();
        eventually(Duration.ofSeconds(15), "both deliveries to be logged", () -> logCount() == 2);
        assertThat(SMTP.getReceivedMessages()).hasSize(2);
    }

    private static EventEnvelope<ReceiptEmailRequestedPayload> receiptEvent() {
        return EventEnvelope.<ReceiptEmailRequestedPayload>builder()
                .topic(Topics.SALES_RECEIPT_EMAIL_REQUESTED)
                .correlationId("receipt-correlation")
                .payload(ReceiptEmailModel.sample())
                .build();
    }

    // --- the other two messages -----------------------------------------------

    @Test
    void userRegisteredDeliversAWelcome() throws Exception {
        EventEnvelope<UserRegisteredPayload> event =
                EventEnvelope.<UserRegisteredPayload>builder()
                        .topic(Topics.AUTH_USER_REGISTERED)
                        .correlationId("welcome-correlation")
                        .payload(
                                new UserRegisteredPayload(
                                        UUID.randomUUID(),
                                        "newcomer@example.com",
                                        "New Comer",
                                        List.of(),
                                        List.of()))
                        .build();

        publish(Topics.AUTH_USER_REGISTERED, event, UUID.randomUUID());

        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();
        MimeMessage message = SMTP.getReceivedMessages()[0];

        assertThat(message.getSubject()).isEqualTo("Welcome to Test Supermarket");
        // Sets the expectation that an unassigned account looks empty, so nobody reports it broken.
        assertThat(GreenMailUtil.getBody(message)).contains("assign your role");
    }

    @Test
    @DisplayName("a password reset delivers a working link, and the token stays out of the log")
    void passwordResetDeliversALink() throws Exception {
        String token = "reset-token-abc123";
        EventEnvelope<PasswordResetRequestedPayload> event =
                EventEnvelope.<PasswordResetRequestedPayload>builder()
                        .topic(Topics.AUTH_PASSWORD_RESET_REQUESTED)
                        .correlationId("reset-correlation")
                        .payload(
                                new PasswordResetRequestedPayload(
                                        UUID.randomUUID(),
                                        "forgot@example.com",
                                        "Forgot Password",
                                        token,
                                        Instant.now().plusSeconds(1800)))
                        .build();

        publish(Topics.AUTH_PASSWORD_RESET_REQUESTED, event, UUID.randomUUID());

        assertThat(SMTP.waitForIncomingEmail(20_000, 1)).isTrue();
        MimeMessage message = SMTP.getReceivedMessages()[0];

        assertThat(message.getSubject()).isEqualTo("Reset your Test Supermarket password");
        String body = GreenMailUtil.getBody(message);
        assertThat(body).contains("http://localhost:3000/reset-password?token=" + token);
        // The link is also written out, because some clients strip buttons.
        assertThat(body).contains("Or paste this into your browser");

        eventually(
                Duration.ofSeconds(15),
                "the delivery to be logged",
                () -> "SENT".equals(statusOf(event.eventId())));

        String row =
                jdbc.sql("SELECT notification_log::text FROM notification.notification_log")
                        .query(String.class)
                        .single();
        assertThat(row).doesNotContain(token);
    }

    // --- helpers --------------------------------------------------------------

    private static EventEnvelope<OtpRequestedPayload> otpEvent(
            String email, String name, String code) {
        return EventEnvelope.<OtpRequestedPayload>builder()
                .topic(Topics.AUTH_OTP_REQUESTED)
                .correlationId("otp-correlation")
                .payload(
                        new OtpRequestedPayload(
                                UUID.randomUUID(),
                                email,
                                name,
                                code,
                                OtpPurpose.REGISTRATION,
                                Instant.now().plusSeconds(600)))
                .build();
    }
}
