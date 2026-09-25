package com.pos.common.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller, as asserted by a verified access token.
 *
 * <p>Read from the security context rather than from request parameters or headers: a caller must
 * never be able to state who they are, only prove it.
 */
public record AuthenticatedUser(
        UUID userId,
        String email,
        Set<String> roles,
        Set<String> permissions,
        Set<UUID> branchIds,
        int tokenVersion) {

    public static Optional<AuthenticatedUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(from(jwt));
    }

    /**
     * Who is acting, taken from the verified token; null when nobody is - a Kafka listener or a
     * scheduled job acts for the system.
     */
    public static UUID currentUserId() {
        return current().map(AuthenticatedUser::userId).orElse(null);
    }

    /** The caller, or an {@link IllegalStateException} if there is none. */
    public static AuthenticatedUser require() {
        return current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No authenticated user in context. An endpoint reached this"
                                                + " code without authentication - check the security"
                                                + " configuration."));
    }

    /**
     * The caller's verified token as an {@code Authorization} header value, for forwarding to a
     * service this one calls on the caller's behalf.
     *
     * <p>Taken from the security context rather than re-read from the request, so what is forwarded
     * is exactly what the resource server validated.
     */
    public static Optional<String> bearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of("Bearer " + jwt.getTokenValue());
    }

    public static AuthenticatedUser from(Jwt jwt) {
        String id = jwt.getClaimAsString(JwtClaims.USER_ID);
        if (id == null) {
            id = jwt.getSubject();
        }
        return new AuthenticatedUser(
                UUID.fromString(id),
                jwt.getClaimAsString(JwtClaims.EMAIL),
                Set.copyOf(stringList(jwt, JwtClaims.ROLES)),
                Set.copyOf(stringList(jwt, JwtClaims.PERMISSIONS)),
                stringList(jwt, JwtClaims.BRANCHES).stream()
                        .map(UUID::fromString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                jwt.getClaim(JwtClaims.TOKEN_VERSION) instanceof Number n ? n.intValue() : 0);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(Permissions.ALL) || permissions.contains(permission);
    }

    public boolean canAccessAllBranches() {
        return hasPermission(Permissions.BRANCH_ACCESS_ALL)
                || permissions.contains(Permissions.ALL);
    }

    public boolean canAccessBranch(UUID branchId) {
        return canAccessAllBranches() || branchIds.contains(branchId);
    }

    private static List<String> stringList(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
