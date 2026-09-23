package com.pos.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Base for every error this system raises deliberately.
 *
 * <p>Carries the HTTP status and a stable machine-readable {@code code} (for example {@code
 * product.not_found}) so a client can branch on the code rather than parsing prose. The
 * human-readable message is for operators and developers; it is returned to the caller, so it must
 * never contain a secret, a token, or another user's data.
 */
public abstract class ApiException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    protected ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    protected ApiException(
            HttpStatus status, String code, String message, Map<String, Object> details) {
        this(status, code, message, details, null);
    }

    protected ApiException(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details,
            Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra machine-readable context merged into the problem document. */
    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }
}
