package com.pos.common.error;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.pos.common.correlation.CorrelationId;

/**
 * Handles authorization failures raised <em>inside</em> the application, as opposed to those caught
 * by the security filter chain.
 *
 * <p>This is easy to miss and expensive to miss. A {@code @PreAuthorize} denial on a controller
 * method throws {@link AccessDeniedException} from the method-security interceptor, which is past
 * the filter chain, so {@code ProblemAccessDeniedHandler} never sees it. Without this advice the
 * catch-all {@code Exception} handler answers instead and every permission denial becomes a 500 -
 * misleading to clients and noisy in the error budget.
 *
 * <p>Ordered ahead of {@link GlobalExceptionHandler} so the specific handlers win.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionHandler.class);

    private static final String TYPE_PREFIX = "https://docs.pos.local/problems/";

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        // An unauthenticated caller gets 401, not 403: they may well succeed after signing in,
        // and 403 would wrongly tell them the answer is no regardless of who they are.
        if (!isAuthenticated()) {
            return unauthorized();
        }
        log.warn("Access denied: {}", ex.getMessage());
        return problem(
                HttpStatus.FORBIDDEN,
                "auth.forbidden",
                "You do not have permission to perform this action");
    }

    @ExceptionHandler({
        AuthenticationException.class,
        AuthenticationCredentialsNotFoundException.class
    })
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return unauthorized();
    }

    private static ProblemDetail unauthorized() {
        return problem(
                HttpStatus.UNAUTHORIZED, "auth.unauthenticated", "Authentication is required");
    }

    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationId.get());
        return problem;
    }
}
