package com.pos.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.pos.common.error.SecurityExceptionHandler;
import com.pos.common.security.BranchAccessGuard;
import com.pos.common.security.PermissionsJwtAuthenticationConverter;
import com.pos.common.security.PosSecurityProperties;
import com.pos.common.security.ProblemAccessDeniedHandler;
import com.pos.common.security.ProblemAuthenticationEntryPoint;

import tools.jackson.databind.ObjectMapper;

/**
 * Makes every service an OAuth2 resource server that validates the access token itself, against
 * auth-service's JWKS.
 *
 * <p>The gateway also validates, but a service sitting on an internal network must not be trivially
 * callable by anything that reaches that network. Defence in depth: both check.
 *
 * <p>Three things are deliberate here. The chain denies by default, so an endpoint added without an
 * authorization rule is unreachable rather than open. Sessions are stateless, because a token is
 * the whole story and a session would reintroduce CSRF and sticky-routing concerns. And CSRF
 * protection is off precisely because there is no cookie-borne authority at this layer: browsers
 * talk to the Next.js BFF, which holds the cookie, and the BFF talks here with a bearer token.
 */
@AutoConfiguration
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class})
@ConditionalOnProperty(
        prefix = "pos.security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(PosSecurityProperties.class)
@EnableMethodSecurity
public class PosResourceServerAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(PosResourceServerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public PermissionsJwtAuthenticationConverter permissionsJwtAuthenticationConverter() {
        return new PermissionsJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper objectMapper) {
        return new ProblemAccessDeniedHandler(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public BranchAccessGuard branchAccessGuard() {
        return new BranchAccessGuard();
    }

    /** Catches @PreAuthorize denials, which are thrown past the security filter chain. */
    @Bean
    @ConditionalOnMissingBean
    public SecurityExceptionHandler securityExceptionHandler() {
        return new SecurityExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain posSecurityFilterChain(
            HttpSecurity http,
            PosSecurityProperties properties,
            PermissionsJwtAuthenticationConverter converter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {

        String[] publicPaths = properties.getPublicPaths().toArray(String[]::new);
        // Logged at startup on purpose: the unauthenticated surface is the thing most worth
        // noticing when it changes, and it is otherwise invisible until something is exploited.
        log.info(
                "Security chain active; {} public path(s): {}",
                publicPaths.length,
                String.join(", ", publicPaths));

        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
                                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}
