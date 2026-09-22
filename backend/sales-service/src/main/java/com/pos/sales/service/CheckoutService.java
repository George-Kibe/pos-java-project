package com.pos.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.client.InventoryClient;
import com.pos.sales.domain.Cart;
import com.pos.sales.domain.CartLine;
import com.pos.sales.domain.CartStatus;
import com.pos.sales.domain.PaymentStatus;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleLine;
import com.pos.sales.domain.SalePayment;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.totals.SaleTotals;
import com.pos.sales.domain.totals.SaleTotalsCalculator;
import com.pos.sales.messaging.SalesEventPublisher;
import com.pos.sales.repository.CartRepository;
import com.pos.sales.repository.SaleRepository;

import lombok.RequiredArgsConstructor;

/**
 * Turning a basket into a sale, and getting paid for it.
 *
 * <p>The saga, in one place:
 *
 * <pre>
 *   checkout  → sale PENDING, totals revalidated against catalog
 *   tender    → cash settles at the till; a provider tender goes AWAITING_PAYMENT and asks
 *   authorised→ PAID, receipt number assigned, sale-completed published
 *   failed    → CANCELLED, reservations released, nothing published as sold
 * </pre>
 *
 * <p>There is no distributed transaction and compensation is explicit. A late authorisation for a
 * sale that has already been cancelled is detected rather than applied, because applying it would
 * mark a sale paid whose stock was never deducted and whose customer has left.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final SaleRepository sales;
    private final CartRepository carts;
    private final CartService cartService;
    private final PricingService pricing;
    private final ReceiptNumberService receiptNumbers;
    private final ReceiptService receipts;
    private final SalesEventPublisher events;
    private final InventoryClient inventory;

    /** One tender at checkout. */
    public record Tender(
            PaymentMethod method,
            BigDecimal amount,
            String phoneNumber,
            String terminalReference) {}

    public Sale require(UUID id) {
        return sales.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Sale", id));
    }

    /**
     * Creates the sale from the basket, with the server's totals.
     *
     * <p>The basket is repriced from catalog first, however recently it was priced. A promotion can
     * start or a price list change between the first scan and the last, and the customer must be
     * charged what is true at the moment they pay.
     */
    @Transactional
    public Sale checkout(UUID cartId, BigDecimal clientGrandTotal, String authorization) {
        Cart cart = cartService.require(cartId);

        if (cart.getStatus() == CartStatus.CHECKED_OUT) {
            throw new Errors.ConflictException(
                    "cart.already_checked_out", "This basket has already been rung up");
        }
        List<CartLine> lines = cart.activeLines();
        if (lines.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "cart.empty", "There is nothing in this basket to sell");
        }

        pricing.applyPrices(lines, cart.getBranchId(), cart.isMember(), authorization);
        cartService.retotal(cart);

        SaleTotals totals = SaleTotalsCalculator.total(PricingService.toPricedLines(lines));

        Sale sale = new Sale(cart.getBranchId(), actor(cart));
        sale.setCart(cart);
        sale.setTillSession(cart.getTillSession());
        sale.setRegisterId(cart.getRegisterId());
        sale.setCustomerId(cart.getCustomerId());
        sale.setMember(cart.isMember());
        sale.setCurrency(cart.getCurrency());
        sale.setNetTotal(totals.net());
        sale.setTaxTotal(totals.tax());
        sale.setDiscountTotal(totals.discount());
        sale.setGrandTotal(totals.grand());
        sale.setReservationReference(cart.getId());
        sale.setOccurredAt(Instant.now());

        // The client's figure is advisory. A disagreement is recorded, never trusted and never
        // silently discarded: it means the terminal was working from something stale.
        sale.setClientGrandTotal(clientGrandTotal);
        if (clientGrandTotal != null && !totals.agreesWith(clientGrandTotal)) {
            sale.setPriceVarianceFlagged(true);
            sale.setPriceVarianceAmount(totals.varianceAgainst(clientGrandTotal));
            log.warn(
                    "Cart {} priced {} at the server against {} at the terminal",
                    cartId,
                    totals.grand(),
                    clientGrandTotal);
        }

        for (CartLine line : lines) {
            sale.addLine(SaleLine.from(line));
        }

        cart.setStatus(CartStatus.CHECKED_OUT);
        carts.save(cart);
        return sales.save(sale);
    }

    /**
     * Takes payment.
     *
     * <p>Cash is settled here and now - it is in the drawer, and nothing asynchronous can change
     * that. Every other method is a request to a provider, so the sale goes AWAITING_PAYMENT and
     * waits for an event. A basket paid part cash and part M-Pesa does both.
     */
    @Transactional
    public Sale tender(UUID saleId, List<Tender> tenders, BigDecimal amountTendered) {
        Sale sale = require(saleId);

        if (sale.getStatus() == SaleStatus.PAID) {
            throw new Errors.ConflictException(
                    "sale.already_paid", "This sale has already been paid");
        }
        if (!sale.getStatus().canTransitionTo(SaleStatus.AWAITING_PAYMENT)
                && sale.getStatus() != SaleStatus.AWAITING_PAYMENT) {
            throw new Errors.ConflictException(
                    "sale.not_payable",
                    "A %s sale cannot take payment".formatted(sale.getStatus()));
        }
        if (tenders == null || tenders.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "sale.no_tender", "Taking payment needs at least one tender");
        }

        BigDecimal outstanding = sale.outstanding();
        BigDecimal offered =
                tenders.stream().map(Tender::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (offered.compareTo(outstanding) < 0) {
            throw new Errors.BusinessRuleException(
                    "sale.underpaid",
                    "Tendered %s against %s outstanding".formatted(offered, outstanding));
        }

        // Change is only ever handed back in cash. A card or M-Pesa amount above what is owed
        // cannot be "changed", so it is refused rather than silently over-charged.
        BigDecimal nonCash =
                tenders.stream()
                        .filter(tender -> tender.method() != PaymentMethod.CASH)
                        .map(Tender::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (nonCash.compareTo(outstanding) > 0) {
            throw new Errors.BusinessRuleException(
                    "sale.non_cash_overpayment",
                    "Card and M-Pesa tenders come to %s against %s owed; change can only be given"
                                    .formatted(nonCash, outstanding)
                            + " in cash");
        }

        BigDecimal cashHandedOver =
                tenders.stream()
                        .filter(tender -> tender.method() == PaymentMethod.CASH)
                        .map(Tender::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal change = offered.subtract(outstanding);
        BigDecimal changeStillToTake = change;

        boolean awaitingProvider = false;

        for (Tender tender : tenders) {
            BigDecimal applied = tender.amount();

            // The cash payment records what stayed in the drawer, not the notes handed over:
            // recording 1,100 for a 1,052.54 sale would make the drawer read 47.46 short at close,
            // on every sale that needed change.
            if (tender.method() == PaymentMethod.CASH && changeStillToTake.signum() > 0) {
                BigDecimal fromThis = changeStillToTake.min(applied);
                applied = applied.subtract(fromThis);
                changeStillToTake = changeStillToTake.subtract(fromThis);
            }
            if (applied.signum() == 0) {
                continue;
            }

            SalePayment payment = new SalePayment(tender.method(), applied, sale.getCurrency());
            payment.setTerminalReference(tender.terminalReference());
            payment.setPhoneNumberMasked(mask(tender.phoneNumber()));
            sale.addPayment(payment);

            if (payment.isSettledAtTheTill()) {
                payment.authorize(applied, null, null);
            } else {
                awaitingProvider = true;
                events.paymentRequested(sale, payment, tender.phoneNumber());
            }
        }

        if (cashHandedOver.signum() > 0) {
            sale.setAmountTendered(amountTendered != null ? amountTendered : cashHandedOver);
            // What physically leaves the drawer, so cents: the 4dp ledger figure is kept on the
            // payments, and the sub-cent difference is the only rounding the drawer ever sees.
            sale.setChangeGiven(change.setScale(2, RoundingMode.HALF_UP));
        }

        if (awaitingProvider) {
            sale.setStatus(SaleStatus.AWAITING_PAYMENT);
            return sales.save(sale);
        }
        return complete(sale);
    }

    /**
     * Marks a sale paid, numbers its receipt and announces it.
     *
     * <p>The receipt number is taken last, inside this transaction, so a sale that fails to commit
     * leaves no gap in the sequence.
     */
    @Transactional
    public Sale complete(Sale sale) {
        if (!sale.isFullyPaid()) {
            throw new Errors.BusinessRuleException(
                    "sale.not_fully_paid",
                    "%s of this sale is still outstanding".formatted(sale.outstanding()));
        }

        sale.setStatus(SaleStatus.PAID);
        sale.setCompletedAt(Instant.now());
        sale.setReceiptNumber(receiptNumbers.next(sale.getBranchId()));

        if (sale.getTillSession() != null) {
            BigDecimal cash = sale.cashPortion();
            sale.getTillSession().recordSale(cash, sale.getGrandTotal().subtract(cash));
        }

        Sale paid = sales.save(sale);
        receipts.issueFor(paid);
        events.saleCompleted(paid);

        log.info(
                "Sale {} paid: {} {} at branch {}",
                paid.getReceiptNumber(),
                paid.getCurrency(),
                paid.getGrandTotal(),
                paid.getBranchId());
        return paid;
    }

    /**
     * Compensation: the payment did not happen.
     *
     * <p>Cancels the sale and announces it, so inventory releases the stock the basket held.
     * Nothing is published as sold, so no consumer ever sees a sale that was not paid for.
     */
    @Transactional
    public Sale cancel(UUID saleId, String reason) {
        Sale sale = require(saleId);

        if (sale.getStatus() == SaleStatus.PAID) {
            throw new Errors.ConflictException(
                    "sale.already_paid", "A paid sale is voided, not cancelled");
        }
        if (sale.getStatus() == SaleStatus.CANCELLED) {
            return sale;
        }

        sale.setStatus(SaleStatus.CANCELLED);
        sale.setCancelledAt(Instant.now());
        sale.setCancellationReason(reason);
        sale.getPayments().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .forEach(payment -> payment.fail("SALE_CANCELLED", reason));

        // Released by inventory on the event, in this transaction's outbox. A direct call would
        // need the caller's token, and a cancellation from a payment event has no caller.
        Sale cancelled = sales.save(sale);
        events.saleCancelled(cancelled);
        return cancelled;
    }

    /**
     * Reverses a paid sale, with a supervisor's approval.
     *
     * <p>The sale stays; the void is a new fact about it. Inventory puts the stock back when it
     * sees the event, and reporting takes the takings out.
     */
    @Transactional
    public Sale voidSale(UUID saleId, String reason, UUID approvedBy) {
        Sale sale = require(saleId);

        if (!sale.getStatus().canTransitionTo(SaleStatus.VOIDED)) {
            throw new Errors.ConflictException(
                    "sale.not_voidable", "A %s sale cannot be voided".formatted(sale.getStatus()));
        }
        if (reason == null || reason.isBlank()) {
            throw new Errors.BusinessRuleException(
                    "sale.void_reason_required", "A void needs a reason");
        }
        if (sale.getTillSession() != null && !sale.getTillSession().getStatus().acceptsSales()) {
            // A void puts the cash back through the shift that took it. Once that shift is being
            // counted or closed, changing its takings would move a drawer nobody can recount: the
            // customer has gone, and what is left is a return.
            throw new Errors.ConflictException(
                    "sale.shift_closed",
                    "The shift that took this sale is %s; process a return instead"
                            .formatted(sale.getTillSession().getStatus()));
        }

        sale.setStatus(SaleStatus.VOIDED);
        sale.setVoidedAt(Instant.now());
        sale.setVoidedBy(currentActor());
        sale.setVoidApprovedBy(approvedBy);
        sale.setVoidReason(reason);

        if (sale.getTillSession() != null) {
            // The takings go back out of the shift, or the drawer will read as over at close.
            BigDecimal cash = sale.cashPortion();
            sale.getTillSession().recordRefund(cash);
        }

        Sale voided = sales.save(sale);
        events.saleVoided(voided);
        return voided;
    }

    private static UUID actor(Cart cart) {
        UUID current = currentActor();
        return current != null ? current : cart.getCashierId();
    }

    private static UUID currentActor() {
        return AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);
    }

    /**
     * Keeps the last four digits only.
     *
     * <p>A full customer phone number is never stored or logged. Four digits is enough for a
     * cashier to confirm the right phone was prompted, and not enough to be personal data worth
     * stealing.
     */
    static String mask(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return null;
        }
        return "*".repeat(Math.max(0, phoneNumber.length() - 4))
                + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
