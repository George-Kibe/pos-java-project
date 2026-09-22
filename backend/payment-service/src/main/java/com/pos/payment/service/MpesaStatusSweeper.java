package com.pos.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pos.payment.client.daraja.DarajaClient;
import com.pos.payment.client.daraja.DarajaException;
import com.pos.payment.client.daraja.DarajaProperties;

import lombok.RequiredArgsConstructor;

/**
 * Asks Daraja what became of pushes whose callback has not come.
 *
 * <p>The callback is a hint; the status query is the authority. Callbacks go missing - a tunnel
 * drops, a deploy restarts the service at the wrong second - and a sale must not wait on one
 * forever. The query is made outside any transaction and its answer recorded in one.
 */
@Component
@RequiredArgsConstructor
public class MpesaStatusSweeper {

    private static final Logger log = LoggerFactory.getLogger(MpesaStatusSweeper.class);

    private static final int BATCH = 20;

    private final MpesaTransactionService transactions;
    private final MpesaSettlementService settlement;
    private final DarajaClient daraja;
    private final DarajaProperties properties;

    @Scheduled(
            fixedDelayString = "${pos.payment.status-sweep-interval:PT15S}",
            initialDelayString = "${pos.payment.status-sweep-interval:PT15S}")
    public void sweep() {
        if (!properties.isConfigured()) {
            return;
        }
        for (String checkoutRequestId : transactions.dueForQuery(BATCH)) {
            try {
                switch (daraja.stkQuery(checkoutRequestId)) {
                    case DarajaClient.QueryResult.Completed completed ->
                            settlement.onQueryResult(
                                    checkoutRequestId,
                                    completed.resultCode(),
                                    completed.resultDesc());
                    case DarajaClient.QueryResult.StillProcessing ignored ->
                            settlement.onStillProcessing(checkoutRequestId);
                }
            } catch (DarajaException e) {
                log.warn("Status query for {} failed: {}", checkoutRequestId, e.getMessage());
                settlement.onStillProcessing(checkoutRequestId);
            }
        }
    }
}
