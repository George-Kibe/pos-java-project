package com.pos.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorsTest {

    @Test
    void eachErrorCarriesTheStatusItsNameImplies() {
        assertThat(new Errors.NotFoundException("p.not_found", "x").status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(new Errors.ConflictException("p.conflict", "x").status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(new Errors.ForbiddenException("p.forbidden", "x").status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new Errors.UnauthorizedException("p.unauth", "x").status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(new Errors.BadRequestException("p.bad", "x").status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new Errors.BusinessRuleException("p.rule", "x").status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(new Errors.TooManyRequestsException("p.rate", "x").status())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void theNotFoundFactoryBuildsAConventionalCode() {
        Errors.NotFoundException ex = Errors.NotFoundException.of("Product", "SKU-1");

        assertThat(ex.code()).isEqualTo("product.not_found");
        assertThat(ex.getMessage()).isEqualTo("Product SKU-1 not found");
    }

    @Test
    void detailsAreCarriedAndCannotBeMutatedByCallers() {
        Errors.BusinessRuleException ex =
                new Errors.BusinessRuleException(
                        "sale.window", "outside window", Map.of("windowDays", 30));

        assertThat(ex.details()).containsEntry("windowDays", 30);
        assertThat(ex.details()).isUnmodifiable();
    }

    @Test
    void anErrorWithoutDetailsHasAnEmptyMap() {
        assertThat(new Errors.ConflictException("p.conflict", "x").details()).isEmpty();
    }
}
