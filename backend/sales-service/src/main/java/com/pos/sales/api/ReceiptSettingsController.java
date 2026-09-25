package com.pos.sales.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.sales.domain.ReceiptSettings;
import com.pos.sales.service.ReceiptSettingsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * A branch's receipt text: read by the lanes that print it, set by whoever runs the branches
 * ({@code branch:manage}).
 */
@RestController
@RequestMapping("/api/v1/receipt-settings")
@RequiredArgsConstructor
@Tag(name = "Receipt settings")
public class ReceiptSettingsController {

    private final ReceiptSettingsService settings;
    private final BranchAccessGuard branchAccess;

    public record ReceiptSettingsRequest(
            @Size(max = 500) String header,
            @Size(max = 500) String footer,
            @Size(max = 300) String address,
            @Size(max = 30) String phone,
            @Size(max = 30) String taxPin) {}

    public record ReceiptSettingsResponse(
            UUID branchId,
            String header,
            String footer,
            String address,
            String phone,
            String taxPin) {

        static ReceiptSettingsResponse from(ReceiptSettings settings) {
            return new ReceiptSettingsResponse(
                    settings.getBranchId(),
                    settings.getHeader(),
                    settings.getFooter(),
                    settings.getAddress(),
                    settings.getPhone(),
                    settings.getTaxPin());
        }
    }

    @GetMapping("/{branchId}")
    @PreAuthorize("hasAnyAuthority('shift:open', 'branch:manage')")
    @Operation(summary = "What a branch prints around the sale on its receipts")
    public ReceiptSettingsResponse get(@PathVariable UUID branchId) {
        branchAccess.requireAccess(branchId);
        return ReceiptSettingsResponse.from(settings.of(branchId));
    }

    @PutMapping("/{branchId}")
    @PreAuthorize("hasAuthority('branch:manage')")
    @Operation(summary = "Set a branch's receipt text; the layout is fixed")
    public ReceiptSettingsResponse save(
            @PathVariable UUID branchId, @Valid @RequestBody ReceiptSettingsRequest request) {
        branchAccess.requireAccess(branchId);
        return ReceiptSettingsResponse.from(
                settings.save(
                        branchId,
                        new ReceiptSettingsService.Text(
                                request.header(),
                                request.footer(),
                                request.address(),
                                request.phone(),
                                request.taxPin())));
    }
}
