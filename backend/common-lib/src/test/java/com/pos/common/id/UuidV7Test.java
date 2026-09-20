package com.pos.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void carriesVersion7AndTheRfcVariant() {
        UUID id = UuidV7.randomUUID();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // binary 10, the RFC 9562 variant
    }

    @Test
    void encodesTheGenerationTimestamp() {
        long millis = Instant.parse("2026-09-20T10:15:30Z").toEpochMilli();
        UUID id = UuidV7.generate(millis);
        assertThat(UuidV7.timestampOf(id)).isEqualTo(Instant.ofEpochMilli(millis));
    }

    @Test
    void idsGeneratedInTimeOrderSortInTimeOrder() {
        // This is the whole point: sequential inserts append to the index instead of scattering.
        List<UUID> ids = new ArrayList<>();
        long base = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 50; i++) {
            ids.add(UuidV7.generate(base + i));
        }

        List<String> asGenerated = ids.stream().map(UUID::toString).toList();
        List<String> sorted = asGenerated.stream().sorted().toList();
        assertThat(sorted).isEqualTo(asGenerated);
    }

    @Test
    void doesNotCollideAcrossManyGenerations() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            ids.add(UuidV7.randomUUID());
        }
        assertThat(ids).hasSize(20_000);
    }

    @Test
    void readingTheTimestampOfANonV7UuidIsRejected() {
        assertThatThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a UUIDv7");
    }
}
