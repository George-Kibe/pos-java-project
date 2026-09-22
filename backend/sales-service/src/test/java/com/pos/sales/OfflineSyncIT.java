package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.common.error.Errors;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleOrigin;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.TillSession;
import com.pos.sales.service.CheckoutService;
import com.pos.sales.service.OfflineSyncService;
import com.pos.sales.service.OfflineSyncService.BatchResult;
import com.pos.sales.service.OfflineSyncService.OfflineLine;
import com.pos.sales.service.OfflineSyncService.OfflineSale;
import com.pos.sales.service.OfflineSyncService.SaleResult;
import com.pos.sales.service.ReceiptService;
import com.pos.sales.service.TillSessionService;

/**
 * Sales a terminal rang up with the network down, arriving later - possibly more than once.
 *
 * <p>The roadmap's test: a replayed offline batch creates no duplicates. Around it, the cases that
 * make a replay dangerous: a stale price, one bad sale in a good batch, and catalog being down when
 * the batch lands.
 */
class OfflineSyncIT extends SalesTestBase {

    @Autowired private OfflineSyncService offlineSync;
    @Autowired private TillSessionService tills;
    @Autowired private CheckoutService checkout;
    @Autowired private ReceiptService receipts;

    @Test
    @DisplayName("an offline batch is accepted, numbered, receipted and announced")
    void anOfflineBatchIsAccepted() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        OfflineSale soap = offlineSale(till, SOAP, "2", "232.00");
        OfflineSale flour = offlineSale(till, FLOUR, "1", "210.00");

        BatchResult result = sync("batch-1", soap, flour);

        assertThat(result.replayed()).isFalse();
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.rejected()).isZero();
        assertThat(result.variances()).isZero();
        assertThat(result.results())
                .extracting(SaleResult::receiptNumber)
                .containsExactly("R-000001", "R-000002");

        Sale sale = checkout.require(result.results().getFirst().saleId());
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PAID);
        assertThat(sale.getOrigin()).isEqualTo(SaleOrigin.OFFLINE_SYNC);
        assertThat(sale.getClientSaleId()).isEqualTo(soap.clientSaleId());
        assertThat(sale.getGrandTotal()).isEqualByComparingTo("232.00");
        assertThat(receipts.forSale(sale.getId())).hasSize(1);
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isEqualTo(2);

        // The shift the terminal was on knows it took the cash.
        TillSession after = tills.require(till.getId());
        assertThat(after.getSaleCount()).isEqualTo(2);
        assertThat(after.getCashSales()).isEqualByComparingTo("442.00");
    }

    @Test
    @DisplayName("the same batch replayed under its key gets the first answer and creates nothing")
    void aReplayedBatchCreatesNoDuplicates() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        OfflineSale soap = offlineSale(till, SOAP, "1", "116.00");
        OfflineSale flour = offlineSale(till, FLOUR, "1", "210.00");

        BatchResult first = sync("batch-replay", soap, flour);
        BatchResult replay = sync("batch-replay", soap, flour);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.accepted()).isEqualTo(first.accepted());
        assertThat(replay.results())
                .extracting(SaleResult::saleId)
                .containsExactlyElementsOf(
                        first.results().stream().map(SaleResult::saleId).toList());
        assertThat(salesCount()).isEqualTo(2);
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isEqualTo(2);
    }

    @Test
    @DisplayName("the same sales under a new key are reported as duplicates, not recreated")
    void aResubmittedSaleIsADuplicate() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        OfflineSale soap = offlineSale(till, SOAP, "1", "116.00");

        BatchResult first = sync("batch-a", soap);
        // The terminal lost the first answer and queued the sale again in a new batch.
        BatchResult second = sync("batch-b", soap, offlineSale(till, FLOUR, "1", "210.00"));

        assertThat(second.replayed()).isFalse();
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(second.accepted()).isEqualTo(1);
        SaleResult duplicate = second.results().getFirst();
        assertThat(duplicate.outcome()).isEqualTo("DUPLICATE");
        assertThat(duplicate.saleId()).isEqualTo(first.results().getFirst().saleId());
        assertThat(duplicate.receiptNumber()).isEqualTo("R-000001");

        assertThat(salesCount()).isEqualTo(2);
        // The receipt sequence did not skip a number for the duplicate.
        assertThat(second.results().get(1).receiptNumber()).isEqualTo("R-000002");
    }

    @Test
    @DisplayName("a stale terminal price is accepted at the server's figure and flagged")
    void aStalePriceIsFlaggedNotTrusted() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        // The terminal's cache still had soap at 110.
        OfflineSale stale = offlineSale(till, SOAP, "1", "110.00");

        BatchResult result = sync("batch-stale", stale);

        SaleResult answer = result.results().getFirst();
        assertThat(answer.outcome()).isEqualTo("ACCEPTED");
        assertThat(answer.serverGrandTotal()).isEqualByComparingTo("116.00");
        assertThat(answer.claimedGrandTotal()).isEqualByComparingTo("110.00");
        assertThat(answer.variance()).isEqualByComparingTo("-6.00");
        assertThat(result.variances()).isEqualTo(1);

        Sale sale = checkout.require(answer.saleId());
        assertThat(sale.isPriceVarianceFlagged()).isTrue();
        assertThat(sale.getPriceVarianceAmount()).isEqualByComparingTo("-6.00");
        assertThat(sale.getGrandTotal()).isEqualByComparingTo("116.00");
    }

    @Test
    @DisplayName("one unsellable sale is rejected and the rest of the batch still lands")
    void oneBadSaleDoesNotSinkTheBatch() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        FakeCatalog.Product unknown =
                new FakeCatalog.Product(
                        UUID.randomUUID(),
                        "GONE-1",
                        "Delisted",
                        money("10.00"),
                        "VAT16",
                        new BigDecimal("0.16"),
                        true,
                        null);

        BatchResult result =
                sync(
                        "batch-mixed",
                        offlineSale(till, SOAP, "1", "116.00"),
                        offlineSale(till, unknown, "1", "10.00"),
                        offlineSale(till, FLOUR, "1", "210.00"));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.results())
                .extracting(SaleResult::outcome)
                .containsExactly("ACCEPTED", "REJECTED", "ACCEPTED");
        // A rejected sale rolls back whole, so it cannot leave a gap in the receipt numbers.
        assertThat(result.results())
                .extracting(SaleResult::receiptNumber)
                .containsExactly("R-000001", null, "R-000002");
        assertThat(salesCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unknown product is a refusal, not an outage")
    void anUnknownProductDoesNotReadAsCatalogDown() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        FakeCatalog.Product unknown =
                new FakeCatalog.Product(
                        UUID.randomUUID(),
                        "GONE-2",
                        "Delisted",
                        money("10.00"),
                        "VAT16",
                        new BigDecimal("0.16"),
                        true,
                        null);

        SaleResult answer =
                sync("batch-unknown", offlineSale(till, unknown, "1", "10.00"))
                        .results()
                        .getFirst();

        assertThat(answer.outcome()).isEqualTo("REJECTED");
        assertThat(answer.message()).contains("does not know");
    }

    @Test
    @DisplayName("with catalog down the batch is refused whole and can be retried under its key")
    void aCatalogOutageAsksForARetry() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        OfflineSale soap = offlineSale(till, SOAP, "1", "116.00");
        CATALOG.goDown();

        assertThatThrownBy(() -> sync("batch-outage", soap))
                .isInstanceOf(Errors.ServiceUnavailableException.class);
        assertThat(salesCount()).isZero();

        // Catalog recovers; the terminal retries with the same key and is answered properly,
        // because a refused batch was never recorded as answered.
        CATALOG.reset();
        CATALOG.register(SOAP);
        BatchResult retried = sync("batch-outage", soap);
        assertThat(retried.replayed()).isFalse();
        assertThat(retried.accepted()).isEqualTo(1);
    }

    @Test
    void aBatchNeedsAnIdempotencyKey() {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));

        assertThatThrownBy(() -> sync(" ", offlineSale(till, SOAP, "1", "116.00")))
                .isInstanceOf(Errors.BadRequestException.class);
        assertThat(salesCount()).isZero();
    }

    // --- helpers ----------------------------------------------------------------

    private BatchResult sync(String key, OfflineSale... sales) {
        return offlineSync.sync(key, BRANCH, REGISTER, List.of(sales), TOKEN);
    }

    private static OfflineSale offlineSale(
            TillSession till, FakeCatalog.Product product, String quantity, String claimed) {
        BigDecimal claimedTotal = money(claimed);
        return new OfflineSale(
                UUID.randomUUID(),
                REGISTER,
                till.getId(),
                null,
                false,
                Instant.now().minusSeconds(3600),
                PaymentMethod.CASH,
                claimedTotal,
                claimedTotal,
                List.of(
                        new OfflineLine(
                                product.id(),
                                product.sku(),
                                null,
                                money(quantity),
                                product.unitPrice(),
                                claimedTotal)));
    }

    private long salesCount() {
        Long count = jdbc.sql("SELECT count(*) FROM sales.sales").query(Long.class).single();
        return count == null ? 0 : count;
    }
}
