package com.pos.auth.security;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.nimbusds.jose.jwk.RSAKey;

import com.pos.auth.domain.User;
import com.pos.common.security.JwtClaims;
import com.pos.common.security.Permissions;

/**
 * Mints access tokens.
 *
 * <p>Permissions are embedded in the token rather than looked up per request, which is what lets
 * every other service authorize locally without calling back here. The cost of that is a token that
 * cannot be revoked mid-life, which is why the lifetime is short and why {@code tv} is carried: a
 * password or role change bumps the version, so a stale token is recognisable even before it
 * expires.
 */
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final RSAKey activeKey;
    private final Supplier<Set<String>> allPermissions;

    public AccessTokenIssuer(
            JwtEncoder encoder,
            JwtProperties properties,
            RSAKey activeKey,
            Supplier<Set<String>> allPermissions) {
        this.encoder = encoder;
        this.properties = properties;
        this.activeKey = activeKey;
        this.allPermissions = allPermissions;
    }

    public record IssuedToken(String value, Instant expiresAt, String jwtId) {}

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTokenTtl());
        String jwtId = UUID.randomUUID().toString();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.getIssuer())
                        .subject(user.getId().toString())
                        .issuedAt(now)
                        .expiresAt(expiresAt)
                        .id(jwtId)
                        .claim(JwtClaims.USER_ID, user.getId().toString())
                        .claim(JwtClaims.EMAIL, user.getEmail())
                        .claim(JwtClaims.ROLES, List.copyOf(user.roleCodes()))
                        .claim(JwtClaims.PERMISSIONS, List.copyOf(expand(user.permissionCodes())))
                        .claim(
                                JwtClaims.BRANCHES,
                                user.branchIds().stream().map(UUID::toString).toList())
                        .claim(JwtClaims.TOKEN_VERSION, user.getTokenVersion())
                        .build();

        // The kid lets a verifier pick the right key while several are published during rotation.
        JwsHeader header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(activeKey.getKeyID()).build();

        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt, jwtId);
    }

    /**
     * A supervisor's approval: a token for the supervisor, good for one permission at one branch
     * for a couple of minutes, naming the user whose lane asked for it.
     *
     * <p>Scoped this narrowly so that what a PIN unlocks is exactly the action it was entered for.
     * The services treat it like any other token, which is the point: the supervisor is the caller,
     * so the audit trail names the person who approved, not the cashier who asked.
     */
    public IssuedToken issueApproval(
            User approver,
            String permission,
            UUID branchId,
            UUID actingFor,
            java.time.Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String jwtId = UUID.randomUUID().toString();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.getIssuer())
                        .subject(approver.getId().toString())
                        .issuedAt(now)
                        .expiresAt(expiresAt)
                        .id(jwtId)
                        .claim(JwtClaims.USER_ID, approver.getId().toString())
                        .claim(JwtClaims.EMAIL, approver.getEmail())
                        .claim(JwtClaims.ROLES, List.of())
                        .claim(JwtClaims.PERMISSIONS, List.of(permission))
                        .claim(JwtClaims.BRANCHES, List.of(branchId.toString()))
                        .claim(JwtClaims.TOKEN_VERSION, approver.getTokenVersion())
                        .claim(JwtClaims.ACTING_FOR, actingFor.toString())
                        .claim(JwtClaims.APPROVAL, true)
                        .build();
        JwsHeader header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(activeKey.getKeyID()).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt, jwtId);
    }

    /** Whether {@code user} holds {@code permission}, the wildcard included. */
    public static boolean holds(User user, String permission) {
        Set<String> held = user.permissionCodes();
        return held.contains(permission) || held.contains(Permissions.ALL);
    }

    /**
     * Expands the wildcard permission into the concrete list.
     *
     * <p>Necessary because authorization is expressed as {@code hasAuthority('role:manage')}, and
     * Spring Security compares authority strings for equality - it has no notion that {@code *}
     * subsumes everything. Without this expansion a SUPER_ADMIN holding only {@code *} would be
     * refused by every endpoint in the system.
     *
     * <p>Done at issue time rather than by teaching every check about the wildcard: the resulting
     * token then states plainly what it can do, which is easier to reason about and to debug, and
     * no service has to special-case it.
     */
    /**
     * What {@code user} may actually do: their roles' permissions, with the wildcard expanded into
     * every concrete one - the same list their access token carries.
     */
    public Set<String> effectivePermissions(User user) {
        return Set.copyOf(expand(user.permissionCodes()));
    }

    private Set<String> expand(Set<String> permissions) {
        if (!permissions.contains(Permissions.ALL)) {
            return permissions;
        }
        Set<String> expanded = new java.util.HashSet<>(allPermissions.get());
        // Kept so branch-wide access and any future wildcard-aware check still work.
        expanded.add(Permissions.ALL);
        return expanded;
    }
}
