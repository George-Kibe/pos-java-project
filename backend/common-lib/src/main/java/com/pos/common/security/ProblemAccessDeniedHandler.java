package com.pos.common.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.pos.common.correlation.CorrelationId;

import tools.jackson.databind.ObjectMapper;

/**
 * Renders a 403 as {@code application/problem+json}.
 *
 * <p>Does not name the missing permission. Telling a caller exactly which permission would have let
 * the request through maps out the authorization model for them.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
        problem.setType(URI.create("https://docs.pos.local/problems/auth.forbidden"));
        problem.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "auth.forbidden");
        problem.setProperty("correlationId", CorrelationId.get());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // The servlet default is ISO-8859-1, which mangles any non-ASCII text in a message.
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
