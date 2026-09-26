package com.pos.gateway.ratelimit;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pos.common.correlation.CorrelationId;
import com.pos.gateway.config.GatewayRateLimitProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Applies the rate limit for the request's traffic class.
 *
 * <p>Runs after authentication and before authorization, which is what lets one filter serve both
 * cases: an authenticated request has a principal to key on, and a credential endpoint does not
 * yet, so it is keyed by IP.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RedisRateLimiter limiter;
    private final GatewayRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RedisRateLimiter limiter,
            GatewayRateLimitProperties properties,
            ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Probes must answer even while the system is shedding load, or an orchestrator will
        // conclude the gateway is dead and restart it in the middle of an overload.
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String userId = authenticatedUserId();

        GatewayRateLimitProperties.Bucket bucket;
        String key;

        if (isCredentialEndpoint(path)) {
            bucket = properties.getAuth();
            key = "pos:rl:auth:" + clientIp(request);
        } else if (isProviderCallback(path)) {
            bucket = properties.getProviderCallbacks();
            key = "pos:rl:callback:" + clientIp(request);
        } else if (userId != null) {
            bucket = properties.getAuthenticated();
            key = "pos:rl:user:" + userId;
        } else {
            bucket = properties.getAnonymous();
            key = "pos:rl:ip:" + clientIp(request);
        }

        RedisRateLimiter.Decision decision =
                limiter.tryConsume(key, bucket.getCapacity(), bucket.getWindow());

        response.setHeader("X-RateLimit-Limit", String.valueOf(bucket.getCapacity()));
        response.setHeader(
                "X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, decision.retryAfter().toSeconds());
        log.warn("Rate limit exceeded for {} on {}", key, path);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many requests. Try again in %d second(s)."
                                .formatted(retryAfterSeconds));
        problem.setType(URI.create("https://docs.pos.local/problems/rate_limit.exceeded"));
        problem.setTitle(HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        problem.setInstance(URI.create(path));
        problem.setProperty("code", "rate_limit.exceeded");
        problem.setProperty("correlationId", CorrelationId.get());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        // Without Retry-After a well-behaved client has no way to back off correctly and will
        // usually retry immediately, making the overload worse.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private boolean isProviderCallback(String path) {
        return properties.getProviderCallbackPaths().stream()
                .anyMatch(pattern -> MATCHER.match(pattern, path));
    }

    private boolean isCredentialEndpoint(String path) {
        return properties.getAuthPaths().stream()
                .anyMatch(pattern -> MATCHER.match(pattern, path) || pattern.equals(path));
    }

    private static String authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt) {
            String uid = jwt.getClaimAsString("uid");
            return uid != null ? uid : jwt.getSubject();
        }
        return null;
    }

    /**
     * The originating client, as Tomcat's RemoteIpValve found it: X-Forwarded-For read from the
     * right, past our own proxies. Behind Traefik and the web app every request would otherwise
     * come from a proxy, collapsing every per-IP limit into one bucket; and the leftmost entry,
     * which the client writes itself, would let anyone pick a fresh bucket per attempt.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
