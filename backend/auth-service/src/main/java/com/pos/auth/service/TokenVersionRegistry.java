package com.pos.auth.service;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.pos.auth.domain.User;
import com.pos.auth.security.JwtProperties;
import com.pos.common.security.TokenVersionKeys;

import lombok.RequiredArgsConstructor;

/**
 * Publishes a user's current token version so the gateway can refuse older tokens.
 *
 * <p>Permissions are embedded in the access token, which is what lets every service authorize
 * without calling back here - but it also means an issued token cannot be withdrawn. Demote a
 * supervisor and their current token keeps {@code sale:void} until it expires. Publishing the new
 * version here is what closes that window.
 *
 * <p>The entry expires shortly after the access-token lifetime. Past that point no token carrying
 * the old version can still be valid, so the entry has nothing left to say - which keeps this cache
 * proportional to recent changes rather than to the number of users.
 *
 * <p>A failed write is logged, not thrown. Losing the publication costs at most the remaining life
 * of one token; failing the role change the administrator just made would be worse, and would leave
 * the database and the cache disagreeing about what was applied.
 */
@Service
@RequiredArgsConstructor
public class TokenVersionRegistry {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionRegistry.class);

    /** Covers the access-token lifetime plus the clock skew the gateway tolerates. */
    private static final Duration SKEW = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final JwtProperties jwtProperties;

    public void publish(User user) {
        publish(user.getId(), user.getTokenVersion());
    }

    public void publish(UUID userId, int tokenVersion) {
        Duration ttl = jwtProperties.getAccessTokenTtl().plus(SKEW);
        try {
            redis.opsForValue()
                    .set(TokenVersionKeys.forUser(userId), String.valueOf(tokenVersion), ttl);
        } catch (Exception e) {
            log.error(
                    "Could not publish token version {} for user {} ({}). The gateway will not"
                            + " refuse that user's existing tokens before they expire.",
                    tokenVersion,
                    userId,
                    e.getMessage());
        }
    }
}
