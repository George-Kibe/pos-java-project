package com.pos.notification.config;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.pos.notification.service.CapturingEmailSender;
import com.pos.notification.service.EmailSender;
import com.pos.notification.service.SmtpEmailSender;

/** Wires the sender: SMTP, or - for a browser test run only - capture to files. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public EmailSender emailSender(
            JavaMailSender mailSender,
            NotificationProperties properties,
            @Value("${pos.notification.mail-from}") String from) {
        if (properties.getMailTransport() == NotificationProperties.MailTransport.CAPTURE) {
            Path directory = Path.of(properties.getCaptureDirectory());
            // Loud on purpose: in this mode nobody receives anything.
            log.warn(
                    "MAIL_TRANSPORT=capture: no email will be sent. Messages are written to {}."
                            + " This is for end-to-end test runs only.",
                    directory);
            return new CapturingEmailSender(directory);
        }
        return new SmtpEmailSender(mailSender, from);
    }
}
