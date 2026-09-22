package com.pos.payment.domain.policy;

/**
 * Daraja's STK result codes, as the stable reason codes a till screen translates.
 *
 * <p>The mapping matters because the cashier's next move depends on it: a customer who cancelled
 * can be asked again, one with no funds needs another tender, and an unreachable phone is worth one
 * retry at most.
 */
public final class MpesaResultCodes {

    public static final int SUCCESS = 0;

    private MpesaResultCodes() {}

    /** The reason code for a non-zero result. */
    public static String reasonFor(int resultCode) {
        return switch (resultCode) {
            case 1 -> "INSUFFICIENT_FUNDS";
            case 1001 -> "SUBSCRIBER_BUSY";
            case 1019 -> "TRANSACTION_EXPIRED";
            case 1025, 9999 -> "PROVIDER_ERROR";
            case 1032 -> "CANCELLED_BY_USER";
            case 1037 -> "CUSTOMER_UNREACHABLE";
            case 2001 -> "WRONG_PIN";
            default -> "DECLINED";
        };
    }

    public static boolean isSuccess(int resultCode) {
        return resultCode == SUCCESS;
    }
}
