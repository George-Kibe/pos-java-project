package com.pos.gateway.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.pos.common.security.PermissionsJwtAuthenticationConverter;
import com.pos.common.security.PosSecurityProperties;
import com.pos.gateway.ratelimit.RateLimitFilter;
import com.pos.gateway.ratelimit.RedisRateLimiter;
import com.pos.gateway.security.TokenVersionFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * The gateway's own filter chain, replacing the default one from common-lib because the gateway has
 * two extra concerns: rate limiting and token-version enforcement.
 *
 * <p>Both are placed after authentication and before authorization. That position is deliberate.
 * Rate limiting needs to know whether there is a principal in order to key on the user rather than
 * the IP, and the token-version check needs the verified token. Running them earlier would leave
 * both guessing; running them later would mean work is already done before a superseded token is
 * refused.
 */
@Configuration
@EnableConfigurationProperties({GatewayCorsProperties.class, PosSecurityProperties.class})
public class GatewaySecurityConfig {

    /** Propagates the correlation id to every downstream service. */
    @Bean
    public CorrelationIdPropagationFilter correlationIdPropagationFilter() {
        return new CorrelationIdPropagationFilter();
    }

    /** Stops the downstream service's echoed correlation header duplicating the gateway's own. */
    @Bean
    public CorrelationIdPropagationFilter.ResponseDeduplication correlationIdResponseFilter() {
        return new CorrelationIdPropagationFilter.ResponseDeduplication();
    }

    @Bean
    public RedisRateLimiter redisRateLimiter(StringRedisTemplate redis) {
        return new RedisRateLimiter(redis);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(
            RedisRateLimiter limiter,
            GatewayRateLimitProperties properties,
            ObjectMapper objectMapper) {
        return new RateLimitFilter(limiter, properties, objectMapper);
    }

    @Bean
    public TokenVersionFilter tokenVersionFilter(
            StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new TokenVersionFilter(redis, objectMapper);
    }

    /**
     * Validates tokens against auth-service's JWKS.
     *
     * <p>Nimbus caches the key set and refetches when it sees a {@code kid} it does not know, which
     * is what makes key rotation invisible here: the new key is picked up the first time a token
     * signed with it arrives.
     *
     * <p>A small clock skew is allowed because the gateway and auth-service are separate hosts, and
     * without it a token issued a moment ago can appear to be from the future.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value(
                            "${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
                    String jwkSetUri,
            @org.springframework.beans.factory.annotation.Value("${pos.gateway.jwt.issuer}")
                    String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(
                JwtValidators.createDefaultWithValidators(
                        new org.springframework.security.oauth2.jwt.JwtTimestampValidator(
                                Duration.ofSeconds(30)),
                        new org.springframework.security.oauth2.jwt.JwtIssuerValidator(issuer)));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(properties.getAllowedMethods());
        configuration.setAllowedHeaders(properties.getAllowedHeaders());
        configuration.setExposedHeaders(properties.getExposedHeaders());
        configuration.setAllowCredentials(properties.isAllowCredentials());
        configuration.setMaxAge(properties.getMaxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain gatewaySecurityFilterChain(
            HttpSecurity http,
            PosSecurityProperties securityProperties,
            CorsConfigurationSource corsConfigurationSource,
            PermissionsJwtAuthenticationConverter converter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            RateLimitFilter rateLimitFilter,
            TokenVersionFilter tokenVersionFilter)
            throws Exception {

        String[] publicPaths = securityProperties.getPublicPaths().toArray(String[]::new);

        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(
                        headers ->
                                headers
                                        // Only meaningful over HTTPS; harmless otherwise, and
                                        // production terminates TLS at Traefik in front of this.
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31536000))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                org.springframework.security.web
                                                                        .header.writers
                                                                        .ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(publicPaths)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class)
                .addFilterBefore(tokenVersionFilter, AuthorizationFilter.class);

        return http.build();
    }
}
