package com.pos.messaging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pos.messaging.idempotency.IdempotencyFilter;
import com.pos.messaging.idempotency.IdempotencyProperties;

/**
 * Registers the {@code Idempotency-Key} filter in a service that asks for it.
 *
 * <p>Conditional on the property alone, not on a {@code JdbcClient} bean: a missing client should
 * fail the service at startup, not quietly leave it without retry protection.
 */
@AutoConfiguration(after = MessagingAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnProperty(prefix = "pos.idempotency", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            JdbcClient jdbc, IdempotencyProperties properties) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(
                        new IdempotencyFilter(jdbc, properties.getExcludedPaths()));
        // Spring Security's chain runs at -100. After it, the caller is authenticated, so keys are
        // scoped per user and an unauthenticated request never claims one.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }
}
