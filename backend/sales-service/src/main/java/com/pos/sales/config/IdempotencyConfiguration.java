package com.pos.sales.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the idempotency filter after the security chain, so it knows who is asking. */
@Configuration(proxyBeanMethods = false)
public class IdempotencyConfiguration {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(JdbcClient jdbc) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyFilter(jdbc));
        // Spring Security's chain runs at -100. After it, the caller is authenticated, so keys are
        // scoped per user and an unauthenticated request never claims one.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }
}
