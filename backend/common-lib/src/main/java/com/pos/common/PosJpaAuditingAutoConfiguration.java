package com.pos.common;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.pos.common.persistence.AuthenticatedAuditorAware;

/**
 * Turns on JPA auditing for any service with JPA on the classpath, so {@link
 * com.pos.common.persistence.BaseEntity} fills its audit columns without per-service setup.
 */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass({EntityManager.class, EnableJpaAuditing.class})
// Having JPA on the classpath is not enough: a module may depend on common-lib for BaseEntity
// while running without a datasource at all. Auditing is only wired once a real
// EntityManagerFactory exists, otherwise @EnableJpaAuditing fails on an empty JPA metamodel.
@ConditionalOnBean(EntityManagerFactory.class)
@ConditionalOnMissingBean(name = "jpaAuditingHandler")
@EnableJpaAuditing(auditorAwareRef = "authenticatedAuditorAware")
public class PosJpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditorAware<UUID> authenticatedAuditorAware() {
        return new AuthenticatedAuditorAware();
    }
}
