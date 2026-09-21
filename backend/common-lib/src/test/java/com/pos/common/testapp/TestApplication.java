package com.pos.common.testapp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;

/**
 * A minimal service that uses common-lib exactly as a real service would, so the library's
 * behaviour is tested through a running Spring context rather than by unit-testing its parts in
 * isolation. What is asserted here is the contract every service inherits.
 */
@SpringBootApplication
public class TestApplication {

    /**
     * The resource server needs a decoder bean to start. Tests authenticate with
     * spring-security-test's post-processors, which bypass decoding, so this is never invoked.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException("not used in tests");
        };
    }

    @RestController
    @RequestMapping("/api/v1")
    static class ProbeController {

        private final BranchAccessGuard branchAccessGuard;

        ProbeController(BranchAccessGuard branchAccessGuard) {
            this.branchAccessGuard = branchAccessGuard;
        }

        @GetMapping("/public/ping")
        Map<String, String> ping() {
            return Map.of("status", "ok");
        }

        @GetMapping("/secure/me")
        Map<String, String> me() {
            return Map.of("userId", AuthenticatedUser.require().userId().toString());
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw Errors.NotFoundException.of("Product", "SKU-123");
        }

        @GetMapping("/probe/business-rule")
        void businessRule() {
            throw new Errors.BusinessRuleException(
                    "sale.outside_returns_window",
                    "This sale is outside the returns window",
                    Map.of("daysSinceSale", 45, "windowDays", 30));
        }

        @GetMapping("/probe/unexpected")
        void unexpected() {
            throw new IllegalStateException(
                    "connection to postgres at 10.0.0.5:5432 failed for user sales_user");
        }

        @GetMapping("/probe/branch/{branchId}")
        Map<String, String> branchScoped(@PathVariable UUID branchId) {
            branchAccessGuard.requireAccess(branchId);
            return Map.of("branchId", branchId.toString());
        }

        @GetMapping("/probe/admin")
        @PreAuthorize("hasAuthority('user:manage')")
        Map<String, String> adminOnly() {
            return Map.of("status", "ok");
        }

        @PostMapping("/probe/validate")
        Map<String, String> validate(@Valid @RequestBody RegistrationRequest request) {
            return Map.of("email", request.email());
        }

        record RegistrationRequest(
                @NotBlank(message = "must not be blank") @Email(message = "must be a valid email")
                        String email,
                @NotBlank(message = "must not be blank") String password) {}

        /** Shaped like a real request: an enum and a nested list, which is where parsing fails. */
        @PostMapping("/probe/parse")
        Map<String, String> parse(@RequestBody ParseProbeRequest request) {
            return Map.of("reason", request.reason().name());
        }

        enum ProbeReason {
            DAMAGE,
            EXPIRY,
            OTHER
        }

        record ParseProbeRequest(ProbeReason reason, Integer count, List<ParseProbeLine> lines) {}

        record ParseProbeLine(ProbeReason reason, Integer quantity) {}
    }

    /** Convenience for building a Jwt in tests. */
    public static Jwt.Builder jwtWith(UUID userId) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("uid", userId.toString());
    }
}
