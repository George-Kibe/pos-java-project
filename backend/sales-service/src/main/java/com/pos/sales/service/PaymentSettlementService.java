package com.pos.sales.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.events.payments.PaymentAuthorizedPayload;
import com.pos.events.payments.PaymentFailedPayload;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SalePayment;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.repository.SalePaymentRepository;
import com.pos.sales.repository.SaleRepository;

import lombok.RequiredArgsConstructor;

/**
 * What to do when a payment provider finally answers.
 *
 * <p>Separated from the listener so the decisions are testable without Kafka, and because the
 * awkward cases are the point of this class rather than an afterthought:
 *
 * <ul>
 *   <li>An authorisation for a sale that has already been cancelled is <b>not</b> applied. Marking
 *       it paid would create a sale whose stock was never deducted and whose customer left without
 *       goods; the money is real, so it is flagged for manual reconciliation instead.
 *   <li>A partial authorisation does not complete the sale. The lane is told what is still
 *       outstanding rather than the sale silently completing short.
 *   <li>An answer about a payment this service has never heard of is logged and ignored, not
 *       retried forever - it belongs to another environment or a sale that was never recorded.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PaymentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);

    private final SalePaymentRepository payments;
    private final SaleRepository sales;
    private final CheckoutService checkout;

    @Transactional
    public void authorize(PaymentAuthorizedPayload authorized) {
        Optional<SalePayment> found = payments.findByPaymentIntentId(authorized.paymentIntentId());

        if (found.isEmpty()) {
            log.warn(
                    "Authorisation for unknown payment intent {} on sale {}; ignoring",
                    authorized.paymentIntentId(),
                    authorized.saleId());
            return;
        }

        SalePayment payment = found.get();
        Sale sale = payment.getSale();

        if (sale.getStatus() == SaleStatus.CANCELLED) {
            // The customer has gone and the stock was never deducted, so this cannot be applied.
            // It is real money, though, so it is recorded loudly rather than dropped.
            log.error(
                    "Late authorisation {} for cancelled sale {} ({} {}): needs manual"
                            + " reconciliation",
                    authorized.providerReference(),
                    sale.getId(),
                    authorized.currency(),
                    authorized.amountAuthorized());
            payment.authorize(
                    authorized.amountAuthorized(),
                    authorized.providerReference(),
                    authorized.approvalCode());
            payment.setFailureReason("LATE_AUTHORIZATION_ON_CANCELLED_SALE");
            payments.save(payment);
            return;
        }

        if (sale.getStatus() == SaleStatus.PAID) {
            log.debug("Sale {} is already paid; authorisation is a no-op", sale.getId());
            return;
        }

        payment.authorize(
                authorized.amountAuthorized(),
                authorized.providerReference(),
                authorized.approvalCode());
        payments.save(payment);

        if (sale.isFullyPaid()) {
            checkout.complete(sale);
        } else {
            // A partial M-Pesa payment. The lane asks for the rest rather than the sale quietly
            // completing for less than it is worth.
            BigDecimal outstanding = sale.outstanding();
            log.info(
                    "Sale {} part paid; {} {} still outstanding",
                    sale.getId(),
                    sale.getCurrency(),
                    outstanding);
            sales.save(sale);
        }
    }

    @Transactional
    public void fail(PaymentFailedPayload failed) {
        Optional<SalePayment> found = payments.findByPaymentIntentId(failed.paymentIntentId());

        if (found.isEmpty()) {
            log.warn(
                    "Failure for unknown payment intent {} on sale {}; ignoring",
                    failed.paymentIntentId(),
                    failed.saleId());
            return;
        }

        SalePayment payment = found.get();
        Sale sale = payment.getSale();

        payment.fail(failed.reasonCode(), failed.providerMessage());
        payments.save(payment);

        if (sale.getStatus() == SaleStatus.PAID) {
            // Another tender already covered it - a cash top-up after a declined card, say. The
            // sale stands and only this tender failed.
            log.info(
                    "Payment {} failed on an already paid sale {}; the sale stands",
                    failed.paymentIntentId(),
                    sale.getId());
            return;
        }

        // Compensation: no money, so no sale. Nothing was ever published as sold.
        checkout.cancel(sale.getId(), "Payment failed: " + failed.reasonCode(), null);
    }
}
