package com.pos.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.pos.common.persistence.AuthenticatedAuditorAware;

class AuthenticatedUserTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID BRANCH = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(Jwt jwt) {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(USER.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
    }

    @Test
    void readsIdentityAndScopeFromTheToken() {
        authenticateWith(
                token().claim(JwtClaims.USER_ID, USER.toString())
                        .claim(JwtClaims.EMAIL, "ada@example.com")
                        .claim(JwtClaims.ROLES, List.of("CASHIER"))
                        .claim(JwtClaims.PERMISSIONS, List.of("sale:create"))
                        .claim(JwtClaims.BRANCHES, List.of(BRANCH.toString()))
                        .claim(JwtClaims.TOKEN_VERSION, 3)
                        .build());

        AuthenticatedUser user = AuthenticatedUser.require();

        assertThat(user.userId()).isEqualTo(USER);
        assertThat(user.email()).isEqualTo("ada@example.com");
        assertThat(user.roles()).containsExactly("CASHIER");
        assertThat(user.permissions()).containsExactly("sale:create");
        assertThat(user.branchIds()).containsExactly(BRANCH);
        assertThat(user.tokenVersion()).isEqualTo(3);
    }

    @Test
    void theWildcardPermissionSatisfiesEveryCheck() {
        authenticateWith(
                token().claim(JwtClaims.USER_ID, USER.toString())
                        .claim(JwtClaims.PERMISSIONS, List.of(Permissions.ALL))
                        .build());

        AuthenticatedUser admin = AuthenticatedUser.require();
        assertThat(admin.hasPermission("anything:at:all")).isTrue();
        assertThat(admin.canAccessAllBranches()).isTrue();
        assertThat(admin.canAccessBranch(UUID.randomUUID())).isTrue();
    }

    @Test
    void branchAccessIsLimitedToAssignedBranches() {
        authenticateWith(
                token().claim(JwtClaims.USER_ID, USER.toString())
                        .claim(JwtClaims.PERMISSIONS, List.of("sale:create"))
                        .claim(JwtClaims.BRANCHES, List.of(BRANCH.toString()))
                        .build());

        AuthenticatedUser user = AuthenticatedUser.require();
        assertThat(user.canAccessBranch(BRANCH)).isTrue();
        assertThat(user.canAccessBranch(UUID.randomUUID())).isFalse();
        assertThat(user.hasPermission("sale:void")).isFalse();
    }

    @Test
    void branchAccessAllPermissionBypassesTheAssignmentList() {
        authenticateWith(
                token().claim(JwtClaims.USER_ID, USER.toString())
                        .claim(JwtClaims.PERMISSIONS, List.of(Permissions.BRANCH_ACCESS_ALL))
                        .build());

        assertThat(AuthenticatedUser.require().canAccessBranch(UUID.randomUUID())).isTrue();
    }

    @Test
    void thereIsNoUserWhenNothingIsAuthenticated() {
        assertThat(AuthenticatedUser.current()).isEmpty();
        assertThatThrownBy(AuthenticatedUser::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authenticated user");
    }

    @Test
    void theForwardedTokenIsTheOneThatWasVerified() {
        assertThat(AuthenticatedUser.bearerToken()).isEmpty();

        authenticateWith(token().claim(JwtClaims.USER_ID, USER.toString()).build());
        assertThat(AuthenticatedUser.bearerToken()).contains("Bearer t");

        // Anything but a JWT has no token to forward.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("someone", "creds", List.of()));
        assertThat(AuthenticatedUser.bearerToken()).isEmpty();
    }

    @Test
    void aNonJwtAuthenticationIsNotTreatedAsAUser() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("someone", "creds", List.of()));

        assertThat(AuthenticatedUser.current()).isEmpty();
    }

    @Test
    void theAuditorIsTheAuthenticatedUserAndEmptyForBackgroundWork() {
        AuthenticatedAuditorAware auditor = new AuthenticatedAuditorAware();

        // Background work - an outbox relay or a scheduled scan - has no user.
        assertThat(auditor.getCurrentAuditor()).isEmpty();

        authenticateWith(token().claim(JwtClaims.USER_ID, USER.toString()).build());
        assertThat(auditor.getCurrentAuditor()).contains(USER);
    }
}
