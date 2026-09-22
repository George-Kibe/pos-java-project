package com.pos.customer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.customer.config.LoyaltyProperties;
import com.pos.customer.domain.Customer;
import com.pos.customer.domain.LoyaltyAccount;
import com.pos.customer.domain.LoyaltyLotTake;
import com.pos.customer.domain.LoyaltyTransaction;
import com.pos.customer.domain.LoyaltyTransactionType;
import com.pos.customer.domain.MembershipTier;
import com.pos.customer.domain.policy.PointsLots;
import com.pos.customer.domain.policy.TierLadder;
import com.pos.customer.messaging.CustomerEventPublisher;
import com.pos.customer.repository.LoyaltyAccountRepository;
import com.pos.customer.repository.LoyaltyLotTakeRepository;
import com.pos.customer.repository.LoyaltyTransactionRepository;
import com.pos.customer.repository.MembershipTierRepository;

import lombok.RequiredArgsConstructor;

/**
 * Every movement of points, and the tier that follows from them.
 *
 * <p>Two rules run through all of it. The balance is never set, only moved, and every move is a row
 * saying why - points are money owed, and "where did my points go" must be answerable a year later.
 * And every move is idempotent on the thing that caused it: a sale accrues once however many times
 * its event is delivered, a payment intent is redeemed once, a return claws back once.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository ledger;
    private final LoyaltyLotTakeRepository lotTakes;
    private final MembershipTierRepository tiers;
    private final LoyaltyProperties properties;
    private final CustomerEventPublisher events;
    private final Clock clock;

    /**
     * What came of a redemption.
     *
     * <p>A result rather than an exception on purpose: the caller runs inside a transaction, and an
     * exception thrown here - even one it catches - marks that transaction rollback-only, so the
     * failure it then tries to publish would roll back with it and the tender would be answered by
     * nobody.
     */
    public sealed interface Redemption {
        record Spent(LoyaltyTransaction transaction) implements Redemption {}

        record NotEnoughPoints(long available, long required) implements Redemption {}
    }

    public LoyaltyAccount require(UUID customerId) {
        return accounts.findByCustomerId(customerId)
                .orElseThrow(() -> Errors.NotFoundException.of("Loyalty account", customerId));
    }

    public Optional<LoyaltyAccount> find(UUID customerId) {
        return accounts.findByCustomerId(customerId);
    }

    public Page<LoyaltyTransaction> history(UUID accountId, Pageable pageable) {
        return ledger.findByAccountIdOrderByOccurredAtDesc(accountId, pageable);
    }

    public List<LoyaltyTransaction> allFor(UUID customerId) {
        return ledger.findByCustomerIdOrderByOccurredAt(customerId);
    }

    /** The ladder as configured, lowest rung first. */
    public List<MembershipTier> activeTiers() {
        return tiers.findByActiveTrueOrderBySortOrder();
    }

    public Optional<MembershipTier> tierOf(LoyaltyAccount account) {
        return account.getTierId() == null ? Optional.empty() : tiers.findById(account.getTierId());
    }

    /** Points that lapse within the given window, so a receipt or a screen can warn about them. */
    public long expiringBefore(UUID accountId, Instant before) {
        return ledger.lotsOf(accountId).stream()
                .filter(lot -> lot.getExpiresAt() != null && lot.getExpiresAt().isBefore(before))
                .mapToLong(LoyaltyTransaction::getPointsRemaining)
                .sum();
    }

    @Transactional
    public LoyaltyAccount openAccountFor(Customer customer) {
        return accounts.findByCustomerId(customer.getId())
                .orElseGet(
                        () -> {
                            LoyaltyAccount account = new LoyaltyAccount(customer.getId());
                            groundFloor().ifPresent(tier -> account.setTierId(tier.getId()));
                            account.setLastEvaluatedAt(clock.instant());
                            return accounts.save(account);
                        });
    }

    // --- earning ----------------------------------------------------------------

    /**
     * Points for a sale.
     *
     * <p>The unique index on {@code (sale_id)} for accruals is the real guard: two deliveries
     * racing each other cannot both insert, whatever the check below sees.
     *
     * @return the accrual, or empty when this sale has already earned
     */
    @Transactional
    public Optional<LoyaltyTransaction> accrue(
            UUID customerId, UUID saleId, UUID branchId, BigDecimal spend, String currency) {

        if (ledger.findBySaleIdAndType(saleId, LoyaltyTransactionType.ACCRUAL).isPresent()) {
            log.debug("Sale {} has already earned points", saleId);
            return Optional.empty();
        }
        LoyaltyAccount account =
                accounts.lockByCustomerId(customerId)
                        .orElseThrow(
                                () -> Errors.NotFoundException.of("Loyalty account", customerId));

        BigDecimal multiplier =
                tierOf(account).map(MembershipTier::getPointsMultiplier).orElse(BigDecimal.ONE);
        long points = properties.policy().pointsFor(spend, multiplier);

        LoyaltyTransaction accrual =
                new LoyaltyTransaction(account, LoyaltyTransactionType.ACCRUAL, points);
        accrual.setAmount(spend);
        accrual.setCurrency(currency == null ? account.getCurrency() : currency);
        accrual.setSaleId(saleId);
        accrual.setBranchId(branchId);
        if (points > 0) {
            accrual.setExpiresAt(
                    clock.instant().plus(properties.expiryMonths() * 30L, ChronoUnit.DAYS));
        }
        account.apply(points);
        accrual.setBalanceAfter(account.getPointsBalance());
        LoyaltyTransaction saved = ledger.save(accrual);
        accounts.save(account);

        if (points > 0) {
            events.accrued(account, saved, tierCodeOf(account));
        }
        evaluateTier(account);
        return Optional.of(saved);
    }

    // --- spending ---------------------------------------------------------------

    /**
     * Spends points to settle a tender.
     *
     * @throws InsufficientPointsException when the balance cannot cover it - the sale is told, and
     *     compensates, rather than the tender hanging
     */
    @Transactional
    public Redemption redeem(
            UUID customerId,
            UUID paymentIntentId,
            UUID saleId,
            UUID branchId,
            BigDecimal amount,
            String currency) {

        Optional<LoyaltyTransaction> already =
                ledger.findByPaymentIntentIdAndType(
                        paymentIntentId, LoyaltyTransactionType.REDEMPTION);
        if (already.isPresent()) {
            return new Redemption.Spent(already.get());
        }

        LoyaltyAccount account =
                accounts.lockByCustomerId(customerId)
                        .orElseThrow(
                                () -> Errors.NotFoundException.of("Loyalty account", customerId));
        long needed = properties.policy().pointsToCover(amount);
        if (account.getPointsBalance() < needed) {
            return new Redemption.NotEnoughPoints(account.getPointsBalance(), needed);
        }

        LoyaltyTransaction redemption =
                new LoyaltyTransaction(account, LoyaltyTransactionType.REDEMPTION, -needed);
        redemption.setAmount(amount);
        redemption.setCurrency(currency == null ? account.getCurrency() : currency);
        redemption.setPaymentIntentId(paymentIntentId);
        redemption.setSaleId(saleId);
        redemption.setBranchId(branchId);
        account.apply(-needed);
        redemption.setBalanceAfter(account.getPointsBalance());
        LoyaltyTransaction saved = ledger.save(redemption);
        recordTakes(saved, spendLots(account, needed));
        accounts.save(account);
        return new Redemption.Spent(saved);
    }

    /**
     * Gives back what a cancelled or voided sale spent.
     *
     * <p>The points return as a fresh lot carrying the original expiry: a customer whose sale fell
     * through is no worse off, and no better - the clock does not restart.
     */
    @Transactional
    public List<LoyaltyTransaction> reverseRedemptions(UUID saleId, String reason) {
        List<LoyaltyTransaction> redemptions =
                ledger.findBySaleIdAndTypeIn(saleId, List.of(LoyaltyTransactionType.REDEMPTION));
        List<LoyaltyTransaction> reversals =
                ledger.findBySaleIdAndTypeIn(saleId, List.of(LoyaltyTransactionType.REVERSAL));

        return redemptions.stream()
                .filter(
                        redemption ->
                                reversals.stream()
                                        .noneMatch(
                                                reversal ->
                                                        redemption
                                                                .getPaymentIntentId()
                                                                .equals(
                                                                        reversal
                                                                                .getPaymentIntentId())))
                .map(redemption -> reverse(redemption, reason))
                .toList();
    }

    /**
     * Puts a redemption's points back into the lots they came out of.
     *
     * <p>Back into the same lots, with the expiry they had: a member whose sale fell through is
     * left exactly as they were, neither given a fresh year nor charged for the delay. A lot that
     * has lapsed in the meantime cannot be refilled, so those points come back as a new lot
     * carrying the old expiry - which the next sweep will write off, as it would have anyway.
     */
    private LoyaltyTransaction reverse(LoyaltyTransaction redemption, String reason) {
        LoyaltyAccount account =
                accounts.lockById(redemption.getAccountId())
                        .orElseThrow(
                                () ->
                                        Errors.NotFoundException.of(
                                                "Loyalty account", redemption.getAccountId()));
        long points = Math.abs(redemption.getPoints());
        Instant now = clock.instant();
        long strandedPoints = 0;
        Instant strandedExpiry = null;

        for (LoyaltyLotTake take : lotTakes.findByTransactionId(redemption.getId())) {
            LoyaltyTransaction lot = ledger.findById(take.getLotId()).orElse(null);
            if (lot == null) {
                strandedPoints += take.getPoints();
                continue;
            }
            if (lot.getExpiresAt() == null || lot.getExpiresAt().isAfter(now)) {
                lot.setPointsRemaining(lot.getPointsRemaining() + take.getPoints());
                ledger.save(lot);
            } else {
                strandedPoints += take.getPoints();
                strandedExpiry = lot.getExpiresAt();
            }
        }

        LoyaltyTransaction reversal =
                new LoyaltyTransaction(account, LoyaltyTransactionType.REVERSAL, points);
        reversal.setAmount(redemption.getAmount());
        reversal.setCurrency(redemption.getCurrency());
        reversal.setSaleId(redemption.getSaleId());
        reversal.setPaymentIntentId(redemption.getPaymentIntentId());
        reversal.setBranchId(redemption.getBranchId());
        reversal.setReason(reason);
        // The points are back in their own lots; this row only records that they moved. Only
        // points whose lot is gone need a lot of their own here.
        reversal.setPointsRemaining(strandedPoints);
        reversal.setExpiresAt(strandedExpiry);
        account.apply(points);
        reversal.setBalanceAfter(account.getPointsBalance());
        LoyaltyTransaction saved = ledger.save(reversal);
        accounts.save(account);
        return saved;
    }

    /**
     * Takes back the points a returned sale earned, in proportion to what went back.
     *
     * <p>Capped at the balance: a member who has already spent them is not pushed negative. The
     * shortfall is recorded in the reason rather than pursued - chasing a customer for points is
     * not worth the goodwill.
     */
    @Transactional
    public Optional<LoyaltyTransaction> clawBack(
            UUID returnId, UUID saleId, BigDecimal refundTotal, String currency) {

        if (ledger.findByReturnIdAndType(returnId, LoyaltyTransactionType.CLAWBACK).isPresent()) {
            return Optional.empty();
        }
        Optional<LoyaltyTransaction> accrual =
                ledger.findBySaleIdAndType(saleId, LoyaltyTransactionType.ACCRUAL);
        if (accrual.isEmpty()) {
            return Optional.empty(); // the sale earned nothing; nothing to take back
        }
        LoyaltyTransaction earned = accrual.get();
        LoyaltyAccount account =
                accounts.lockById(earned.getAccountId())
                        .orElseThrow(
                                () ->
                                        Errors.NotFoundException.of(
                                                "Loyalty account", earned.getAccountId()));

        long share = proportionalPoints(earned, refundTotal);
        long taken = Math.min(share, account.getPointsBalance());
        List<PointsLots.Take> takes = List.of();
        String note =
                taken < share
                        ? "Refund on sale %s; %d of %d points already spent"
                                .formatted(saleId, share - taken, share)
                        : "Refund on sale " + saleId;

        if (taken > 0) {
            takes = spendLots(account, taken);
            account.apply(-taken);
        }
        LoyaltyTransaction clawback =
                new LoyaltyTransaction(account, LoyaltyTransactionType.CLAWBACK, -taken);
        clawback.setAmount(refundTotal);
        clawback.setCurrency(currency == null ? account.getCurrency() : currency);
        clawback.setSaleId(saleId);
        clawback.setReturnId(returnId);
        clawback.setReason(note);
        clawback.setBalanceAfter(account.getPointsBalance());
        LoyaltyTransaction saved = ledger.save(clawback);
        recordTakes(saved, takes);
        accounts.save(account);
        evaluateTier(account);
        return Optional.of(saved);
    }

    /**
     * Takes back everything a voided sale earned.
     *
     * <p>A void is not a return: the whole sale is undone, so the whole accrual goes, and the
     * claw-back references the sale rather than a return - there is none.
     */
    @Transactional
    public Optional<LoyaltyTransaction> clawBackVoidedSale(UUID saleId, String reason) {
        if (ledger.findBySaleIdAndTypeAndReturnIdIsNull(saleId, LoyaltyTransactionType.CLAWBACK)
                .isPresent()) {
            return Optional.empty();
        }
        Optional<LoyaltyTransaction> accrual =
                ledger.findBySaleIdAndType(saleId, LoyaltyTransactionType.ACCRUAL);
        if (accrual.isEmpty()) {
            return Optional.empty();
        }
        LoyaltyTransaction earned = accrual.get();
        LoyaltyAccount account =
                accounts.lockById(earned.getAccountId())
                        .orElseThrow(
                                () ->
                                        Errors.NotFoundException.of(
                                                "Loyalty account", earned.getAccountId()));

        long taken = Math.min(earned.getPoints(), account.getPointsBalance());
        List<PointsLots.Take> takes = List.of();
        if (taken > 0) {
            takes = spendLots(account, taken);
            account.apply(-taken);
        }
        LoyaltyTransaction clawback =
                new LoyaltyTransaction(account, LoyaltyTransactionType.CLAWBACK, -taken);
        clawback.setAmount(earned.getAmount());
        clawback.setCurrency(earned.getCurrency());
        clawback.setSaleId(saleId);
        clawback.setReason(reason);
        clawback.setBalanceAfter(account.getPointsBalance());
        LoyaltyTransaction saved = ledger.save(clawback);
        recordTakes(saved, takes);
        accounts.save(account);
        evaluateTier(account);
        return Optional.of(saved);
    }

    /** A person moves points, and says why. */
    @Transactional
    public LoyaltyTransaction adjust(UUID customerId, long points, String reason) {
        if (points == 0) {
            throw new Errors.BusinessRuleException(
                    "loyalty.no_adjustment", "An adjustment moves points; zero does nothing");
        }
        LoyaltyAccount account =
                accounts.lockByCustomerId(customerId)
                        .orElseThrow(
                                () -> Errors.NotFoundException.of("Loyalty account", customerId));
        if (points < 0 && account.getPointsBalance() < -points) {
            throw new Errors.BusinessRuleException(
                    "loyalty.insufficient_points",
                    "The balance is %d points; %d cannot be taken"
                            .formatted(account.getPointsBalance(), -points));
        }
        List<PointsLots.Take> takes = points < 0 ? spendLots(account, -points) : List.of();
        LoyaltyTransaction adjustment =
                new LoyaltyTransaction(account, LoyaltyTransactionType.ADJUSTMENT, points);
        adjustment.setReason(reason);
        adjustment.setActorId(
                AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null));
        if (points > 0) {
            adjustment.setExpiresAt(
                    clock.instant().plus(properties.expiryMonths() * 30L, ChronoUnit.DAYS));
        }
        account.apply(points);
        adjustment.setBalanceAfter(account.getPointsBalance());
        LoyaltyTransaction saved = ledger.save(adjustment);
        recordTakes(saved, takes);
        accounts.save(account);
        return saved;
    }

    /** Clears what is owed to someone who has asked to be forgotten. */
    @Transactional
    public void writeOffOnErasure(UUID customerId, String reason) {
        accounts.lockByCustomerId(customerId)
                .filter(account -> account.getPointsBalance() > 0)
                .ifPresent(
                        account ->
                                adjust(
                                        customerId,
                                        -account.getPointsBalance(),
                                        "Written off on erasure: " + reason));
    }

    // --- expiry and tiers ---------------------------------------------------------

    /** Writes off lots that have lapsed. Returns how many rows it wrote. */
    @Transactional
    public int expireLapsed(int batch) {
        List<LoyaltyTransaction> lapsed =
                ledger.lapsedLots(clock.instant(), PageRequest.of(0, batch));
        int written = 0;
        for (LoyaltyTransaction lot : lapsed) {
            LoyaltyAccount account = accounts.lockById(lot.getAccountId()).orElse(null);
            if (account == null) {
                continue;
            }
            long points = lot.getPointsRemaining();
            lot.take(points);
            ledger.save(lot);

            LoyaltyTransaction expiry =
                    new LoyaltyTransaction(account, LoyaltyTransactionType.EXPIRY, -points);
            expiry.setReason("Points earned %s lapsed".formatted(lot.getOccurredAt()));
            account.apply(-points);
            expiry.setBalanceAfter(account.getPointsBalance());
            ledger.save(expiry);
            accounts.save(account);
            written++;
        }
        if (written > 0) {
            log.info("Expired points on {} lot(s)", written);
        }
        return written;
    }

    /**
     * Places the account on the rung its rolling spend reaches.
     *
     * <p>Rolling, so it falls as well as rises: a member who stops shopping comes back down, which
     * is what makes a tier mean anything.
     */
    @Transactional
    public Optional<MembershipTier> evaluateTier(LoyaltyAccount account) {
        Instant since = clock.instant().minus(properties.windowMonths() * 30L, ChronoUnit.DAYS);
        BigDecimal rollingSpend = ledger.spendSince(account.getId(), since);
        rollingSpend = rollingSpend == null ? BigDecimal.ZERO : rollingSpend.max(BigDecimal.ZERO);

        List<MembershipTier> ladder = tiers.findByActiveTrueOrderBySortOrder();
        Optional<MembershipTier> earned =
                TierLadder.forSpend(
                                rollingSpend, ladder.stream().map(MembershipTier::toRung).toList())
                        .flatMap(rung -> byCode(ladder, rung.code()));

        UUID previousId = account.getTierId();
        account.setRollingSpend(rollingSpend);
        account.setLastEvaluatedAt(clock.instant());
        earned.ifPresent(tier -> account.setTierId(tier.getId()));
        accounts.save(account);

        if (earned.isPresent() && !earned.get().getId().equals(previousId)) {
            MembershipTier tier = earned.get();
            Optional<MembershipTier> previous =
                    previousId == null ? Optional.empty() : tiers.findById(previousId);
            boolean upgrade =
                    previous.isEmpty()
                            || tier.getMinimumRollingSpend()
                                            .compareTo(previous.get().getMinimumRollingSpend())
                                    > 0;
            events.tierChanged(
                    account, previous.map(MembershipTier::getCode).orElse(null), tier, upgrade);
            log.info(
                    "Customer {} moved to {} on rolling spend {}",
                    account.getCustomerId(),
                    tier.getCode(),
                    rollingSpend);
        }
        return earned;
    }

    /** Re-judges accounts nobody has looked at lately, so a tier can fall as spend ages out. */
    @Transactional
    public int evaluateStaleTiers(int batch) {
        Instant cutoff =
                clock.instant()
                        .minus(
                                properties.tierEvaluationInterval() == null
                                        ? java.time.Duration.ofDays(1)
                                        : properties.tierEvaluationInterval());
        List<LoyaltyAccount> stale =
                accounts.findByLastEvaluatedAtBeforeOrLastEvaluatedAtIsNull(
                        cutoff, PageRequest.of(0, batch));
        stale.forEach(this::evaluateTier);
        return stale.size();
    }

    // --- helpers ------------------------------------------------------------------

    /** Takes points out of the lots, soonest to expire first, and says which it took from. */
    private List<PointsLots.Take> spendLots(LoyaltyAccount account, long points) {
        List<PointsLots.Lot> lots =
                ledger.lotsOf(account.getId()).stream()
                        .map(
                                row ->
                                        new PointsLots.Lot(
                                                row.getId(),
                                                row.getPointsRemaining(),
                                                row.getExpiresAt()))
                        .toList();
        PointsLots.Spend spend = PointsLots.spend(points, lots);
        if (spend.shortfall() > 0) {
            // The cached balance and the lots disagree: refuse rather than hand out points twice.
            throw new IllegalStateException(
                    "Account %s has %d points in lots, %d were needed"
                            .formatted(account.getId(), points - spend.shortfall(), points));
        }
        for (PointsLots.Take take : spend.takes()) {
            LoyaltyTransaction lot =
                    ledger.findById(take.transactionId())
                            .orElseThrow(
                                    () ->
                                            Errors.NotFoundException.of(
                                                    "Loyalty lot", take.transactionId()));
            lot.take(take.points());
            ledger.save(lot);
        }
        return spend.takes();
    }

    private void recordTakes(LoyaltyTransaction spending, List<PointsLots.Take> takes) {
        takes.forEach(
                take ->
                        lotTakes.save(
                                new LoyaltyLotTake(
                                        spending.getId(), take.transactionId(), take.points())));
    }

    private static long proportionalPoints(LoyaltyTransaction accrual, BigDecimal refund) {
        if (accrual.getPoints() <= 0
                || accrual.getAmount() == null
                || accrual.getAmount().signum() <= 0) {
            return 0;
        }
        BigDecimal share =
                refund.min(accrual.getAmount())
                        .divide(accrual.getAmount(), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(accrual.getPoints())
                .multiply(share)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }

    private String tierCodeOf(LoyaltyAccount account) {
        return tierOf(account).map(MembershipTier::getCode).orElse(null);
    }

    private Optional<MembershipTier> byCode(List<MembershipTier> ladder, String code) {
        return ladder.stream().filter(tier -> tier.getCode().equals(code)).findFirst();
    }

    /** The lowest active rung: where a new member stands. */
    private Optional<MembershipTier> groundFloor() {
        return tiers.findByActiveTrueOrderBySortOrder().stream()
                .min(Comparator.comparing(MembershipTier::getMinimumRollingSpend));
    }
}
