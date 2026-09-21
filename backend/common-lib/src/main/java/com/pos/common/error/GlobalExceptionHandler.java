package com.pos.common.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pos.common.correlation.CorrelationId;

/**
 * Turns every exception into one RFC 7807 {@code application/problem+json} shape, so a client
 * parses errors once rather than once per endpoint.
 *
 * <p>Two rules hold throughout. Every response carries the correlation id, so a user can quote it
 * and an operator can find the request. And an unexpected exception never reaches the client as a
 * message: it is logged in full with a stack trace and answered with a generic 500, because
 * exception text routinely contains table names, SQL fragments and file paths.
 */
@RestControllerAdvice
// Last resort: more specific advices, such as SecurityExceptionHandler, are consulted first.
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Problem type URIs are namespaced per error code so they can be documented and linked. */
    private static final String TYPE_PREFIX = "https://docs.pos.local/problems/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        // Deliberate, expected errors: warn, no stack trace - they are not defects.
        log.warn("{} -> {}: {}", ex.status().value(), ex.code(), ex.getMessage());
        ProblemDetail problem = problem(ex.status(), ex.code(), ex.getMessage());
        ex.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        // Deliberately no rejected value: it may hold a password or a card number.
                        .map(
                                fe ->
                                        Map.of(
                                                "field",
                                                fe.getField(),
                                                "message",
                                                fe.getDefaultMessage() == null
                                                        ? "is invalid"
                                                        : fe.getDefaultMessage()))
                        .toList();

        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "request.validation_failed",
                        "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors =
                ex.getConstraintViolations().stream()
                        .map(
                                v ->
                                        Map.of(
                                                "field", String.valueOf(v.getPropertyPath()),
                                                "message", v.getMessage()))
                        .toList();

        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        "request.validation_failed",
                        "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        // The parser message can echo the submitted body, so it is logged but not returned.
        log.warn("Unreadable request body: {}", ex.getMessage());
        return problem(
                HttpStatus.BAD_REQUEST, "request.malformed", "Request body could not be parsed");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "request.missing_parameter",
                "Required parameter '%s' is missing".formatted(ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "request.invalid_parameter",
                "Parameter '%s' has the wrong type".formatted(ex.getName()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "route.not_found", "No endpoint for this path");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Full detail to the log, nothing to the caller.
        log.error("Unhandled exception", ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal.error",
                "An unexpected error occurred. Quote the correlation id when reporting it.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(typeUri(code));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationId.get());
        return problem;
    }

    /**
     * The problem type URI for a code, never throwing.
     *
     * <p>{@code URI.create} rejects a code containing a space or any other character illegal in a
     * path, and an exception thrown here is far worse than a vague type: the resolver abandons the
     * handler and the original exception escapes the dispatcher, so a deliberate 404 reaches the
     * client as an unhandled 500 with no body. Codes are slugged at source; this is the backstop
     * for the one that is not.
     */
    private static URI typeUri(String code) {
        try {
            return URI.create(TYPE_PREFIX + code);
        } catch (IllegalArgumentException ex) {
            log.warn(
                    "Error code '{}' is not URI-safe; falling back to the generic problem type",
                    code);
            return URI.create(TYPE_PREFIX + "unspecified");
        }
    }
}
