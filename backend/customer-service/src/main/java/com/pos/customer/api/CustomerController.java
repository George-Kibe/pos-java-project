package com.pos.customer.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.web.PageResponse;
import com.pos.customer.api.dto.CustomerDtos;
import com.pos.customer.domain.Customer;
import com.pos.customer.domain.CustomerAddress;
import com.pos.customer.service.CustomerService;
import com.pos.customer.service.LoyaltyQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@Tag(name = "Customers")
public class CustomerController {

    private final CustomerService customers;
    private final LoyaltyQueryService loyaltyQueries;

    @GetMapping
    @PreAuthorize("hasAuthority('customer:view')")
    @Operation(summary = "Find a member by phone, card, member number or name")
    public PageResponse<CustomerDtos.CustomerResponse> search(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(customers.search(q, pageable), CustomerDtos.CustomerResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:view')")
    @Operation(summary = "One member")
    public CustomerDtos.CustomerResponse get(@PathVariable UUID id) {
        return CustomerDtos.CustomerResponse.from(customers.require(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer:manage')")
    @Operation(summary = "Enrol a member; a loyalty account is opened with them")
    public ResponseEntity<CustomerDtos.CustomerResponse> create(
            @Valid @RequestBody CustomerDtos.CustomerRequest request) {
        Customer created = customers.create(request.toDetails());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.getId()))
                .body(CustomerDtos.CustomerResponse.from(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:manage')")
    @Operation(summary = "Update a member's details")
    public CustomerDtos.CustomerResponse update(
            @PathVariable UUID id, @Valid @RequestBody CustomerDtos.CustomerRequest request) {
        return CustomerDtos.CustomerResponse.from(customers.update(id, request.toDetails()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('customer:manage')")
    @Operation(summary = "Stop using a member's record without erasing their history")
    public CustomerDtos.CustomerResponse deactivate(@PathVariable UUID id) {
        return CustomerDtos.CustomerResponse.from(customers.deactivate(id));
    }

    // --- addresses and consent ---------------------------------------------------

    @GetMapping("/{id}/addresses")
    @PreAuthorize("hasAuthority('customer:view')")
    public List<CustomerDtos.AddressResponse> addresses(@PathVariable UUID id) {
        return customers.addressesOf(id).stream().map(CustomerDtos.AddressResponse::from).toList();
    }

    @PostMapping("/{id}/addresses")
    @PreAuthorize("hasAuthority('customer:manage')")
    public ResponseEntity<CustomerDtos.AddressResponse> addAddress(
            @PathVariable UUID id, @Valid @RequestBody CustomerDtos.AddressRequest request) {
        CustomerAddress address = new CustomerAddress(id, request.line1());
        address.setLabel(request.label());
        address.setLine2(request.line2());
        address.setTown(request.town());
        address.setCounty(request.county());
        address.setDefaultAddress(request.isDefault());
        CustomerAddress saved = customers.addAddress(id, address);
        return ResponseEntity.created(
                        URI.create("/api/v1/customers/" + id + "/addresses/" + saved.getId()))
                .body(CustomerDtos.AddressResponse.from(saved));
    }

    @GetMapping("/{id}/consents")
    @PreAuthorize("hasAuthority('customer:view')")
    @Operation(summary = "What was agreed and when, newest first")
    public List<CustomerDtos.ConsentResponse> consents(@PathVariable UUID id) {
        return customers.consentHistory(id).stream()
                .map(CustomerDtos.ConsentResponse::from)
                .toList();
    }

    @PostMapping("/{id}/consents")
    @PreAuthorize("hasAuthority('customer:manage')")
    @Operation(summary = "Record consent given or withdrawn; the history is append-only")
    public CustomerDtos.ConsentResponse recordConsent(
            @PathVariable UUID id, @Valid @RequestBody CustomerDtos.ConsentRequest request) {
        return CustomerDtos.ConsentResponse.from(
                customers.recordConsent(
                        id,
                        request.channel(),
                        Boolean.TRUE.equals(request.granted()),
                        request.source(),
                        request.note()));
    }

    // --- the member's own data ----------------------------------------------------

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('customer:manage')")
    @Operation(summary = "Everything held about a member, for them to take away")
    public CustomerDtos.ExportResponse export(@PathVariable UUID id) {
        Customer customer = customers.require(id);
        return new CustomerDtos.ExportResponse(
                Instant.now(),
                CustomerDtos.CustomerResponse.from(customer),
                customers.addressesOf(id).stream().map(CustomerDtos.AddressResponse::from).toList(),
                customers.consentHistory(id).stream()
                        .map(CustomerDtos.ConsentResponse::from)
                        .toList(),
                loyaltyQueries.snapshot(id).map(CustomerDtos.AccountResponse::from).orElse(null),
                loyaltyQueries.allTransactions(id).stream()
                        .map(CustomerDtos.TransactionResponse::from)
                        .toList());
    }

    @PostMapping("/{id}/erasure")
    @PreAuthorize("hasAuthority('customer:manage')")
    @Operation(
            summary =
                    "Forget the person: details are removed, the points ledger is kept and any"
                            + " balance written off")
    public CustomerDtos.CustomerResponse erase(
            @PathVariable UUID id, @Valid @RequestBody CustomerDtos.ErasureRequest request) {
        return CustomerDtos.CustomerResponse.from(customers.erase(id, request.reason()));
    }
}
