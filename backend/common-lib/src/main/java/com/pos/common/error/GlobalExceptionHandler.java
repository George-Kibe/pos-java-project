package com.pos.common.error;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

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

    /**
     * A body the parser could not turn into the request type.
     *
     * <p>The parser's own message is logged but never returned: it echoes the submitted value,
     * which may be a password or a card number. What is returned is the field path and, for an
     * enum, the values that would have been accepted - the field names and the accepted set are
     * published API, not the caller's data.
     *
     * <p>Worth the effort because the bare "could not be parsed" is the least actionable error the
     * platform can send. An offline till retrying a queued sale gets one chance to be told which
     * field is wrong.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());

        String detail = "Request body could not be parsed";
        String field = null;

        if (ex.getCause() instanceof MismatchedInputException cause) {
            field = fieldPath(cause);
            Class<?> target = cause.getTargetType();

            if (target != null && target.isEnum()) {
                String accepted =
                        Arrays.stream(target.getEnumConstants())
                                .map(Object::toString)
                                .collect(Collectors.joining(", "));
                // The type is always named and the field only when Jackson recorded a path, so
                // the message stays actionable either way rather than degrading to the bare
                // "could not be parsed".
                detail =
                        field == null
                                ? "A %s value must be one of: %s"
                                        .formatted(target.getSimpleName(), accepted)
                                : "Field '%s' (%s) must be one of: %s"
                                        .formatted(field, target.getSimpleName(), accepted);
            } else if (field != null) {
                detail = "Field '%s' is missing or has the wrong type".formatted(field);
            }
        }

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "request.malformed", detail);
        if (field != null) {
            problem.setProperty("errors", List.of(Map.of("field", field, "message", detail)));
        }
        return problem;
    }

    /**
     * The dotted path to the field the parser choked on, {@code lines[0].reasonCode} style.
     *
     * <p>Returns null when Jackson recorded no path, which does happen: a record is built through a
     * creator, and depending on what else is in the body the failure can surface while the creator
     * is invoked rather than while a property is bound, leaving nothing to prepend. Do not rely on
     * the path being there - callers handle null and fall back to naming the type.
     */
    private static String fieldPath(MismatchedInputException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
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
