package com.pos.auth.api.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pos.auth.service.TokenPair;

/** Request and response bodies for the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {}

    /**
     * Twelve characters minimum with no composition rules.
     *
     * <p>Length is what actually resists guessing; forcing a digit and a symbol mostly produces
     * Password1! and pushes people to write passwords down. Argon2 does the rest.
     */
    private static final int MIN_PASSWORD_LENGTH = 12;

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = 128) String password,
            @NotBlank @Size(max = 150) String fullName,
            @Size(max = 30) String phone) {}

    public record VerifyOtpRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "\\d{4,10}", message = "must be a numeric code")
                    String code) {}

    public record EmailRequest(@NotBlank @Email String email) {}

    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank @Size(max = 128) String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = 128) String newPassword) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = 128) String newPassword) {}

    /** OAuth2-shaped so a standard client library can consume it without adaptation. */
    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            boolean mustChangePassword) {

        public static TokenResponse from(TokenPair pair) {
            return new TokenResponse(
                    pair.accessToken(),
                    pair.tokenType(),
                    pair.expiresInSeconds(),
                    pair.refreshToken(),
                    pair.mustChangePassword());
        }
    }

    public record MeResponse(
            UUID id,
            String email,
            String fullName,
            String phone,
            String status,
            Set<String> roles,
            Set<String> permissions,
            Set<UUID> branchIds,
            boolean mustChangePassword,
            /**
             * The caller's own branches, named - what a branch switcher shows. A cashier holds no
             * {@code branch:view} and could not look the names up otherwise.
             */
            java.util.List<BranchSummary> branches) {}

    public record BranchSummary(UUID id, String code, String name) {}

    /**
     * Deliberately vague. Used for register, resend and forgot-password, all of which must look the
     * same whether or not the address belongs to an account.
     */
    public record MessageResponse(String message) {

        public static MessageResponse of(String message) {
            return new MessageResponse(message);
        }
    }
}
