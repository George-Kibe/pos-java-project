package com.pos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.pos.messaging.idempotency.IdempotencyFilter;

class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                    .withBean(JdbcClient.class, () -> mock(JdbcClient.class));

    @Test
    void offUnlessAServiceAsksForIt() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void registeredAfterSecurityForTheApiWhenEnabled() {
        runner.withPropertyValues(
                        "pos.idempotency.enabled=true",
                        "pos.idempotency.excluded-paths=/api/v1/sales/sync")
                .run(
                        context -> {
                            FilterRegistrationBean<?> registration =
                                    context.getBean(FilterRegistrationBean.class);
                            assertThat(registration.getFilter())
                                    .isInstanceOf(IdempotencyFilter.class);
                            assertThat(registration.getUrlPatterns()).containsExactly("/api/v1/*");
                            // Security's chain sits at -100; keys must be scoped to a known caller.
                            assertThat(registration.getOrder()).isGreaterThan(-100);
                        });
    }
}
