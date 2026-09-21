package com.pos.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** The error handler must never be the thing that fails. */
class ProblemTypeUriTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a code that is not URI-safe still produces a problem response")
    void anUnsafeCodeDoesNotBreakTheHandler() {
        // A space here used to make URI.create throw inside the handler, so the resolver gave up
        // and the original 404 escaped the dispatcher as an unhandled 500 with no body.
        ProblemDetail problem =
                handler.handleApiException(
                        new Errors.NotFoundException("stock item.not_found", "nope"));

        assertThat(problem.getStatus()).isEqualTo(404);
        // The code still reaches the client verbatim; only the type URI falls back.
        assertThat(problem.getProperties()).containsEntry("code", "stock item.not_found");
        assertThat(problem.getType()).hasToString("https://docs.pos.local/problems/unspecified");
    }

    @Test
    void anOrdinaryCodeKeepsItsOwnType() {
        ProblemDetail problem =
                handler.handleApiException(Errors.NotFoundException.of("Stock item", "abc"));

        assertThat(problem.getType())
                .hasToString("https://docs.pos.local/problems/stock_item.not_found");
    }
}
