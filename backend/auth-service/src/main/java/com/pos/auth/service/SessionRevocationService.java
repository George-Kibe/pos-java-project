package com.pos.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;

/**
 * Revokes refresh tokens in a transaction of its own.
 *
 * <p>This exists because of a subtle and dangerous failure. Refresh-token reuse is detected and
 * then answered with a 401, which means the caller throws - and a revocation performed in that same
 * transaction is rolled back with it. The result looks correct from outside (the request is
 * refused) while the compromised family stays live and the stolen token keeps working on the next
 * attempt. Reuse detection that rolls back is reuse detection that does nothing.
 *
 * <p>Committing separately makes the revocation stick regardless of how the request ends.
 */
@Service
@RequiredArgsConstructor
public class SessionRevocationService {

    private final RefreshTokenRepository refreshTokens;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, String reason) {
        return refreshTokens.revokeFamily(familyId, Instant.now(), reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(UUID userId, String reason) {
        return refreshTokens.revokeAllForUser(userId, Instant.now(), reason);
    }
}
