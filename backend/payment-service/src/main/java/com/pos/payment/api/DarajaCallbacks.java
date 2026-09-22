package com.pos.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import com.pos.common.error.Errors;
import com.pos.payment.domain.policy.MpesaStatement;
import com.pos.payment.service.MpesaTransactionService;

/**
 * Daraja's callback bodies, read into something typed.
 *
 * <p>Daraja's JSON is loose: metadata is a list of {@code {Name, Value}} pairs, numbers arrive as
 * doubles, and the transaction date is a number shaped like {@code 20260922143011}. The customer's
 * phone number is in there too and is deliberately never read.
 */
final class DarajaCallbacks {

    private static final DateTimeFormatter DARAJA_DATE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private DarajaCallbacks() {}

    /** A reversal's result, from {@code {"Result": {...}}}. */
    record ReversalResult(
            String originatorConversationId,
            String conversationId,
            int resultCode,
            String resultDesc,
            String transactionId) {}

    @SuppressWarnings("unchecked")
    static MpesaTransactionService.StkResult stk(Map<String, Object> body) {
        Map<String, Object> callback = map(map(body, "Body"), "stkCallback");
        String checkoutRequestId = text(callback, "CheckoutRequestID");
        if (checkoutRequestId == null) {
            throw new Errors.BadRequestException(
                    "payment.callback_malformed", "Not an STK callback: no CheckoutRequestID");
        }
        String receipt = null;
        BigDecimal amount = null;
        Instant date = null;
        Object metadata = callback.get("CallbackMetadata");
        if (metadata instanceof Map<?, ?> meta && meta.get("Item") instanceof List<?> items) {
            for (Object entry : items) {
                if (!(entry instanceof Map<?, ?> item) || item.get("Value") == null) {
                    continue;
                }
                String value = String.valueOf(item.get("Value"));
                switch (String.valueOf(item.get("Name"))) {
                    case "MpesaReceiptNumber" -> receipt = value;
                    case "Amount" -> amount = new BigDecimal(value);
                    case "TransactionDate" -> date = darajaDate(value);
                    default -> {
                        // PhoneNumber and anything new: not needed, not kept
                    }
                }
            }
        }
        return new MpesaTransactionService.StkResult(
                text(callback, "MerchantRequestID"),
                checkoutRequestId,
                integer(callback, "ResultCode"),
                text(callback, "ResultDesc"),
                receipt,
                amount,
                date);
    }

    static ReversalResult reversal(Map<String, Object> body) {
        Map<String, Object> result = map(body, "Result");
        String originator = text(result, "OriginatorConversationID");
        if (originator == null) {
            throw new Errors.BadRequestException(
                    "payment.callback_malformed", "Not a reversal result");
        }
        return new ReversalResult(
                originator,
                text(result, "ConversationID"),
                integer(result, "ResultCode"),
                text(result, "ResultDesc"),
                text(result, "TransactionID"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        if (parent != null && parent.get(key) instanceof Map<?, ?> child) {
            return (Map<String, Object>) child;
        }
        throw new Errors.BadRequestException(
                "payment.callback_malformed", "Callback has no '" + key + "'");
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new Errors.BadRequestException(
                    "payment.callback_malformed", "Callback has no '" + key + "'");
        }
        return new BigDecimal(String.valueOf(value)).intValue();
    }

    private static Instant darajaDate(String value) {
        // 20260922143011, possibly delivered as 2.0260922143011E13.
        String digits = new BigDecimal(value).toPlainString();
        try {
            return LocalDateTime.parse(digits, DARAJA_DATE)
                    .atZone(MpesaStatement.NAIROBI)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
