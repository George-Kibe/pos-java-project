package com.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.pos.notification.config.MailConfig;
import com.pos.notification.config.NotificationProperties;
import com.pos.notification.service.CapturingEmailSender;
import com.pos.notification.service.EmailMessage;
import com.pos.notification.service.SmtpEmailSender;

class CapturingEmailSenderTest {

    @TempDir Path directory;

    private static EmailMessage code(String recipient, String code) {
        return new EmailMessage(
                recipient,
                "Achieng",
                "Your verification code",
                "<p>" + code + "</p>",
                "Use this code:\n\n    " + code + "\n");
    }

    private List<Path> captured() throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".txt")).sorted().toList();
        }
    }

    @Test
    @DisplayName("a captured message keeps the recipient, subject and plain-text body a test reads")
    void aCapturedMessageCanBeReadBack() throws Exception {
        new CapturingEmailSender(directory).send(code("new@example.test", "482913"));

        assertThat(captured()).singleElement();
        String content = Files.readString(captured().getFirst());
        assertThat(content)
                .startsWith("To: new@example.test")
                .contains("Subject: Your verification code")
                .contains("    482913");
    }

    @Test
    @DisplayName(
            "each message gets its own file, the newest sorting last, and no partial file stays")
    void messagesDoNotOverwriteEachOther() throws Exception {
        CapturingEmailSender sender = new CapturingEmailSender(directory);
        sender.send(code("a@example.test", "111111"));
        sender.send(code("b@example.test", "222222"));

        assertThat(captured()).hasSize(2);
        assertThat(Files.readString(captured().getLast())).contains("b@example.test");
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(Path::toString)).noneMatch(name -> name.contains(".partial-"));
        }
    }

    @Test
    @DisplayName("SMTP unless capture is asked for by name")
    void theTransportIsSmtpUnlessCaptureIsChosen() {
        NotificationProperties properties = new NotificationProperties();
        MailConfig config = new MailConfig();

        assertThat(config.emailSender(new JavaMailSenderImpl(), properties, "pos@example.test"))
                .isInstanceOf(SmtpEmailSender.class);

        properties.setMailTransport(NotificationProperties.MailTransport.CAPTURE);
        properties.setCaptureDirectory(directory.toString());
        assertThat(config.emailSender(new JavaMailSenderImpl(), properties, "pos@example.test"))
                .isInstanceOf(CapturingEmailSender.class);
    }
}
