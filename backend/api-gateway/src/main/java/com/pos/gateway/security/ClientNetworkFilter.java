package com.pos.gateway.security;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pos.common.correlation.CorrelationId;
import com.pos.gateway.config.GatewayClientAccessProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Refuses a request from outside the business's own networks - a branch or head office - before
 * anything else looks at it, sign-in included. With no networks configured it admits everyone.
 */
public class ClientNetworkFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientNetworkFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** An IP literal. Anything else is refused before IpAddressMatcher can look it up in DNS. */
    private static final Pattern IP_LITERAL =
            Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*)$");

    private final List<IpAddressMatcher> allowed;
    private final List<String> openPaths;
    private final ObjectMapper objectMapper;

    public ClientNetworkFilter(
            GatewayClientAccessProperties properties, ObjectMapper objectMapper) {
        this.allowed = properties.getAllowedNetworks().stream().map(IpAddressMatcher::new).toList();
        this.openPaths = List.copyOf(properties.getOpenPaths());
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the request as it arrived is judged. A forward to the circuit breaker's fallback is
        // the gateway talking to itself, and an open path's fallback must answer 503, not 403.
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        String path = request.getRequestURI();
        return allowed.isEmpty() || openPaths.stream().anyMatch(open -> MATCHER.match(open, path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        if (isAllowed(client)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn(
                "Refused {} {} from {}: not a branch or head office network",
                request.getMethod(),
                request.getRequestURI(),
                client);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN,
                        "The POS can only be used from a branch or head office network.");
        problem.setType(URI.create("https://docs.pos.local/problems/access.network_denied"));
        problem.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "access.network_denied");
        problem.setProperty("correlationId", CorrelationId.get());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private boolean isAllowed(String address) {
        // The valve passes on whatever the first untrusted X-Forwarded-For entry says, text
        // included.
        if (address == null || !IP_LITERAL.matcher(address).matches()) {
            return false;
        }
        try {
            return allowed.stream().anyMatch(network -> network.matches(address));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
