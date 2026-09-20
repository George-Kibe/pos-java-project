package com.pos.common.security;

import java.util.UUID;

/**
 * The Redis key under which a user's current token version is published.
 *
 * <p>This is the contract that closes an otherwise unavoidable gap in stateless authorization.
 * Permissions are baked into the access token so that every service can authorize locally without
 * calling back to auth-service - but that also means an issued token cannot be edited or withdrawn.
 * Demote someone, and their existing token keeps its old permissions until it expires.
 *
 * <p>So auth-service publishes the new version here whenever it bumps one, and the gateway rejects
 * any token carrying an older version. The entry is written with a TTL slightly longer than the
 * access-token lifetime, because after that no token bearing the old version can still be valid and
 * the entry has nothing left to say. That keeps this cache small and self-cleaning rather than
 * growing a row per user forever.
 *
 * <p>A missing entry means "no version bump on record", which is the common case and is allowed. If
 * Redis is unavailable the gateway degrades to the token's own expiry - the same behaviour as
 * having no enforcement at all, rather than locking every user out.
 */
public final class TokenVersionKeys {

    private TokenVersionKeys() {}

    private static final String PREFIX = "pos:auth:tv:";

    public static String forUser(UUID userId) {
        return PREFIX + userId;
    }
}
