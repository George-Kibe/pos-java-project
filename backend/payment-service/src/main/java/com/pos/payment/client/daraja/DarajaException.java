package com.pos.payment.client.daraja;

/**
 * Daraja said no, or could not be asked.
 *
 * <p>The distinction decides what happens next. A refusal ({@link Kind#REJECTED}) is final - the
 * request was bad and repeating it will not help. {@link Kind#UNAVAILABLE} means no answer, and for
 * an STK Push "no answer" includes "the push may have gone out", so it is never retried blindly.
 */
public class DarajaException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public enum Kind {
        REJECTED,
        UNAVAILABLE
    }

    private final Kind kind;

    public DarajaException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public DarajaException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
