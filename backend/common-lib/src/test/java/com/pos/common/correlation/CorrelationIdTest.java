package com.pos.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CorrelationIdTest {

    @AfterEach
    void tearDown() {
        CorrelationId.clear();
    }

    @Test
    void setAndGetRoundTrip() {
        CorrelationId.set("abc-123");
        assertThat(CorrelationId.get()).isEqualTo("abc-123");
    }

    @Test
    void settingBlankClearsRatherThanStoringEmptiness() {
        CorrelationId.set("abc-123");
        CorrelationId.set("   ");
        assertThat(CorrelationId.get()).isNull();

        CorrelationId.set("abc-123");
        CorrelationId.set(null);
        assertThat(CorrelationId.get()).isNull();
    }

    @Test
    void getOrCreateGeneratesOnceAndThenReuses() {
        String first = CorrelationId.getOrCreate();
        assertThat(first).isNotBlank();
        assertThat(CorrelationId.getOrCreate()).isEqualTo(first);
    }

    @Test
    void withRestoresThePreviousValueAfterwards() {
        CorrelationId.set("outer");

        CorrelationId.with("inner", () -> assertThat(CorrelationId.get()).isEqualTo("inner"));

        assertThat(CorrelationId.get()).isEqualTo("outer");
    }

    @Test
    void withRestoresEvenWhenTheTaskThrows() {
        CorrelationId.set("outer");
        try {
            CorrelationId.with(
                    "inner",
                    () -> {
                        throw new IllegalStateException("boom");
                    });
        } catch (IllegalStateException expected) {
            // asserted below
        }
        assertThat(CorrelationId.get()).isEqualTo("outer");
    }

    @Test
    void generatedIdsAreUniqueAndFilterSafe() {
        assertThat(CorrelationId.generate())
                .isNotEqualTo(CorrelationId.generate())
                .matches("[A-Za-z0-9._-]+");
    }
}
