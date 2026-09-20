package com.pos.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.pos.notification.service.EmailSender;
import com.pos.notification.service.SmtpEmailSender;

/** Wires the SMTP sender. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class MailConfig {

    @Bean
    public EmailSender emailSender(
            JavaMailSender mailSender, @Value("${pos.notification.mail-from}") String from) {
        return new SmtpEmailSender(mailSender, from);
    }
}
