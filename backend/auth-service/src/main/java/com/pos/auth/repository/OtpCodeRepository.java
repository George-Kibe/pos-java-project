package com.pos.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.auth.domain.OtpCode;
import com.pos.events.auth.OtpPurpose;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    /** The code currently in play for this purpose, if any. */
    Optional<OtpCode> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
            UUID userId, OtpPurpose purpose);

    /**
     * Invalidates any outstanding codes before a new one is issued, so a resend genuinely replaces
     * the previous code rather than leaving two valid codes in circulation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE OtpCode c SET c.consumedAt = :now
            WHERE c.userId = :userId AND c.purpose = :purpose AND c.consumedAt IS NULL
            """)
    int consumeOutstanding(
            @Param("userId") UUID userId,
            @Param("purpose") OtpPurpose purpose,
            @Param("now") Instant now);
}
