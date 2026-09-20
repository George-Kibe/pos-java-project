package com.pos.common;

import jakarta.servlet.Servlet;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;

import com.pos.common.correlation.CorrelationIdFilter;
import com.pos.common.error.GlobalExceptionHandler;

/**
 * Wires the web cross-cutting concerns into any servlet service that puts common-lib on its
 * classpath: one error shape and one correlation id, with nothing to remember per service.
 *
 * <p>Everything is {@code @ConditionalOnMissingBean}, so a service that genuinely needs different
 * behaviour can define its own bean and override cleanly.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Servlet.class, DispatcherServlet.class})
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    /**
     * Registers the filter ahead of the security chain so that even a rejected request carries a
     * correlation id in its logs and its response.
     */
    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public org.springframework.boot.web.servlet.FilterRegistrationBean<CorrelationIdFilter>
            correlationIdFilterRegistration(CorrelationIdFilter filter) {
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
