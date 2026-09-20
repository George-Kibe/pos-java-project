package com.pos.common.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.pos.common.correlation.CorrelationId;

import tools.jackson.databind.ObjectMapper;

/**
 * Renders a 401 as {@code application/problem+json}.
 *
 * <p>Security rejections happen in the filter chain, before any controller advice runs, so without
 * this a client would get Spring's default empty 401 and have to parse a second error shape.
 *
 * <p>The reason for the rejection is deliberately not disclosed. "Expired" versus "malformed"
 * versus "unknown signing key" is useful to an attacker probing the token format and useless to a
 * legitimate client, whose only correct response is to refresh or re-authenticate.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED, "Authentication is required");
        problem.setType(URI.create("https://docs.pos.local/problems/auth.unauthenticated"));
        problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "auth.unauthenticated");
        problem.setProperty("correlationId", CorrelationId.get());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
