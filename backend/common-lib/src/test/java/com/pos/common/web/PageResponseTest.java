package com.pos.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void carriesTheNavigationFactsAClientNeeds() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 6);

        PageResponse<String> response = PageResponse.of(page);

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(6);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    void mapsEntitiesToDtosWithoutLeakingTheEntityType() {
        var page = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(0, 3), 3);

        PageResponse<String> response = PageResponse.of(page, i -> "n" + i);

        assertThat(response.content()).containsExactly("n1", "n2", "n3");
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    @Test
    void emptyIsAValidFirstAndLastPage() {
        PageResponse<String> response = PageResponse.empty();

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }
}
