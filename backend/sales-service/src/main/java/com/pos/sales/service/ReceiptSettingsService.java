package com.pos.sales.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.sales.domain.ReceiptSettings;
import com.pos.sales.repository.ReceiptSettingsRepository;

import lombok.RequiredArgsConstructor;

/**
 * A branch's receipt text. A branch that has set none prints the brand and the sale alone, so the
 * answer for it is blank settings rather than a 404 a lane would have to handle.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptSettingsService {

    /** A thermal receipt is 32-48 characters wide; more than a handful of lines is a leaflet. */
    private static final int MAX_LINES = 6;

    private final ReceiptSettingsRepository settings;

    public record Text(String header, String footer, String address, String phone, String taxPin) {}

    public ReceiptSettings of(UUID branchId) {
        return settings.findByBranchId(branchId).orElseGet(() -> new ReceiptSettings(branchId));
    }

    @Transactional
    public ReceiptSettings save(UUID branchId, Text text) {
        requireFew(text.header(), "header");
        requireFew(text.footer(), "footer");
        ReceiptSettings current =
                settings.findByBranchId(branchId).orElseGet(() -> new ReceiptSettings(branchId));
        current.setHeader(trimmed(text.header()));
        current.setFooter(trimmed(text.footer()));
        current.setAddress(trimmed(text.address()));
        current.setPhone(trimmed(text.phone()));
        current.setTaxPin(trimmed(text.taxPin()));
        return settings.save(current);
    }

    private static void requireFew(String lines, String field) {
        if (lines != null && lines.lines().count() > MAX_LINES) {
            throw new Errors.BadRequestException(
                    "receipt_settings.too_many_lines",
                    "The %s takes at most %d lines.".formatted(field, MAX_LINES));
        }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
