package com.pos.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class PermissionsJwtAuthenticationConverterTest {

    private final PermissionsJwtAuthenticationConverter converter =
            new PermissionsJwtAuthenticationConverter();

    private static Jwt.Builder token(UUID userId) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900));
    }

    @Test
    void permissionsBecomeAuthoritiesVerbatimSoPreAuthorizeCanUseThem() {
        UUID userId = UUID.randomUUID();
        Jwt jwt =
                token(userId)
                        .claim(JwtClaims.USER_ID, userId.toString())
                        .claim(JwtClaims.PERMISSIONS, List.of("sale:create", "sale:void"))
                        .build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains("sale:create", "sale:void");
        assertThat(auth.getName()).isEqualTo(userId.toString());
    }

    @Test
    void rolesAreMappedWithThePrefixButAreForDisplayAndAuditOnly() {
        Jwt jwt =
                token(UUID.randomUUID())
                        .claim(JwtClaims.ROLES, List.of("CASHIER"))
                        .claim(JwtClaims.PERMISSIONS, List.of("sale:create"))
                        .build();

        assertThat(
                        converter.convert(jwt).getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority))
                .contains("ROLE_CASHIER", "sale:create");
    }

    @Test
    void aTokenWithNoClaimsYieldsNoAuthoritiesRatherThanFailing() {
        UUID userId = UUID.randomUUID();
        AbstractAuthenticationToken auth = converter.convert(token(userId).build());

        assertThat(auth.getAuthorities()).isEmpty();
        // Falls back to the subject when uid is absent.
        assertThat(auth.getName()).isEqualTo(userId.toString());
    }

    @Test
    void blankPermissionEntriesAreIgnored() {
        Jwt jwt =
                token(UUID.randomUUID())
                        .claim(JwtClaims.PERMISSIONS, List.of("sale:create", "", "  "))
                        .build();

        assertThat(converter.convert(jwt).getAuthorities()).hasSize(1);
    }
}
