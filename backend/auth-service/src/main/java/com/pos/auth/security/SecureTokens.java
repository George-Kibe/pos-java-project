package com.pos.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of opaque tokens - refresh tokens and password reset tokens.
 *
 * <p>Hashed with SHA-256 rather than a slow hash such as Argon2, which is the right choice
 * <em>only</em> because these values are 256 bits of cryptographic randomness. There is no
 * dictionary to run against them, so key-stretching buys nothing, while the fast hash is needed to
 * look a token up on every refresh. Never use this for passwords, which are low-entropy and do need
 * stretching.
 */
public final class SecureTokens {

    private SecureTokens() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits

    /** A new opaque token, URL-safe so it survives being put in a link or a header. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A numeric one-time code of the requested length, uniformly distributed. */
    public static String generateNumericCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    /** Lowercase hex SHA-256, used as the stored form and the lookup key. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Constant-time comparison. A plain {@code equals} returns as soon as two bytes differ, which
     * leaks how much of a guess was correct.
     */
    public static boolean matches(String token, String expectedHash) {
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
