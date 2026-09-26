package com.pos.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DeviceCodeTest {

    @Test
    void aCodeIsEightCharactersNoOneMisreads() {
        String code = DeviceService.newCode();
        assertThat(code).hasSize(8).matches("[A-HJ-NP-Z2-9]+");
    }

    @Test
    void codesDoNotRepeat() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            codes.add(DeviceService.newCode());
        }
        assertThat(codes).hasSize(1_000);
    }

    @Test
    void aCodeIsShownInTwoHalves() {
        assertThat(DeviceService.format("ABCD2345")).isEqualTo("ABCD-2345");
    }

    @Test
    void caseSpacesAndDashesDoNotMatterWhenTyped() {
        assertThat(DeviceService.normalize(" abcd-2345 ")).isEqualTo("ABCD2345");
        assertThat(DeviceService.normalize("ABCD 2345")).isEqualTo("ABCD2345");
        assertThat(DeviceService.normalize(null)).isEmpty();
    }
}
