package com.pos.customer.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.contact.PhoneNumbers;
import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.customer.domain.ConsentChannel;
import com.pos.customer.domain.Customer;
import com.pos.customer.domain.CustomerAddress;
import com.pos.customer.domain.CustomerConsent;
import com.pos.customer.domain.CustomerStatus;
import com.pos.customer.repository.CustomerAddressRepository;
import com.pos.customer.repository.CustomerConsentRepository;
import com.pos.customer.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

/**
 * Members: who they are, how a lane finds them, and what they agreed to.
 *
 * <p>A phone number is normalised before it is stored or searched, so the same person typed three
 * ways stays one member.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customers;
    private final CustomerAddressRepository addresses;
    private final CustomerConsentRepository consents;
    private final LoyaltyService loyalty;

    public record Details(
            String firstName,
            String lastName,
            String phone,
            String alternatePhone,
            String email,
            String cardNumber,
            LocalDate dateOfBirth,
            String notes) {}

    public Customer require(UUID id) {
        return customers
                .findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Customer", id));
    }

    /** Lane lookup: a phone number, a card, or the start of a name. */
    public Page<Customer> search(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return customers.findAllByOrderByDisplayNameAsc(pageable);
        }
        String trimmed = query.trim();
        Optional<Customer> exact =
                PhoneNumbers.toMsisdn(trimmed)
                        .flatMap(customers::findByPhone)
                        .or(() -> customers.findByCardNumber(trimmed))
                        .or(() -> customers.findByCustomerNumber(trimmed.toUpperCase()));
        if (exact.isPresent()) {
            return new org.springframework.data.domain.PageImpl<>(
                    List.of(exact.get()), pageable, 1);
        }
        return customers.searchByName("%" + trimmed + "%", pageable);
    }

    @Transactional
    public Customer create(Details details) {
        Customer customer = new Customer(nextCustomerNumber(), fallbackName(details));
        requireUnique(customer, details);
        apply(customer, details);
        Customer saved = customers.save(customer);
        loyalty.openAccountFor(saved);
        return saved;
    }

    @Transactional
    public Customer update(UUID id, Details details) {
        Customer customer = require(id);
        if (customer.isErased()) {
            throw new Errors.ConflictException(
                    "customer.erased", "An erased customer cannot be edited");
        }
        requireUnique(customer, details);
        apply(customer, details);
        return customers.save(customer);
    }

    @Transactional
    public CustomerAddress addAddress(UUID customerId, CustomerAddress address) {
        require(customerId);
        if (address.isDefaultAddress()) {
            // Flushed before the new row is inserted: one default is a partial unique index, and
            // the insert would otherwise reach the database while the old default is still set.
            addresses
                    .findByCustomerIdOrderByCreatedAt(customerId)
                    .forEach(
                            existing -> {
                                existing.setDefaultAddress(false);
                                addresses.saveAndFlush(existing);
                            });
        }
        return addresses.save(address);
    }

    public List<CustomerAddress> addressesOf(UUID customerId) {
        return addresses.findByCustomerIdOrderByCreatedAt(customerId);
    }

    @Transactional
    public CustomerConsent recordConsent(
            UUID customerId, ConsentChannel channel, boolean granted, String source, String note) {
        require(customerId);
        return consents.save(new CustomerConsent(customerId, channel, granted, source, note));
    }

    public List<CustomerConsent> consentHistory(UUID customerId) {
        return consents.findByCustomerIdOrderByOccurredAtDesc(customerId);
    }

    /**
     * Forgets the person on request.
     *
     * <p>Personal details go; the loyalty ledger stays. Those rows are a financial record - points
     * are money owed - and the shop's accounts have to keep balancing after someone leaves. The
     * remaining balance is written off first, so nothing is left owed to a member who cannot be
     * identified.
     */
    @Transactional
    public Customer erase(UUID id, String reason) {
        Customer customer = require(id);
        if (customer.isErased()) {
            return customer;
        }
        loyalty.writeOffOnErasure(customer.getId(), reason);
        customer.erase(AuthenticatedUser.currentUserId(), reason);
        addresses.deleteAll(addresses.findByCustomerIdOrderByCreatedAt(id));
        return customers.save(customer);
    }

    @Transactional
    public Customer deactivate(UUID id) {
        Customer customer = require(id);
        customer.setStatus(CustomerStatus.INACTIVE);
        return customers.save(customer);
    }

    /** Says which field clashes, rather than letting the index answer with a bare conflict. */
    private void requireUnique(Customer customer, Details details) {
        String phone = normalise(details.phone(), "phone");
        if (phone != null) {
            customers
                    .findByPhone(phone)
                    .filter(other -> !other.getId().equals(customer.getId()))
                    .ifPresent(
                            other -> {
                                throw new Errors.ConflictException(
                                        "customer.phone_taken",
                                        "Member %s already uses that phone number"
                                                .formatted(other.getCustomerNumber()));
                            });
        }
        if (details.cardNumber() != null && !details.cardNumber().isBlank()) {
            customers
                    .findByCardNumber(details.cardNumber().trim())
                    .filter(other -> !other.getId().equals(customer.getId()))
                    .ifPresent(
                            other -> {
                                throw new Errors.ConflictException(
                                        "customer.card_taken",
                                        "Member %s already uses that card"
                                                .formatted(other.getCustomerNumber()));
                            });
        }
    }

    private void apply(Customer customer, Details details) {
        customer.rename(details.firstName(), details.lastName(), customer.getCustomerNumber());
        customer.setPhone(normalise(details.phone(), "phone"));
        customer.setAlternatePhone(normalise(details.alternatePhone(), "alternatePhone"));
        customer.setEmail(
                details.email() == null || details.email().isBlank()
                        ? null
                        : details.email().trim());
        customer.setCardNumber(
                details.cardNumber() == null || details.cardNumber().isBlank()
                        ? null
                        : details.cardNumber().trim());
        customer.setDateOfBirth(details.dateOfBirth());
        customer.setNotes(details.notes());
    }

    private static String normalise(String typed, String field) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        return PhoneNumbers.toMsisdn(typed)
                .orElseThrow(
                        () ->
                                new Errors.BusinessRuleException(
                                        "customer.invalid_phone",
                                        "%s is not a Kenyan mobile number".formatted(field)));
    }

    private static String fallbackName(Details details) {
        String joined =
                ((details.firstName() == null ? "" : details.firstName())
                                + " "
                                + (details.lastName() == null ? "" : details.lastName()))
                        .trim();
        if (!joined.isBlank()) {
            return joined;
        }
        return details.phone() == null ? "Member" : PhoneNumbers.mask(details.phone());
    }

    private String nextCustomerNumber() {
        return "C-%06d".formatted(customers.highestCustomerNumber() + 1);
    }
}
