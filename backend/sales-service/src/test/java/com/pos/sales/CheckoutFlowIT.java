package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.error.Errors;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentAuthorizedPayload;
import com.pos.events.payments.PaymentFailedPayload;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.domain.Cart;
import com.pos.sales.domain.CartLine;
import com.pos.sales.domain.CartStatus;
import com.pos.sales.domain.Receipt;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.totals.TaxClassTotal;
import com.pos.sales.repository.PriceOverrideRepository;
import com.pos.sales.repository.SaleRepository;
import com.pos.sales.service.CartService;
import com.pos.sales.service.CheckoutService;
import com.pos.sales.service.PaymentTimeoutSweeper;
import com.pos.sales.service.ReceiptService;
import com.pos.sales.service.TillSessionService;

/**
 * Ringing up a basket and getting paid for it.
 *
 * <p>The roadmap's test: a mixed basket - standard-rated, zero-rated, weighed and
 * promotion-discounted - is priced by catalog, paid for, and issued a receipt whose tax breakdown
 * matches catalog's per-line figures exactly. Then the saga's awkward cases: an asynchronous
 * payment, a failure that must release stock, a duplicated authorisation, and an authorisation that
 * arrives after the sale was given up on.
 */
class CheckoutFlowIT extends SalesTestBase {

    @Autowired private TillSessionService tills;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private ReceiptService receipts;
    @Autowired private PaymentTimeoutSweeper sweeper;
    @Autowired private SaleRepository sales;
    @Autowired private PriceOverrideRepository overrides;

    // --- the mixed basket -------------------------------------------------------

    @Test
    @DisplayName("a mixed basket is paid in cash and its receipt matches catalog to the cent")
    void theMixedBasket() {
        TillSession till = openTill();
        Cart cart = mixedBasket(till);

        Sale sale = checkout.checkout(cart.getId(), null, TOKEN);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.PENDING);

        // What catalog said, line by line, computed independently of the service under test.
        BigDecimal expectedGross =
                sum(
                        FakeCatalog.price(SOAP, money("2")),
                        FakeCatalog.price(FLOUR, money("1")),
                        FakeCatalog.price(BANANAS, money("1.235")),
                        FakeCatalog.price(JUICE, money("2")));
        assertThat(sale.getGrandTotal()).isEqualByComparingTo(expectedGross);
        assertThat(sale.getGrandTotal()).isEqualByComparingTo("1052.5377");
        assertThat(sale.getTaxTotal()).isEqualByComparingTo("116.2121");
        assertThat(sale.getNetTotal().add(sale.getTaxTotal()))
                .isEqualByComparingTo(sale.getGrandTotal());
        // The juice promotion, reported rather than hidden inside the net.
        assertThat(sale.getDiscountTotal()).isEqualByComparingTo("50.0000");

        Sale paid =
                checkout.tender(
                        sale.getId(),
                        List.of(
                                new CheckoutService.Tender(
                                        PaymentMethod.CASH, money("1100.00"), null, null)),
                        money("1100.00"));

        assertThat(paid.getStatus()).isEqualTo(SaleStatus.PAID);
        assertThat(paid.getReceiptNumber()).isEqualTo("R-000001");
        assertThat(paid.getChangeGiven()).isEqualByComparingTo("47.46");

        Receipt receipt = receipts.forSale(paid.getId()).getFirst();
        TaxClassTotal[] breakdown =
                EventJson.read(receipt.getTaxBreakdown(), TaxClassTotal[].class);

        // Standard rate first, then zero-rated flour.
        assertThat(breakdown).hasSize(2);
        assertThat(breakdown[0].taxClassCode()).isEqualTo("VAT16");
        assertThat(breakdown[0].tax()).isEqualByComparingTo("116.2121");
        assertThat(breakdown[0].gross()).isEqualByComparingTo("842.5377");
        assertThat(breakdown[1].taxClassCode()).isEqualTo("ZERO");
        assertThat(breakdown[1].tax()).isEqualByComparingTo("0");
        assertThat(breakdown[1].gross()).isEqualByComparingTo("210.0000");

        // The breakdown sums to the receipt, which sums to the sale.
        assertThat(breakdown[0].gross().add(breakdown[1].gross()))
                .isEqualByComparingTo(receipt.getGrandTotal());
        assertThat(receipt.getGrandTotal()).isEqualByComparingTo(paid.getGrandTotal());

        // Announced, with the lines inventory will deduct.
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isEqualTo(1);
        String event = latestOutboxPayload(Topics.SALES_SALE_COMPLETED);
        assertThat(event).contains("R-000001").contains("BANANA-KG").contains("1.235");

        // The shift knows it took cash.
        TillSession after = tills.require(till.getId());
        assertThat(after.getCashSales()).isEqualByComparingTo("1052.5377");
        assertThat(after.getSaleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the same tin scanned three times is one line of three, but each weighing is its own")
    void scanningMergesButWeighingDoesNot() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);

        for (int i = 0; i < 3; i++) {
            cart =
                    carts.addLine(
                            cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        }
        cart =
                carts.addLine(
                        cart.getId(),
                        BANANAS.id(),
                        BANANAS.sku(),
                        null,
                        money("0.800"),
                        true,
                        TOKEN);
        cart =
                carts.addLine(
                        cart.getId(),
                        BANANAS.id(),
                        BANANAS.sku(),
                        null,
                        money("1.100"),
                        true,
                        TOKEN);

        assertThat(cart.activeLines()).hasSize(3);
        assertThat(cart.activeLines().getFirst().getQuantity()).isEqualByComparingTo("3");
        assertThat(cart.getGrandTotal())
                .isEqualByComparingTo(
                        sum(
                                FakeCatalog.price(SOAP, money("3")),
                                FakeCatalog.price(BANANAS, money("0.800")),
                                FakeCatalog.price(BANANAS, money("1.100"))));
    }

    @Test
    @DisplayName("a voided line stays on the record but leaves the total")
    void voidingALine() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        cart = carts.addLine(cart.getId(), FLOUR.id(), FLOUR.sku(), null, money("1"), false, TOKEN);

        UUID soapLine = cart.getLines().getFirst().getId();
        cart = carts.voidLine(cart.getId(), soapLine, "Customer changed mind");

        assertThat(cart.getLines()).hasSize(2);
        assertThat(cart.activeLines()).hasSize(1);
        assertThat(cart.getGrandTotal()).isEqualByComparingTo("210.0000");

        // A new line never reuses the voided line's number, so the receipt reads in order.
        cart = carts.addLine(cart.getId(), JUICE.id(), JUICE.sku(), null, money("1"), false, TOKEN);
        assertThat(cart.getLines()).extracting(CartLine::getLineNumber).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName(
            "the client's total is compared, and a disagreement is flagged rather than trusted")
    void aStaleTerminalTotalIsFlagged() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);

        // The terminal thought soap was still 100.
        Sale sale = checkout.checkout(cart.getId(), money("100.00"), TOKEN);

        assertThat(sale.getGrandTotal()).isEqualByComparingTo("116.0000");
        assertThat(sale.isPriceVarianceFlagged()).isTrue();
        assertThat(sale.getPriceVarianceAmount()).isEqualByComparingTo("-16.0000");
    }

    @Test
    @DisplayName("an empty basket cannot be rung up, and a basket cannot be rung up twice")
    void checkoutGuards() {
        TillSession till = openTill();
        Cart empty = carts.open(till.getId(), null, false);

        assertThatThrownBy(() -> checkout.checkout(empty.getId(), null, TOKEN))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("nothing in this basket");

        Cart cart =
                carts.addLine(empty.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        checkout.checkout(cart.getId(), null, TOKEN);

        assertThatThrownBy(() -> checkout.checkout(cart.getId(), null, TOKEN))
                .isInstanceOf(Errors.ConflictException.class);
    }

    @Test
    @DisplayName("underpaying is refused before anything is recorded")
    void underpaymentIsRefused() {
        Sale sale = pendingSale(openTill());

        assertThatThrownBy(
                        () ->
                                checkout.tender(
                                        sale.getId(),
                                        List.of(
                                                new CheckoutService.Tender(
                                                        PaymentMethod.CASH,
                                                        money("50.00"),
                                                        null,
                                                        null)),
                                        money("50.00")))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("outstanding");
        assertThat(checkout.require(sale.getId()).getStatus()).isEqualTo(SaleStatus.PENDING);
    }

    // --- receipt numbering ------------------------------------------------------

    @Test
    @DisplayName("receipt numbers are gapless per branch")
    void receiptNumbersAreContiguous() {
        TillSession till = openTill();
        for (int i = 1; i <= 3; i++) {
            Sale paid = payCash(pendingSale(till));
            assertThat(paid.getReceiptNumber()).isEqualTo("R-%06d".formatted(i));
        }
    }

    @Test
    @DisplayName("a sale that is abandoned before payment never takes a receipt number")
    void cancelledSalesLeaveNoGap() {
        TillSession till = openTill();
        payCash(pendingSale(till));
        Sale abandoned = pendingSale(till);
        checkout.cancel(abandoned.getId(), "Customer left", TOKEN);
        Sale next = payCash(pendingSale(till));

        assertThat(abandoned.getReceiptNumber()).isNull();
        assertThat(next.getReceiptNumber()).isEqualTo("R-000002");
    }

    // --- the payment saga -------------------------------------------------------

    @Test
    @DisplayName("an M-Pesa tender waits, then the authorisation completes the sale")
    void anAsynchronousPaymentCompletesOnAuthorisation() {
        Sale sale = pendingSale(openTill());

        Sale awaiting =
                checkout.tender(
                        sale.getId(),
                        List.of(
                                new CheckoutService.Tender(
                                        PaymentMethod.MPESA,
                                        sale.getGrandTotal(),
                                        "254712345678",
                                        null)),
                        null);

        assertThat(awaiting.getStatus()).isEqualTo(SaleStatus.AWAITING_PAYMENT);
        assertThat(awaiting.getReceiptNumber()).isNull();
        // Only the last four digits are kept.
        assertThat(awaiting.getPayments().getFirst().getPhoneNumberMasked())
                .isEqualTo("********5678");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_REQUESTED)).isEqualTo(1);
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isZero();

        UUID intent = awaiting.getPayments().getFirst().getPaymentIntentId();
        publish(Topics.PAYMENTS_PAYMENT_AUTHORIZED, authorized(sale, intent), sale.getId());

        eventually(
                Duration.ofSeconds(30),
                "the sale to be paid",
                () -> checkout.require(sale.getId()).getStatus() == SaleStatus.PAID);

        Sale paid = checkout.require(sale.getId());
        assertThat(paid.getReceiptNumber()).isEqualTo("R-000001");
        assertThat(paid.getPayments().getFirst().getProviderReference()).isEqualTo("QK12ABC34D");
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicated authorisation pays once, numbers once, and announces once")
    void aRedeliveredAuthorisationChangesNothing() {
        Sale sale = awaitingMpesa(pendingSale(openTill()));
        UUID intent = sale.getPayments().getFirst().getPaymentIntentId();
        EventEnvelope<PaymentAuthorizedPayload> event = authorized(sale, intent);

        publish(Topics.PAYMENTS_PAYMENT_AUTHORIZED, event, sale.getId());
        eventually(
                Duration.ofSeconds(30),
                "the first delivery",
                () -> checkout.require(sale.getId()).getStatus() == SaleStatus.PAID);

        publish(Topics.PAYMENTS_PAYMENT_AUTHORIZED, event, sale.getId());
        eventually(
                Duration.ofSeconds(15),
                "the redelivery to be recorded as handled",
                () -> processed(event.eventId()) == 1);

        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isEqualTo(1);
        assertThat(receipts.forSale(sale.getId())).hasSize(1);
        assertThat(checkout.require(sale.getId()).getReceiptNumber()).isEqualTo("R-000001");
    }

    @Test
    @DisplayName("a failed payment cancels the sale, releases its stock and announces nothing sold")
    void aFailedPaymentCompensates() {
        Sale sale = awaitingMpesa(pendingSale(openTill()));
        UUID intent = sale.getPayments().getFirst().getPaymentIntentId();

        publish(
                Topics.PAYMENTS_PAYMENT_FAILED,
                EventEnvelope.<PaymentFailedPayload>builder()
                        .topic(Topics.PAYMENTS_PAYMENT_FAILED)
                        .branchId(BRANCH)
                        .payload(
                                new PaymentFailedPayload(
                                        intent,
                                        sale.getId(),
                                        BRANCH,
                                        PaymentMethod.MPESA,
                                        sale.getGrandTotal(),
                                        "KES",
                                        "CANCELLED_BY_USER",
                                        "Request cancelled by user",
                                        Instant.now()))
                        .build(),
                sale.getId());

        eventually(
                Duration.ofSeconds(30),
                "the sale to be cancelled",
                () -> checkout.require(sale.getId()).getStatus() == SaleStatus.CANCELLED);

        Sale cancelled = checkout.require(sale.getId());
        assertThat(cancelled.getCancellationReason()).contains("CANCELLED_BY_USER");
        assertThat(cancelled.getReceiptNumber()).isNull();
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isZero();
        // The listener has no caller token to forward, so the release is left to inventory's
        // expiry sweep; the stock was only ever soft-held, never deducted.
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isZero();
    }

    @Test
    @DisplayName("a cashier cancelling a sale releases the basket's reservations at once")
    void aManualCancellationReleasesStock() {
        Sale sale = pendingSale(openTill());
        assertThat(INVENTORY.reservations()).isGreaterThan(0);

        checkout.cancel(sale.getId(), "Customer walked away", TOKEN);

        assertThat(INVENTORY.releasesFor(sale.getReservationReference())).isEqualTo(1);
        assertThat(checkout.require(sale.getId()).getStatus()).isEqualTo(SaleStatus.CANCELLED);
    }

    @Test
    @DisplayName("an authorisation for a sale already given up on is flagged, not applied")
    void aLateAuthorisationIsNotApplied() {
        Sale sale = awaitingMpesa(pendingSale(openTill()));
        UUID intent = sale.getPayments().getFirst().getPaymentIntentId();
        checkout.cancel(sale.getId(), "Timed out", TOKEN);

        publish(Topics.PAYMENTS_PAYMENT_AUTHORIZED, authorized(sale, intent), sale.getId());

        eventually(
                Duration.ofSeconds(30),
                "the late authorisation to be recorded",
                () ->
                        "LATE_AUTHORIZATION_ON_CANCELLED_SALE"
                                .equals(
                                        jdbc.sql(
                                                        "SELECT failure_reason FROM sales.sale_payments"
                                                                + " WHERE payment_intent_id = :id")
                                                .param("id", intent)
                                                .query(String.class)
                                                .optional()
                                                .orElse(null)));

        // Still cancelled: the customer left without the goods and no stock was deducted.
        assertThat(checkout.require(sale.getId()).getStatus()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(outboxCount(Topics.SALES_SALE_COMPLETED)).isZero();
    }

    @Test
    @DisplayName("a sale split across cash and M-Pesa completes only when both are in")
    void aSplitTender() {
        Sale sale = pendingSale(openTill());
        BigDecimal half = money("58.00");

        Sale awaiting =
                checkout.tender(
                        sale.getId(),
                        List.of(
                                new CheckoutService.Tender(PaymentMethod.CASH, half, null, null),
                                new CheckoutService.Tender(
                                        PaymentMethod.MPESA,
                                        sale.getGrandTotal().subtract(half),
                                        "254700000001",
                                        null)),
                        null);

        // The cash is in the drawer, but the sale is not complete until the phone answers.
        assertThat(awaiting.getStatus()).isEqualTo(SaleStatus.AWAITING_PAYMENT);
        assertThat(awaiting.outstanding())
                .isEqualByComparingTo(sale.getGrandTotal().subtract(half));

        UUID mpesaIntent =
                awaiting.getPayments().stream()
                        .filter(p -> p.getMethod() == PaymentMethod.MPESA)
                        .findFirst()
                        .orElseThrow()
                        .getPaymentIntentId();
        publish(Topics.PAYMENTS_PAYMENT_AUTHORIZED, authorized(sale, mpesaIntent), sale.getId());

        eventually(
                Duration.ofSeconds(30),
                "the sale to complete",
                () -> checkout.require(sale.getId()).getStatus() == SaleStatus.PAID);

        // Only the cash half counts towards the drawer.
        assertThat(tills.require(sale.getTillSession().getId()).getCashSales())
                .isEqualByComparingTo("58.00");
    }

    @Test
    @DisplayName("a sale waiting past the payment timeout is compensated by the sweep")
    void theTimeoutSweepCompensates() {
        Sale sale = awaitingMpesa(pendingSale(openTill()));
        // Pretend it has been waiting for ten minutes.
        jdbc.sql(
                        "UPDATE sales.sales SET occurred_at = now() - interval '10 minutes' WHERE id = :id")
                .param("id", sale.getId())
                .update();

        sweeper.cancelStaleSales();

        Sale swept = checkout.require(sale.getId());
        assertThat(swept.getStatus()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(swept.getCancellationReason()).contains("timed out");
    }

    // --- voids and overrides ----------------------------------------------------

    @Test
    @DisplayName("a void reverses a paid sale without erasing it, and the drawer follows")
    void voidingAPaidSale() {
        Sale paid = payCash(pendingSale(openTill()));
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);

        Sale voided = checkout.voidSale(paid.getId(), "Rung up on the wrong customer", SUPERVISOR);

        assertThat(voided.getStatus()).isEqualTo(SaleStatus.VOIDED);
        assertThat(voided.getVoidApprovedBy()).isEqualTo(SUPERVISOR);
        // The receipt number stays; the original is never overwritten.
        assertThat(voided.getReceiptNumber()).isEqualTo(paid.getReceiptNumber());
        assertThat(outboxCount(Topics.SALES_SALE_VOIDED)).isEqualTo(1);
        assertThat(tills.require(paid.getTillSession().getId()).getCashRefunds())
                .isEqualByComparingTo(paid.getGrandTotal());

        assertThatThrownBy(() -> checkout.voidSale(paid.getId(), "Again", SUPERVISOR))
                .isInstanceOf(Errors.ConflictException.class);
    }

    @Test
    void aVoidNeedsAReason() {
        Sale paid = payCash(pendingSale(openTill()));
        assertThatThrownBy(() -> checkout.voidSale(paid.getId(), " ", SUPERVISOR))
                .isInstanceOf(Errors.BusinessRuleException.class);
    }

    @Test
    @DisplayName("a price override is audited with who approved it and what it gave away")
    void aPriceOverrideIsAudited() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("3"), false, TOKEN);
        UUID line = cart.getLines().getFirst().getId();

        cart =
                carts.overridePrice(
                        cart.getId(), line, money("100.00"), "Damaged packaging", SUPERVISOR);

        CartLine overridden = cart.getLines().getFirst();
        assertThat(overridden.getUnitPrice()).isEqualByComparingTo("100.00");
        assertThat(overridden.getOriginalUnitPrice()).isEqualByComparingTo("116.00");
        assertThat(cart.getGrandTotal()).isEqualByComparingTo("300.0000");
        // The tax is still extracted from an inclusive price: net + tax == line total.
        assertThat(overridden.getNetAmount().add(overridden.getTaxAmount()))
                .isEqualByComparingTo(overridden.getLineTotal());

        var audit = overrides.findAll();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getApprovedBy()).isEqualTo(SUPERVISOR);
        assertThat(audit.getFirst().getValueGivenAway()).isEqualByComparingTo("48.00");

        // Changing the quantity keeps the decided price rather than repricing from catalog.
        cart = carts.changeQuantity(cart.getId(), line, money("1"), TOKEN);
        assertThat(cart.getGrandTotal()).isEqualByComparingTo("100.0000");
    }

    @Test
    void anOverrideNeedsAReason() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        Cart withLine =
                carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        UUID line = withLine.getLines().getFirst().getId();

        assertThatThrownBy(
                        () ->
                                carts.overridePrice(
                                        withLine.getId(), line, money("1.00"), "", SUPERVISOR))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("reason");
    }

    // --- suspending and dependencies --------------------------------------------

    @Test
    @DisplayName("a basket can be parked and recalled by its short code")
    void suspendAndRecall() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);

        Cart parked = carts.suspend(cart.getId());
        assertThat(parked.getStatus()).isEqualTo(CartStatus.SUSPENDED);
        assertThat(parked.getSuspendCode()).matches("\\d{4}");
        UUID cartId = parked.getId();
        assertThatThrownBy(
                        () ->
                                carts.addLine(
                                        cartId,
                                        FLOUR.id(),
                                        FLOUR.sku(),
                                        null,
                                        money("1"),
                                        false,
                                        TOKEN))
                .isInstanceOf(Errors.ConflictException.class);

        Cart recalled = carts.recall(BRANCH, parked.getSuspendCode());
        assertThat(recalled.getStatus()).isEqualTo(CartStatus.OPEN);
        assertThat(recalled.activeLines()).hasSize(1);
    }

    @Test
    @DisplayName("with catalog down the till says so plainly and records nothing")
    void aCatalogOutageIsA503NotAGuess() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        UUID cartId = cart.getId();
        CATALOG.goDown();

        assertThatThrownBy(
                        () ->
                                carts.addLine(
                                        cartId,
                                        SOAP.id(),
                                        SOAP.sku(),
                                        null,
                                        money("1"),
                                        false,
                                        TOKEN))
                .isInstanceOf(Errors.ServiceUnavailableException.class)
                .hasMessageContaining("not been recorded");
        assertThat(carts.require(cartId).getLines()).isEmpty();
    }

    @Test
    @DisplayName("the caller's token and correlation id travel with the pricing call")
    void theCallCarriesTokenAndCorrelationId() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);

        CorrelationId.with(
                "corr-sales-1",
                () ->
                        carts.addLine(
                                cart.getId(),
                                SOAP.id(),
                                SOAP.sku(),
                                null,
                                money("1"),
                                false,
                                TOKEN));

        assertThat(CATALOG.lastAuthorization()).isEqualTo(TOKEN);
        // Captured before the circuit breaker's thread, where the MDC would have been empty.
        assertThat(CATALOG.lastCorrelationId()).isEqualTo("corr-sales-1");
    }

    // --- helpers ----------------------------------------------------------------

    private TillSession openTill() {
        return tills.open(BRANCH, REGISTER, money("5000.00"));
    }

    private Cart mixedBasket(TillSession till) {
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("2"), false, TOKEN);
        cart = carts.addLine(cart.getId(), FLOUR.id(), FLOUR.sku(), null, money("1"), false, TOKEN);
        cart =
                carts.addLine(
                        cart.getId(),
                        BANANAS.id(),
                        BANANAS.sku(),
                        null,
                        money("1.235"),
                        true,
                        TOKEN);
        return carts.addLine(cart.getId(), JUICE.id(), JUICE.sku(), null, money("2"), false, TOKEN);
    }

    private Sale pendingSale(TillSession till) {
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        return checkout.checkout(cart.getId(), null, TOKEN);
    }

    private Sale payCash(Sale sale) {
        return checkout.tender(
                sale.getId(),
                List.of(
                        new CheckoutService.Tender(
                                PaymentMethod.CASH, sale.getGrandTotal(), null, null)),
                sale.getGrandTotal());
    }

    private Sale awaitingMpesa(Sale sale) {
        return checkout.tender(
                sale.getId(),
                List.of(
                        new CheckoutService.Tender(
                                PaymentMethod.MPESA, sale.getGrandTotal(), "254712345678", null)),
                null);
    }

    private static EventEnvelope<PaymentAuthorizedPayload> authorized(Sale sale, UUID intent) {
        return EventEnvelope.<PaymentAuthorizedPayload>builder()
                .topic(Topics.PAYMENTS_PAYMENT_AUTHORIZED)
                .branchId(BRANCH)
                .payload(
                        new PaymentAuthorizedPayload(
                                intent,
                                sale.getId(),
                                BRANCH,
                                PaymentMethod.MPESA,
                                null,
                                "KES",
                                "QK12ABC34D",
                                null,
                                Instant.now()))
                .build();
    }

    private long processed(String eventId) {
        Long count =
                jdbc.sql("SELECT count(*) FROM sales.processed_event WHERE event_id = :id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    @SafeVarargs
    private static BigDecimal sum(Map<String, Object>... lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            total = total.add((BigDecimal) line.get("lineTotal"));
        }
        return total;
    }
}
