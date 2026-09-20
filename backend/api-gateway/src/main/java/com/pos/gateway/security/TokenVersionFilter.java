package com.pos.gateway.security;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.security.JwtClaims;
import com.pos.common.security.TokenVersionKeys;

import tools.jackson.databind.ObjectMapper;

/**
 * Rejects an access token whose version has been superseded.
 *
 * <p>This closes the one hole in stateless authorization. Permissions live inside the token so that
 * services never have to call back to auth-service, but that also means a token cannot be edited or
 * withdrawn once issued: demote a supervisor and their current token keeps {@code sale:void} until
 * it expires. Fifteen minutes is a short window, and for most changes it is tolerable - but not for
 * the ones that matter, which are exactly the ones done in a hurry because something is wrong.
 *
 * <p>So auth-service publishes the new version to Redis when it bumps one, and this filter refuses
 * anything older. One Redis lookup per authenticated request, against a key that only exists for
 * users whose version has actually changed recently.
 *
 * <p>Fails open. If Redis is unreachable the system degrades to the token's own expiry, which is
 * precisely the behaviour without this filter - undesirable, but far better than refusing every
 * request in the shop because a cache is down.
 */
public class TokenVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionFilter.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TokenVersionFilter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }

        if (isStale(jwt)) {
            SecurityContextHolder.clearContext();
            reject(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isStale(Jwt jwt) {
        String userId = jwt.getClaimAsString(JwtClaims.USER_ID);
        if (userId == null) {
            userId = jwt.getSubject();
        }
        Object versionClaim = jwt.getClaim(JwtClaims.TOKEN_VERSION);
        if (userId == null || !(versionClaim instanceof Number tokenVersion)) {
            // A token without a version predates this mechanism or is not ours to judge; the
            // signature and expiry checks have already passed, so let it through.
            return false;
        }

        try {
            String published =
                    redis.opsForValue().get(TokenVersionKeys.forUser(UUID.fromString(userId)));
            if (published == null) {
                // No bump on record. The common case.
                return false;
            }
            boolean stale = tokenVersion.intValue() < Integer.parseInt(published);
            if (stale) {
                log.info(
                        "Rejecting a superseded token for user {} (token v{}, current v{})",
                        userId,
                        tokenVersion.intValue(),
                        published);
            }
            return stale;
        } catch (IllegalArgumentException e) {
            // A malformed uid or version claim is not a reason to lock the caller out here; the
            // downstream service will reject it on its own terms.
            return false;
        } catch (Exception e) {
            log.warn(
                    "Token version check unavailable ({}); falling back to token expiry.",
                    e.getMessage());
            return false;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED,
                        "Your permissions have changed. Please sign in again.");
        problem.setType(URI.create("https://docs.pos.local/problems/auth.token_superseded"));
        problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        // A distinct code from auth.unauthenticated, so a client knows to refresh rather than
        // treating it as a lost session.
        problem.setProperty("code", "auth.token_superseded");
        problem.setProperty("correlationId", CorrelationId.get());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
