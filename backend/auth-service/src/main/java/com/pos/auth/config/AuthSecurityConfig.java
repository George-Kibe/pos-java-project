package com.pos.auth.config;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import com.pos.auth.domain.Permission;
import com.pos.auth.repository.PermissionRepository;
import com.pos.auth.security.AccessTokenIssuer;
import com.pos.auth.security.JwtKeyProvider;
import com.pos.auth.security.JwtProperties;

/** Key material, token encoding and password hashing. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class AuthSecurityConfig {

    @Bean
    public JwtKeyProvider jwtKeyProvider(JwtProperties properties) {
        return new JwtKeyProvider(properties);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(JwtKeyProvider keyProvider) {
        return new ImmutableJWKSet<>(keyProvider.jwkSet());
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public AccessTokenIssuer accessTokenIssuer(
            JwtEncoder encoder,
            JwtProperties properties,
            JwtKeyProvider keyProvider,
            PermissionRepository permissions) {
        // Queried lazily, per token, and only for a holder of the wildcard permission. Logins are
        // rare enough that this is cheaper than a cache that can go stale when a release adds a
        // permission.
        return new AccessTokenIssuer(
                encoder,
                properties,
                keyProvider.activeKey(),
                () ->
                        permissions.findAll().stream()
                                .map(Permission::getCode)
                                .collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * Verifies its own tokens against the in-memory key set rather than fetching its own JWKS over
     * HTTP. Other services do fetch, because they have no access to the private key; here the HTTP
     * round trip would only add a way for the service to fail to start.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, JwtProperties properties) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        // Spring's validators below handle expiry and issuer; the Nimbus default would duplicate
        // that work and report failures in a different shape.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    /**
     * Argon2id, the current recommendation for password hashing: memory-hard, so a GPU or ASIC
     * gains far less against it than against bcrypt or PBKDF2.
     *
     * <p>Wrapped in a delegating encoder so every stored hash carries its algorithm as a prefix.
     * That is what makes a future migration possible: hashes with the old prefix keep verifying
     * while new ones are written with the new algorithm, with no mass reset.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        String encodingId = "argon2";
        Map<String, PasswordEncoder> encoders =
                Map.of(
                        encodingId,
                        Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                        "bcrypt",
                        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder(encodingId, encoders);
    }

    /**
     * A second encoder for one-time codes. Codes are verified far more often than passwords are
     * set, and are already rate-limited and short-lived, so bcrypt's cost is the right trade here
     * where Argon2's memory cost would not be.
     */
    @Bean
    public PasswordEncoder otpEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
