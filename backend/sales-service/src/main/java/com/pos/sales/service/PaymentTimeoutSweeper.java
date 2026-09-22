package com.pos.sales.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pos.sales.config.ServiceEndpointProperties;
import com.pos.sales.domain.Sale;
import com.pos.sales.repository.SaleRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sales waiting for a payment answer that never came.
 *
 * <p>A customer who walks away from an M-Pesa prompt leaves a sale in AWAITING_PAYMENT, and nothing
 * else will ever resolve it. Left alone it holds a stock reservation and sits in the till's open
 * list forever, so a timeout is treated as a failure and the saga compensates.
 *
 * <p>A late authorisation after this point is not applied silently - see the listener, which routes
 * it to reconciliation instead.
 */
@Component
@RequiredArgsConstructor
public class PaymentTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutSweeper.class);

    private final SaleRepository sales;
    private final CheckoutService checkout;
    private final ServiceEndpointProperties properties;

    @Scheduled(cron = "${pos.sales.payment-sweep-cron:0 */1 * * * *}")
    public void cancelStaleSales() {
        Instant cutoff = Instant.now().minus(properties.paymentTimeoutOrDefault());
        List<Sale> stale = sales.findStalePendingPayments(cutoff);

        for (Sale sale : stale) {
            log.warn(
                    "Sale {} at branch {} waited past the payment timeout; compensating",
                    sale.getId(),
                    sale.getBranchId());
            // Null token: there is no caller to borrow credentials from on a scheduled thread,
            // and InventoryClient skips the release rather than guaranteeing a 401. Inventory
            // expires the reservation on its own sweep.
            checkout.cancel(sale.getId(), "Payment timed out");
        }
        if (!stale.isEmpty()) {
            log.info("Compensated {} sales that timed out waiting for payment", stale.size());
        }
    }
}
