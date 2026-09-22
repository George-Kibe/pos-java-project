package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.common.error.ApiException;
import com.pos.common.error.Errors;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.domain.Cart;
import com.pos.sales.domain.ReturnReason;
import com.pos.sales.domain.ReturnStatus;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleReturn;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.TillSessionStatus;
import com.pos.sales.domain.policy.ReturnEligibility;
import com.pos.sales.service.CartService;
import com.pos.sales.service.CheckoutService;
import com.pos.sales.service.SaleReturnService;
import com.pos.sales.service.SaleReturnService.ReturnLineRequest;
import com.pos.sales.service.TillSessionService;
import com.pos.sales.service.ZReportService;

/**
 * Goods coming back, and the drawer at the end of the day.
 *
 * <p>The two meet in the cash: a refund paid out of the drawer has to show up in the count at
 * close, or every shift with a return reads as over.
 */
class ReturnsAndShiftIT extends SalesTestBase {

    @Autowired private TillSessionService tills;
    @Autowired private CartService carts;
    @Autowired private CheckoutService checkout;
    @Autowired private SaleReturnService returns;
    @Autowired private ZReportService zReports;

    // --- returns ----------------------------------------------------------------

    @Test
    @DisplayName("a partial return refunds its share of what was charged and says if it can resell")
    void aPartialReturn() {
        TillSession till = openTill();
        Sale sale = paidSale(till, JUICE, "3");
        UUID lineId = sale.getLines().getFirst().getId();

        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        SaleReturn processed =
                returns.process(
                        sale.getId(),
                        ReturnReason.FAULTY,
                        PaymentMethod.CASH,
                        "Seal broken",
                        null,
                        null,
                        List.of(new ReturnLineRequest(lineId, money("1"), false, "Leaking")),
                        shift());

        assertThat(processed.getStatus()).isEqualTo(ReturnStatus.COMPLETED);
        assertThat(processed.getReturnNumber()).matches("RT-\\d{4}-000001");
        // One of three, at the promotional price actually paid: (250 - 25) = 225, not 250.
        assertThat(processed.getRefundTotal()).isEqualByComparingTo("225.0000");
        assertThat(processed.isOutsidePolicyWindow()).isFalse();

        assertThat(outboxCount(Topics.SALES_RETURN_PROCESSED)).isEqualTo(1);
        String event = latestOutboxPayload(Topics.SALES_RETURN_PROCESSED);
        assertThat(event).contains("\"resaleable\":false").contains(sale.getId().toString());

        // What is left to return shrinks.
        ReturnEligibility left =
                returns.eligibility(sale.getId()).stream()
                        .filter(e -> e.saleLineId().equals(lineId))
                        .findFirst()
                        .orElseThrow();
        assertThat(left.eligible()).isTrue();
        assertThat(left.maximumReturnable()).isEqualByComparingTo("2");
    }

    @Test
    void moreThanWasSoldCannotComeBack() {
        Sale sale = paidSale(openTill(), SOAP, "2");
        UUID lineId = sale.getLines().getFirst().getId();
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);

        returns.process(
                sale.getId(),
                ReturnReason.CHANGED_MIND,
                PaymentMethod.CASH,
                null,
                null,
                null,
                List.of(new ReturnLineRequest(lineId, money("1.5"), true, null)),
                shift());

        assertThatThrownBy(
                        () ->
                                returns.process(
                                        sale.getId(),
                                        ReturnReason.CHANGED_MIND,
                                        PaymentMethod.CASH,
                                        null,
                                        null,
                                        null,
                                        List.of(
                                                new ReturnLineRequest(
                                                        lineId, money("1"), true, null)),
                                        shift()))
                .isInstanceOf(Errors.BusinessRuleException.class);
        assertThat(outboxCount(Topics.SALES_RETURN_PROCESSED)).isEqualTo(1);
    }

    @Test
    @DisplayName("outside the window a return needs an approver and a reason, and records both")
    void outsideTheWindowNeedsAnOverride() {
        Sale sale = paidSale(openTill(), SOAP, "1");
        UUID lineId = sale.getLines().getFirst().getId();
        jdbc.sql(
                        "UPDATE sales.sales SET completed_at = now() - interval '45 days'"
                                + " WHERE id = :id")
                .param("id", sale.getId())
                .update();
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        List<ReturnLineRequest> lines =
                List.of(new ReturnLineRequest(lineId, money("1"), true, null));

        assertThatThrownBy(
                        () ->
                                returns.process(
                                        sale.getId(),
                                        ReturnReason.FAULTY,
                                        PaymentMethod.CASH,
                                        null,
                                        null,
                                        null,
                                        lines,
                                        shift()))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("outside");

        SaleReturn approved =
                returns.process(
                        sale.getId(),
                        ReturnReason.FAULTY,
                        PaymentMethod.CASH,
                        null,
                        SUPERVISOR,
                        "Regular customer, receipt shown",
                        lines,
                        shift());

        assertThat(approved.isOutsidePolicyWindow()).isTrue();
        assertThat(approved.getPolicyOverrideBy()).isEqualTo(SUPERVISOR);
        assertThat(approved.getDaysSinceSale()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void anUnpaidSaleCannotBeReturned() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        Sale pending = checkout.checkout(cart.getId(), null, TOKEN);

        assertThatThrownBy(() -> returns.eligibility(pending.getId()))
                .isInstanceOf(Errors.BusinessRuleException.class);
    }

    @Test
    @DisplayName(
            "a cash refund comes out of today's drawer, not the closed shift that took the sale")
    void aLaterRefundIsPaidFromTheShiftThatPaysIt() {
        TillSession monday = openTill();
        Sale sale = paidSale(monday, SOAP, "2");
        tills.close(monday.getId(), money("5232.00"), null);

        TillSession tuesday = openTill();
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        returns.process(
                sale.getId(),
                ReturnReason.CHANGED_MIND,
                PaymentMethod.CASH,
                null,
                null,
                null,
                List.of(
                        new ReturnLineRequest(
                                sale.getLines().getFirst().getId(), money("1"), true, null)),
                tuesday.getId());

        // Monday was counted and closed; it must not move. Tuesday's drawer paid the refund.
        assertThat(tills.require(monday.getId()).getCashRefunds()).isEqualByComparingTo("0");
        assertThat(tills.require(tuesday.getId()).getCashRefunds()).isEqualByComparingTo("116.00");
        assertThat(latestOutboxPayload(Topics.SALES_RETURN_PROCESSED))
                .contains("\"tillSessionId\":\"" + tuesday.getId() + "\"");
    }

    @Test
    void aCashRefundMustNameTheDrawerPayingIt() {
        Sale sale = paidSale(openTill(), SOAP, "1");
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);

        assertThatThrownBy(
                        () ->
                                returns.process(
                                        sale.getId(),
                                        ReturnReason.FAULTY,
                                        PaymentMethod.CASH,
                                        null,
                                        null,
                                        null,
                                        List.of(
                                                new ReturnLineRequest(
                                                        sale.getLines().getFirst().getId(),
                                                        money("1"),
                                                        true,
                                                        null)),
                                        null))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("open shift");
    }

    @Test
    @DisplayName("once the shift that took a sale is closed, the sale is returned, not voided")
    void aSaleOnAClosedShiftCannotBeVoided() {
        TillSession till = openTill();
        Sale sale = paidSale(till, SOAP, "1");
        tills.close(till.getId(), money("5116.00"), null);
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);

        assertThatThrownBy(() -> checkout.voidSale(sale.getId(), "Too late", SUPERVISOR))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("process a return");
    }

    @Test
    @DisplayName("a completed sale says how it was paid, cash net of the change handed back")
    void aCompletedSaleCarriesItsTenders() {
        TillSession till = openTill();
        Cart cart = carts.open(till.getId(), null, false);
        cart = carts.addLine(cart.getId(), SOAP.id(), SOAP.sku(), null, money("1"), false, TOKEN);
        Sale sale = checkout.checkout(cart.getId(), null, TOKEN);
        checkout.tender(
                sale.getId(),
                List.of(
                        new CheckoutService.Tender(
                                PaymentMethod.CASH, money("200.00"), null, null)),
                money("200.00"));

        assertThat(latestOutboxPayload(Topics.SALES_SALE_COMPLETED))
                .contains("\"payments\":[{\"method\":\"CASH\",\"amount\":116");
    }

    // --- the shift --------------------------------------------------------------

    @Test
    @DisplayName("a shift closes against sales, refunds and drops, and announces its variance")
    void aShiftFromFloatToCount() {
        TillSession till = openTill();
        Sale sale = paidSale(till, SOAP, "3"); // 348.00 cash

        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        returns.process(
                sale.getId(),
                ReturnReason.CHANGED_MIND,
                PaymentMethod.CASH,
                null,
                null,
                null,
                List.of(
                        new ReturnLineRequest(
                                sale.getLines().getFirst().getId(), money("1"), true, null)),
                shift());
        tills.recordDrop(till.getId(), money("1000.00"), "Mid-shift drop", "SAFE-BAG-7");
        tills.addFloat(till.getId(), money("200.00"), "More coins");

        actingAs(CASHIER, CASHIER_PERMISSIONS);
        TillSession closing = tills.beginClose(till.getId());
        assertThat(closing.getStatus()).isEqualTo(TillSessionStatus.CLOSING);
        // 5000 float + 200 top-up + 348 sold - 116 refunded - 1000 dropped.
        assertThat(closing.getExpectedCash()).isEqualByComparingTo("4432.00");

        TillSession closed = tills.close(till.getId(), money("4430.00"), "Two shillings short");

        assertThat(closed.getStatus()).isEqualTo(TillSessionStatus.CLOSED);
        assertThat(closed.getVariance()).isEqualByComparingTo("-2.00");
        assertThat(closed.getClosedBy()).isEqualTo(CASHIER);
        assertThat(outboxCount(Topics.SALES_SHIFT_CLOSED)).isEqualTo(1);
        assertThat(latestOutboxPayload(Topics.SALES_SHIFT_CLOSED)).contains("\"variance\":-2");

        ZReportService.ZReport report = zReports.forSession(till.getId());
        assertThat(report.saleCount()).isEqualTo(1);
        assertThat(report.cashSales()).isEqualByComparingTo("348.00");
        assertThat(report.cashRefunds()).isEqualByComparingTo("116.00");
        assertThat(report.cashDrops()).isEqualByComparingTo("1000.00");
        assertThat(report.variance()).isEqualByComparingTo("-2.00");
        assertThat(report.countersAgreeWithSales()).isTrue();
        assertThat(report.takings())
                .singleElement()
                .satisfies(
                        takings -> {
                            assertThat(takings.method()).isEqualTo("CASH");
                            assertThat(takings.total()).isEqualByComparingTo("348.00");
                        });
        assertThat(tills.movementsOf(till.getId())).hasSize(3);
    }

    @Test
    @DisplayName("a void is not a drifting counter: the Z-report still agrees with the till")
    void aShiftWithAVoidStillAgreesWithItsCounters() {
        TillSession till = openTill();
        paidSale(till, SOAP, "2"); // 232.00 cash
        Sale voided = paidSale(till, SOAP, "1"); // 116.00 cash, then given back
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        checkout.voidSale(voided.getId(), "Rung up twice", SUPERVISOR);

        ZReportService.ZReport report = zReports.forSession(till.getId());

        assertThat(report.countersAgreeWithSales()).isTrue();
        // The drawer took both and gave one back; the takings are what was kept.
        assertThat(report.cashSales()).isEqualByComparingTo("348.00");
        assertThat(report.cashRefunds()).isEqualByComparingTo("116.00");
        assertThat(report.totalTakings()).isEqualByComparingTo("232.00");
    }

    @Test
    void aRegisterHasOneShiftAtATime() {
        openTill();
        assertThatThrownBy(this::openTill).isInstanceOf(Errors.ConflictException.class);
    }

    @Test
    @DisplayName("a cashier cannot close a colleague's drawer; a supervisor can")
    void closingSomeoneElsesShift() {
        TillSession till = openTill();
        UUID colleague = UUID.randomUUID();

        actingAs(colleague, CASHIER_PERMISSIONS);
        assertThatThrownBy(() -> tills.close(till.getId(), money("5000.00"), null))
                .isInstanceOf(Errors.ForbiddenException.class);

        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        TillSession closed = tills.close(till.getId(), money("5000.00"), null);
        assertThat(closed.getVariance()).isEqualByComparingTo("0");
        assertThat(closed.getClosedBy()).isEqualTo(SUPERVISOR);
    }

    @Test
    void aDropCannotTakeMoreThanTheDrawerHolds() {
        TillSession till = openTill();
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);

        assertThatThrownBy(() -> tills.recordDrop(till.getId(), money("5000.01"), "Too much", null))
                .isInstanceOf(Errors.BusinessRuleException.class);
    }

    @Test
    void aClosedShiftTakesNoMoreSales() {
        TillSession till = openTill();
        tills.close(till.getId(), money("5000.00"), null);

        assertThatThrownBy(() -> carts.open(till.getId(), null, false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tills.close(till.getId(), money("5000.00"), null))
                .isInstanceOf(Errors.ConflictException.class);
    }

    // --- helpers ----------------------------------------------------------------

    /** The open shift on this test's register: the drawer a refund is paid from. */
    private UUID shift() {
        return tills.requireOpenForRegister(REGISTER).getId();
    }

    private TillSession openTill() {
        return tills.open(BRANCH, REGISTER, money("5000.00"));
    }

    private Sale paidSale(TillSession till, FakeCatalog.Product product, String quantity) {
        Cart cart = carts.open(till.getId(), null, false);
        cart =
                carts.addLine(
                        cart.getId(),
                        product.id(),
                        product.sku(),
                        null,
                        new BigDecimal(quantity),
                        false,
                        TOKEN);
        Sale sale = checkout.checkout(cart.getId(), null, TOKEN);
        return checkout.tender(
                sale.getId(),
                List.of(
                        new CheckoutService.Tender(
                                PaymentMethod.CASH, sale.getGrandTotal(), null, null)),
                sale.getGrandTotal());
    }
}
