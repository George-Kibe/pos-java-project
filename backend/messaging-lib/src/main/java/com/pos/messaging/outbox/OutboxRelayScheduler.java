package com.pos.messaging.outbox;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives {@link OutboxPublisher} on a fixed delay.
 *
 * <p>Separate from the publisher so the publishing logic can be called directly in tests without
 * waiting for a scheduler tick, and so a service that publishes on demand can switch the schedule
 * off with {@code pos.outbox.enabled=false} while still using the recorder.
 */
public class OutboxRelayScheduler {

    private final OutboxPublisher publisher;

    public OutboxRelayScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${pos.outbox.poll-interval:PT1S}",
            initialDelayString = "${pos.outbox.poll-interval:PT1S}")
    public void relay() {
        publisher.publishDue();
    }
}
