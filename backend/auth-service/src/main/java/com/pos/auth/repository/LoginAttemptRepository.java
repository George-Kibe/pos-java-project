package com.pos.auth.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.auth.domain.LoginAttempt;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    @Query(
            """
            SELECT count(a) FROM LoginAttempt a
            WHERE a.emailNormalized = :email AND a.successful = false AND a.attemptedAt > :since
            """)
    long countRecentFailures(@Param("email") String email, @Param("since") Instant since);

    @Query(
            """
            SELECT count(a) FROM LoginAttempt a
            WHERE a.ipAddress = :ip AND a.successful = false AND a.attemptedAt > :since
            """)
    long countRecentFailuresFromIp(@Param("ip") String ip, @Param("since") Instant since);
}
