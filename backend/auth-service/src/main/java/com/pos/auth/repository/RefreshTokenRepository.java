package com.pos.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.auth.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    /**
     * Revokes every token in a rotation family. Called when a used token is replayed, which means
     * the family is compromised, and on logout.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE RefreshToken t
            SET t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("now") Instant now,
            @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE RefreshToken t
            SET t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.userId = :userId AND t.revokedAt IS NULL
            """)
    int revokeAllForUser(
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE RefreshToken t
            SET t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.deviceId = :deviceId AND t.revokedAt IS NULL
            """)
    int revokeAllForDevice(
            @Param("deviceId") UUID deviceId,
            @Param("now") Instant now,
            @Param("reason") String reason);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
