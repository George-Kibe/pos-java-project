package com.pos.customer.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A member.
 *
 * <p>The phone number is the identifier that matters at a lane - it is what a customer says out
 * loud - and is stored normalised so the same person cannot become two members.
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
public class Customer extends BaseEntity {

    @Column(name = "customer_number", nullable = false, length = 20, updatable = false)
    private String customerNumber;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(length = 20)
    private String phone;

    @Column(name = "alternate_phone", length = 20)
    private String alternatePhone;

    @Column(length = 255)
    private String email;

    @Column(name = "card_number", length = 40)
    private String cardNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @Column(length = 1000)
    private String notes;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt = Instant.now();

    @Column(name = "erased_at")
    private Instant erasedAt;

    @Column(name = "erased_by")
    private UUID erasedBy;

    @Column(name = "erasure_reason", length = 500)
    private String erasureReason;

    public Customer(String customerNumber, String displayName) {
        this.customerNumber = customerNumber;
        this.displayName = displayName;
    }

    public void rename(String firstName, String lastName, String fallback) {
        this.firstName = firstName;
        this.lastName = lastName;
        String joined =
                ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName))
                        .trim();
        this.displayName = joined.isBlank() ? fallback : joined;
    }

    /**
     * Forgets the person, keeps the member.
     *
     * <p>Names, contact details and the card go; the row stays, so the ledger still refers to
     * something and the shop's accounts still balance. Identifiers are cleared rather than
     * scrambled so they can be reused by whoever holds that number next.
     */
    public void erase(UUID actor, String reason) {
        this.firstName = null;
        this.lastName = null;
        this.displayName = "Erased customer " + customerNumber;
        this.phone = null;
        this.alternatePhone = null;
        this.email = null;
        this.cardNumber = null;
        this.dateOfBirth = null;
        this.notes = null;
        this.status = CustomerStatus.ERASED;
        this.erasedAt = Instant.now();
        this.erasedBy = actor;
        this.erasureReason = reason;
    }

    public boolean isErased() {
        return status == CustomerStatus.ERASED;
    }
}
