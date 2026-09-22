package com.pos.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pos.common.error.Errors;
import com.pos.payment.client.daraja.DarajaProperties;

import lombok.RequiredArgsConstructor;

/**
 * Decides whether a callback really came from Daraja.
 *
 * <p>Daraja neither signs callbacks nor authenticates to us, so the secret is in the URL we gave
 * it: a path token compared in constant time. An optional IP allowlist narrows it further. A wrong
 * token answers 404, not 403, so the endpoint does not confirm to a prober that it exists.
 *
 * <p>With no token configured, every callback is refused: an open payment callback would let anyone
 * mark a sale paid.
 */
@Component
@RequiredArgsConstructor
public class CallbackGuard {

    private static final Logger log = LoggerFactory.getLogger(CallbackGuard.class);

    private final DarajaProperties properties;

    public void verify(String token, String remoteAddress, String forwardedFor) {
        String expected = properties.callbackToken();
        if (expected == null
                || expected.isBlank()
                || token == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Refused an M-Pesa callback with a wrong or missing token");
            throw new Errors.NotFoundException("payment.callback_not_found", "Not found");
        }

        List<String> allowed = properties.allowedIps();
        if (!allowed.isEmpty()) {
            String caller = callerAddress(remoteAddress, forwardedFor);
            if (!allowed.contains(caller)) {
                log.warn("Refused an M-Pesa callback from {}", caller);
                throw new Errors.ForbiddenException(
                        "payment.callback_forbidden", "Callbacks are not accepted from here");
            }
        }
    }

    /**
     * The address the nearest trusted proxy saw. Each proxy appends to X-Forwarded-For, so the
     * entry {@code hops} from the right is the one no caller could have written for itself.
     */
    String callerAddress(String remoteAddress, String forwardedFor) {
        int hops = properties.trustedProxyHopsOrDefault();
        if (hops <= 0 || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }
        String[] chain = forwardedFor.split(",");
        int index = chain.length - hops;
        return index >= 0 ? chain[index].trim() : remoteAddress;
    }
}
