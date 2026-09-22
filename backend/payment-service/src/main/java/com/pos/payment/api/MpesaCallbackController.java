package com.pos.payment.api;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.payment.service.CallbackGuard;
import com.pos.payment.service.MpesaSettlementService;
import com.pos.payment.service.MpesaTransactionService;
import com.pos.payment.service.RefundService;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * Where Daraja reports back.
 *
 * <p>The one place in the platform without a bearer token: Daraja cannot present one. {@code
 * permitAll} here is explicit, and the authentication is the secret path token, checked by {@link
 * CallbackGuard} before the body is even parsed.
 *
 * <p>Always answers Daraja with acceptance once the caller is verified. A duplicate or an
 * out-of-order callback is handled here, idempotently; telling Daraja "error" would achieve nothing
 * but noise.
 */
@RestController
@RequestMapping("/api/v1/payments/mpesa/callbacks")
@RequiredArgsConstructor
@Hidden
public class MpesaCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MpesaCallbackController.class);

    private static final Map<String, Object> ACCEPTED =
            Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final CallbackGuard guard;
    private final MpesaSettlementService settlement;
    private final RefundService refunds;

    @PostMapping("/stk/{token}")
    @PreAuthorize("permitAll()")
    public Map<String, Object> stk(
            @PathVariable String token,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        verify(token, request);
        MpesaTransactionService.StkResult result = DarajaCallbacks.stk(body);
        log.info("STK callback for {}: result {}", result.checkoutRequestId(), result.resultCode());
        try {
            settlement.onCallback(result);
        } catch (DataIntegrityViolationException e) {
            // The same callback, twice at once: the other copy recorded it. This one finds it done.
            settlement.onCallback(result);
        }
        return ACCEPTED;
    }

    @PostMapping("/reversal/{token}")
    @PreAuthorize("permitAll()")
    public Map<String, Object> reversal(
            @PathVariable String token,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        verify(token, request);
        DarajaCallbacks.ReversalResult result = DarajaCallbacks.reversal(body);
        log.info(
                "Reversal result for {}: {}",
                result.originatorConversationId(),
                result.resultCode());
        refunds.onReversalResult(
                result.originatorConversationId(),
                result.resultCode(),
                result.resultDesc(),
                result.transactionId());
        return ACCEPTED;
    }

    @PostMapping("/reversal-timeout/{token}")
    @PreAuthorize("permitAll()")
    public Map<String, Object> reversalTimeout(
            @PathVariable String token,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        verify(token, request);
        DarajaCallbacks.ReversalResult result = DarajaCallbacks.reversal(body);
        log.warn("Reversal {} timed out in Daraja's queue", result.originatorConversationId());
        refunds.onReversalTimeout(result.originatorConversationId());
        return ACCEPTED;
    }

    private void verify(String token, HttpServletRequest request) {
        guard.verify(token, request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
    }
}
