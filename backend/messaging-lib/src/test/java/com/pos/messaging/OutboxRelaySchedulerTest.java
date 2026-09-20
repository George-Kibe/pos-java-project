package com.pos.messaging;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.pos.messaging.outbox.OutboxPublisher;
import com.pos.messaging.outbox.OutboxRelayScheduler;

class OutboxRelaySchedulerTest {

    @Test
    void eachTickDrainsWhateverIsDue() {
        OutboxPublisher publisher = Mockito.mock(OutboxPublisher.class);
        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(publisher);

        scheduler.relay();
        scheduler.relay();

        verify(publisher, times(2)).publishDue();
    }
}
