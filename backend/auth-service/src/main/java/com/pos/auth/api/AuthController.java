package com.pos.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.api.dto.AuthDtos;
import com.pos.auth.domain.User;
import com.pos.auth.service.AuthenticationService;
import com.pos.auth.service.PasswordResetService;
import com.pos.auth.service.RegistrationService;
import com.pos.auth.service.TokenPair;
import com.pos.auth.service.UserAdminService;
import com.pos.common.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Unauthenticated authentication endpoints, plus {@code /me}.
 *
 * <p>Register, resend and forgot-password all answer with the same vague message whatever the
 * outcome. That is not laziness: a response that differs turns each of them into an oracle for
 * whether a given address has an account here.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

    private static final String CHECK_YOUR_EMAIL =
            "If that email address can be registered, a verification code has been sent to it.";

    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;
    private final UserAdminService users;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Register an account and send a verification code by email")
    public AuthDtos.MessageResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        registrationService.register(
                request.email(), request.password(), request.fullName(), request.phone());
        return AuthDtos.MessageResponse.of(CHECK_YOUR_EMAIL);
    }

    @PostMapping("/verify-otp")
    @Operation(summary = "Complete registration with the emailed code")
    public AuthDtos.MessageResponse verifyOtp(
            @Valid @RequestBody AuthDtos.VerifyOtpRequest request) {
        registrationService.verifyOtp(request.email(), request.code());
        return AuthDtos.MessageResponse.of("Your email is verified. You can now sign in.");
    }

    @PostMapping("/resend-otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a fresh verification code")
    public AuthDtos.MessageResponse resendOtp(@Valid @RequestBody AuthDtos.EmailRequest request) {
        registrationService.resendOtp(request.email());
        return AuthDtos.MessageResponse.of(CHECK_YOUR_EMAIL);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access and refresh token")
    public AuthDtos.TokenResponse login(
            @Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest httpRequest) {
        TokenPair pair =
                authenticationService.login(
                        request.email(),
                        request.password(),
                        ClientRequestInfo.ipOf(httpRequest),
                        ClientRequestInfo.userAgentOf(httpRequest));
        return AuthDtos.TokenResponse.from(pair);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token for a new pair")
    public AuthDtos.TokenResponse refresh(
            @Valid @RequestBody AuthDtos.RefreshRequest request, HttpServletRequest httpRequest) {
        TokenPair pair =
                authenticationService.refresh(
                        request.refreshToken(),
                        ClientRequestInfo.ipOf(httpRequest),
                        ClientRequestInfo.userAgentOf(httpRequest));
        return AuthDtos.TokenResponse.from(pair);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the refresh token family, signing out every device on that login")
    public void logout(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        authenticationService.logout(request.refreshToken());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a password reset link")
    public AuthDtos.MessageResponse forgotPassword(
            @Valid @RequestBody AuthDtos.EmailRequest request) {
        passwordResetService.requestReset(request.email());
        return AuthDtos.MessageResponse.of(
                "If that email address belongs to an account, a reset link has been sent to it.");
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using a reset token")
    public AuthDtos.MessageResponse resetPassword(
            @Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return AuthDtos.MessageResponse.of(
                "Your password has been changed and all other sessions have been signed out.");
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change your own password; ends your other sessions")
    public AuthDtos.MessageResponse changePassword(
            @Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
        passwordResetService.changeOwnPassword(
                AuthenticatedUser.require().userId(),
                request.currentPassword(),
                request.newPassword());
        return AuthDtos.MessageResponse.of("Your password has been changed. Please sign in again.");
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated caller")
    public AuthDtos.MeResponse me() {
        AuthenticatedUser caller = AuthenticatedUser.require();
        User user = users.get(caller.userId());

        return new AuthDtos.MeResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getStatus().name(),
                user.roleCodes(),
                user.permissionCodes(),
                user.branchIds(),
                user.isMustChangePassword());
    }
}
