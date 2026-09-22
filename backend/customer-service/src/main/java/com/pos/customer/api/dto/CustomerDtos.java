package com.pos.customer.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import com.pos.customer.domain.ConsentChannel;
import com.pos.customer.domain.Customer;
import com.pos.customer.domain.CustomerAddress;
import com.pos.customer.domain.CustomerConsent;
import com.pos.customer.domain.LoyaltyAccount;
import com.pos.customer.domain.LoyaltyTransaction;
import com.pos.customer.domain.MembershipTier;
import com.pos.customer.service.CustomerService;
import com.pos.customer.service.LoyaltyQueryService;

public final class CustomerDtos {

    private CustomerDtos() {}

    // --- requests ---------------------------------------------------------------

    public record CustomerRequest(
            @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Size(max = 20) String phone,
            @Size(max = 20) String alternatePhone,
            @Email @Size(max = 255) String email,
            @Size(max = 40) String cardNumber,
            @Past LocalDate dateOfBirth,
            @Size(max = 1000) String notes) {

        public CustomerService.Details toDetails() {
            return new CustomerService.Details(
                    firstName,
                    lastName,
                    phone,
                    alternatePhone,
                    email,
                    cardNumber,
                    dateOfBirth,
                    notes);
        }
    }

    public record AddressRequest(
            @Size(max = 40) String label,
            @NotBlank @Size(max = 160) String line1,
            @Size(max = 160) String line2,
            @Size(max = 80) String town,
            @Size(max = 80) String county,
            Boolean makeDefault) {

        public boolean isDefault() {
            return Boolean.TRUE.equals(makeDefault);
        }
    }

    public record ConsentRequest(
            @NotNull ConsentChannel channel,
            @NotNull Boolean granted,
            @Size(max = 30) String source,
            @Size(max = 500) String note) {}

    public record ErasureRequest(@NotBlank @Size(max = 500) String reason) {}

    public record AdjustmentRequest(
            @NotNull UUID customerId,
            @NotNull Long points,
            @NotBlank @Size(max = 500) String reason) {}

    // --- responses --------------------------------------------------------------

    public record CustomerResponse(
            UUID id,
            String customerNumber,
            String firstName,
            String lastName,
            String displayName,
            String phone,
            String email,
            String cardNumber,
            LocalDate dateOfBirth,
            String status,
            Instant enrolledAt) {

        public static CustomerResponse from(Customer customer) {
            return new CustomerResponse(
                    customer.getId(),
                    customer.getCustomerNumber(),
                    customer.getFirstName(),
                    customer.getLastName(),
                    customer.getDisplayName(),
                    customer.getPhone(),
                    customer.getEmail(),
                    customer.getCardNumber(),
                    customer.getDateOfBirth(),
                    customer.getStatus().name(),
                    customer.getEnrolledAt());
        }
    }

    public record AddressResponse(
            UUID id,
            String label,
            String line1,
            String line2,
            String town,
            String county,
            boolean isDefault) {

        public static AddressResponse from(CustomerAddress address) {
            return new AddressResponse(
                    address.getId(),
                    address.getLabel(),
                    address.getLine1(),
                    address.getLine2(),
                    address.getTown(),
                    address.getCounty(),
                    address.isDefaultAddress());
        }
    }

    public record ConsentResponse(
            String channel, boolean granted, String source, String note, Instant occurredAt) {

        public static ConsentResponse from(CustomerConsent consent) {
            return new ConsentResponse(
                    consent.getChannel().name(),
                    consent.isGranted(),
                    consent.getSource(),
                    consent.getNote(),
                    consent.getOccurredAt());
        }
    }

    public record TierResponse(
            String code, String name, BigDecimal minimumRollingSpend, BigDecimal pointsMultiplier) {

        public static TierResponse from(MembershipTier tier) {
            return new TierResponse(
                    tier.getCode(),
                    tier.getName(),
                    tier.getMinimumRollingSpend(),
                    tier.getPointsMultiplier());
        }
    }

    public record AccountResponse(
            UUID id,
            UUID customerId,
            long pointsBalance,
            long lifetimePoints,
            BigDecimal pointsValue,
            BigDecimal rollingSpend,
            String currency,
            TierResponse tier,
            long expiringWithin30Days,
            Instant lastActivityAt) {

        public static AccountResponse from(LoyaltyQueryService.Snapshot snapshot) {
            LoyaltyAccount account = snapshot.account();
            MembershipTier tier = snapshot.tier();
            BigDecimal pointsValue = snapshot.pointsValue();
            long expiringSoon = snapshot.expiringSoon();
            return new AccountResponse(
                    account.getId(),
                    account.getCustomerId(),
                    account.getPointsBalance(),
                    account.getLifetimePoints(),
                    pointsValue,
                    account.getRollingSpend(),
                    account.getCurrency(),
                    tier == null ? null : TierResponse.from(tier),
                    expiringSoon,
                    account.getLastActivityAt());
        }
    }

    public record TransactionResponse(
            UUID id,
            String type,
            long points,
            long balanceAfter,
            long pointsRemaining,
            BigDecimal amount,
            String currency,
            UUID saleId,
            UUID returnId,
            String reason,
            Instant expiresAt,
            Instant occurredAt) {

        public static TransactionResponse from(LoyaltyTransaction transaction) {
            return new TransactionResponse(
                    transaction.getId(),
                    transaction.getType().name(),
                    transaction.getPoints(),
                    transaction.getBalanceAfter(),
                    transaction.getPointsRemaining(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    transaction.getSaleId(),
                    transaction.getReturnId(),
                    transaction.getReason(),
                    transaction.getExpiresAt(),
                    transaction.getOccurredAt());
        }
    }

    /** Everything held about one person, for them to take away. */
    public record ExportResponse(
            Instant generatedAt,
            CustomerResponse customer,
            List<AddressResponse> addresses,
            List<ConsentResponse> consents,
            AccountResponse loyalty,
            List<TransactionResponse> transactions) {}
}
