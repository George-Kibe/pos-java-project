package com.pos.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns the token's {@code perms} claim into Spring Security authorities, so
 * {@code @PreAuthorize("hasAuthority('sale:void')")} works directly against the permission model.
 *
 * <p>Roles are also mapped, prefixed {@code ROLE_}, but only so they can be displayed and audited.
 * Authorization decisions must be written against permissions: roles are editable at runtime, and a
 * check on a role name silently stops matching the moment the business renames one.
 */
public class PermissionsJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        for (String permission : claimAsStrings(jwt, JwtClaims.PERMISSIONS)) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        for (String role : claimAsStrings(jwt, JwtClaims.ROLES)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }

        String name = jwt.getClaimAsString(JwtClaims.USER_ID);
        return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
    }

    private static List<String> claimAsStrings(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }
}
