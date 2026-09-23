package com.pos.notification.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Sends" by writing each message to a file, for browser end-to-end runs.
 *
 * <p>Those runs register throwaway accounts and need the emailed code back. Sending it through the
 * real provider would mail made-up addresses; this keeps it inside the container, where the test
 * reads it with {@code docker exec}. There is no endpoint: nothing outside the container can read
 * these files. Chosen only by {@code MAIL_TRANSPORT=capture}, which only the e2e overlay sets.
 *
 * <p>One file per message, named so the newest sorts last: a {@code To:} and {@code Subject:}
 * header, a blank line, then the plain-text body.
 */
public class CapturingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(CapturingEmailSender.class);

    private final Path directory;

    public CapturingEmailSender(Path directory) {
        this.directory = directory;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            Files.createDirectories(directory);
            String name = "%019d-%s.txt".formatted(System.currentTimeMillis(), UUID.randomUUID());
            String content =
                    "To: %s%nSubject: %s%n%n%s"
                            .formatted(message.recipient(), message.subject(), message.text());
            // Written aside and moved into place, so a reader never sees half a message.
            Path partial = Files.createTempFile(directory, ".partial-", ".tmp");
            Files.writeString(partial, content, StandardCharsets.UTF_8);
            Files.move(partial, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            // Recipient and subject only, as for SMTP: the body holds the code.
            log.info("Captured '{}' for {}", message.subject(), message.recipient());
        } catch (IOException e) {
            throw new EmailDeliveryException(
                    "Could not capture '%s' for %s"
                            .formatted(message.subject(), message.recipient()),
                    e);
        }
    }
}
