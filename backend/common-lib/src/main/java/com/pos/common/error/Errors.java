package com.pos.common.error;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;

/** The concrete exception types. Services raise these rather than subclassing ApiException. */
public final class Errors {

    private Errors() {}

    /** The thing does not exist, or the caller may not know that it does. */
    public static class NotFoundException extends ApiException {
        public NotFoundException(String code, String message) {
            super(HttpStatus.NOT_FOUND, code, message);
        }

        public static NotFoundException of(String resource, Object id) {
            return new NotFoundException(
                    slug(resource) + ".not_found", "%s %s not found".formatted(resource, id));
        }
    }

    /** The request conflicts with current state: duplicate key, concurrent edit, wrong status. */
    public static class ConflictException extends ApiException {
        public ConflictException(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }

        public ConflictException(String code, String message, Map<String, Object> details) {
            super(HttpStatus.CONFLICT, code, message, details);
        }
    }

    /** Authenticated, but not permitted. */
    public static class ForbiddenException extends ApiException {
        public ForbiddenException(String code, String message) {
            super(HttpStatus.FORBIDDEN, code, message);
        }
    }

    /** Not authenticated, or credentials are no longer valid. */
    public static class UnauthorizedException extends ApiException {
        public UnauthorizedException(String code, String message) {
            super(HttpStatus.UNAUTHORIZED, code, message);
        }
    }

    /** Syntactically valid but semantically wrong for this domain. */
    public static class BadRequestException extends ApiException {
        public BadRequestException(String code, String message) {
            super(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    /**
     * A business rule said no: selling below cost without authorisation, refunding outside the
     * returns window, closing a shift with an unresolved variance.
     */
    public static class BusinessRuleException extends ApiException {
        public BusinessRuleException(String code, String message) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        }

        public BusinessRuleException(String code, String message, Map<String, Object> details) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
        }
    }

    /** Rate limited. */
    public static class TooManyRequestsException extends ApiException {
        public TooManyRequestsException(String code, String message) {
            super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        }
    }

    /**
     * Turns a human resource name into a code segment: {@code "Stock item"} becomes {@code
     * stock_item}.
     *
     * <p>A code is a machine-readable identifier that clients branch on and that is interpolated
     * into the problem {@code type} URI. Passing the display name through unchanged put a space in
     * that URI, and {@code URI.create} refused it - so an ordinary 404 died inside the exception
     * handler and reached the client as an unhandled 500.
     */
    static String slug(String resource) {
        String slug = resource.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return slug.replaceAll("^_|_$", "");
    }
}
