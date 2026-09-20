package com.pos.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TopicsTest {

    @Test
    void eventTypeIsDerivedFromTheTopicNameSoTheyCannotDrift() {
        assertThat(Topics.eventTypeOf(Topics.AUTH_OTP_REQUESTED)).isEqualTo("auth.otp-requested");
        assertThat(Topics.eventTypeOf(Topics.SALES_SALE_COMPLETED))
                .isEqualTo("sales.sale-completed");
        assertThat(Topics.eventTypeOf(Topics.INVENTORY_NEGATIVE_STOCK_DETECTED))
                .isEqualTo("inventory.negative-stock-detected");
    }

    @Test
    void schemaVersionIsReadFromTheTopicSuffix() {
        assertThat(Topics.schemaVersionOf(Topics.AUTH_OTP_REQUESTED)).isEqualTo(1);
        assertThat(Topics.schemaVersionOf("pos.sales.sale-completed.v12")).isEqualTo(12);
    }

    @Test
    void aTopicWithoutAVersionSuffixIsRejected() {
        assertThatThrownBy(() -> Topics.schemaVersionOf("pos.sales.sale-completed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no version suffix");
    }

    @Test
    void deadLetterTopicIsTheTopicPlusSuffix() {
        assertThat(Topics.dlt(Topics.SALES_SALE_COMPLETED))
                .isEqualTo("pos.sales.sale-completed.v1.dlt");
    }

    @Test
    void deadLetteringADeadLetterTopicIsRejected() {
        assertThatThrownBy(() -> Topics.dlt(Topics.dlt(Topics.SALES_SALE_COMPLETED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already a dead-letter topic");
    }

    @Test
    void blankTopicsAreRejectedEverywhere() {
        assertThatThrownBy(() -> Topics.dlt("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Topics.eventTypeOf(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Topics.schemaVersionOf(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
