package com.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

import com.pos.events.EventJson;
import com.pos.events.Topics;

import tools.jackson.databind.JsonNode;

/** The identity flows end to end, over HTTP, against a real PostgreSQL. */
class AuthFlowIT extends AuthTestBase {

    // --- the main walk --------------------------------------------------------

    @Test
    @DisplayName("register, verify by OTP, sign in, call /me, then rotate the refresh token")
    void fullRegistrationWalk() {
        String email = "walk-" + System.nanoTime() + "@pos.test";
        String password = "CorrectHorseBattery1";

        ResponseEntity<String> registered =
                post(
                        "/api/v1/auth/register",
                        Map.of("email", email, "password", password, "fullName", "Ada Lovelace"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // An OTP event is queued in the outbox, in the same transaction as the new user.
        String code = latestOtpFor(email);
        assertThat(code).hasSize(6).containsOnlyDigits();

        // Cannot sign in before proving the address.
        ResponseEntity<String> tooEarly =
                post("/api/v1/auth/login", Map.of("email", email, "password", password));
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(tooEarly).get("code").asString()).isEqualTo("auth.not_active");

        ResponseEntity<String> verified =
                post("/api/v1/auth/verify-otp", Map.of("email", email, "code", code));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outboxCount(Topics.AUTH_USER_REGISTERED)).isPositive();

        ResponseEntity<String> loggedIn =
                post("/api/v1/auth/login", Map.of("email", email, "password", password));
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tokens = json(loggedIn);
        assertThat(tokens.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(tokens.get("expiresIn").asLong()).isBetween(1L, 900L);

        String accessToken = tokens.get("accessToken").asString();
        String refreshToken = tokens.get("refreshToken").asString();

        ResponseEntity<String> me = get("/api/v1/auth/me", accessToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(me).get("email").asString()).isEqualTo(email);
        assertThat(json(me).get("status").asString()).isEqualTo("ACTIVE");
        // No default role, so a self-registered account can do nothing yet.
        assertThat(json(me).get("permissions")).isEmpty();

        ResponseEntity<String> refreshed =
                post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedRefresh = json(refreshed).get("refreshToken").asString();
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);

        // The rotated token works.
        assertThat(
                        get("/api/v1/auth/me", json(refreshed).get("accessToken").asString())
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("replaying a spent refresh token revokes the entire family")
    void replayedRefreshTokenRevokesTheFamily() {
        Registered user = registerAndVerify("replay");

        ResponseEntity<String> loggedIn =
                post(
                        "/api/v1/auth/login",
                        Map.of("email", user.email(), "password", user.password()));
        String firstRefresh = json(loggedIn).get("refreshToken").asString();

        String secondRefresh =
                json(post("/api/v1/auth/refresh", Map.of("refreshToken", firstRefresh)))
                        .get("refreshToken")
                        .asString();

        // Replay the spent token: either it was stolen, or the client is retrying.
        // Indistinguishable,
        // so the safe action is to end the whole family.
        ResponseEntity<String> replay =
                post("/api/v1/auth/refresh", Map.of("refreshToken", firstRefresh));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(replay).get("code").asString()).isEqualTo("auth.invalid_refresh_token");

        // The legitimate holder is signed out too. That is the point: the thief cannot keep going,
        // and the real user notices and signs in again.
        ResponseEntity<String> afterRevocation =
                post("/api/v1/auth/refresh", Map.of("refreshToken", secondRefresh));
        assertThat(afterRevocation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Long revoked =
                jdbc.sql(
                                """
                                SELECT count(*) FROM auth.refresh_tokens
                                WHERE revoked_reason = 'reuse_detected'
                                """)
                        .query(Long.class)
                        .single();
        assertThat(revoked).isPositive();
    }

    // --- key publication ------------------------------------------------------

    @Test
    @DisplayName("an access token verifies against the published JWKS")
    void jwksVerifiesAnIssuedToken() throws Exception {
        Registered user = registerAndVerify("jwks");
        String accessToken = loginForAccessToken(user.email(), user.password());

        ResponseEntity<String> jwks =
                rest.getForEntity(baseUrl + "/.well-known/jwks.json", String.class);
        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Public key material only: no private exponent may ever be published.
        assertThat(jwks.getBody()).doesNotContain("\"d\":").doesNotContain("\"p\":");

        JWKSet published = JWKSet.parse(jwks.getBody());
        SignedJWT parsed = SignedJWT.parse(accessToken);

        // The kid is what lets a verifier pick the right key while several are published.
        String kid = parsed.getHeader().getKeyID();
        assertThat(kid).isNotBlank();

        RSAKey key = (RSAKey) published.getKeyByKeyId(kid);
        assertThat(key).isNotNull();
        assertThat(parsed.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();

        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:8081");
        assertThat(parsed.getJWTClaimsSet().getClaim("uid")).isNotNull();
        assertThat(parsed.getJWTClaimsSet().getIntegerClaim("tv")).isNotNull();
    }

    // --- the endpoints must not leak who has an account -----------------------

    @Test
    @DisplayName("registering an address that already exists is indistinguishable from a new one")
    void registrationDoesNotRevealExistingAccounts() {
        Registered existing = registerAndVerify("enumerate");

        ResponseEntity<String> againstExisting =
                post(
                        "/api/v1/auth/register",
                        Map.of(
                                "email", existing.email(),
                                "password", "SomeOtherPassword1",
                                "fullName", "Someone Else"));
        ResponseEntity<String> againstNew =
                post(
                        "/api/v1/auth/register",
                        Map.of(
                                "email", "fresh-" + System.nanoTime() + "@pos.test",
                                "password", "SomeOtherPassword1",
                                "fullName", "Someone New"));

        assertThat(againstExisting.getStatusCode()).isEqualTo(againstNew.getStatusCode());
        assertThat(againstExisting.getBody()).isEqualTo(againstNew.getBody());
    }

    @Test
    @DisplayName("an unknown address and a wrong password give the same answer")
    void unknownAddressAndWrongPasswordAreIndistinguishable() {
        Registered user = registerAndVerify("timing");

        ResponseEntity<String> wrongPassword =
                post(
                        "/api/v1/auth/login",
                        Map.of("email", user.email(), "password", "DefinitelyNotIt1"));
        ResponseEntity<String> unknownAddress =
                post(
                        "/api/v1/auth/login",
                        Map.of(
                                "email",
                                "nobody-" + System.nanoTime() + "@pos.test",
                                "password",
                                "DefinitelyNotIt1"));

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownAddress.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(wrongPassword).get("code").asString())
                .isEqualTo(json(unknownAddress).get("code").asString())
                .isEqualTo("auth.invalid_credentials");
    }

    @Test
    @DisplayName("a resend for an unknown address answers exactly as one for a real address")
    void resendDoesNotRevealExistingAccounts() {
        Registered user = registerAndVerify("resend");

        ResponseEntity<String> known =
                post("/api/v1/auth/resend-otp", Map.of("email", user.email()));
        ResponseEntity<String> unknown =
                post(
                        "/api/v1/auth/resend-otp",
                        Map.of("email", "nobody-" + System.nanoTime() + "@pos.test"));

        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(known.getBody()).isEqualTo(unknown.getBody());
    }

    // --- OTP and lockout ------------------------------------------------------

    @Test
    @DisplayName("a wrong OTP is rejected and repeated guesses exhaust the attempt limit")
    void wrongOtpIsRejectedAndCapped() {
        String email = "otp-" + System.nanoTime() + "@pos.test";
        post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        email,
                        "password",
                        "CorrectHorseBattery1",
                        "fullName",
                        "Otp Tester"));
        String realCode = latestOtpFor(email);

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> attempt =
                    post("/api/v1/auth/verify-otp", Map.of("email", email, "code", "000000"));
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(attempt).get("code").asString()).isEqualTo("otp.invalid");
        }

        // Five wrong guesses burned the code, so even the correct one no longer works. Without
        // this cap a six-digit code would be brute-forceable inside its lifetime.
        ResponseEntity<String> withRealCode =
                post("/api/v1/auth/verify-otp", Map.of("email", email, "code", realCode));
        assertThat(withRealCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("repeated failed sign-ins lock the account")
    void accountLocksAfterRepeatedFailures() {
        Registered user = registerAndVerify("lockout");

        for (int i = 0; i < 3; i++) {
            post(
                    "/api/v1/auth/login",
                    Map.of("email", user.email(), "password", "WrongPassword123"));
        }

        // Locked, and told so: a cashier needs to know to fetch a supervisor rather than keep
        // trying.
        ResponseEntity<String> locked =
                post(
                        "/api/v1/auth/login",
                        Map.of("email", user.email(), "password", user.password()));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(locked).get("code").asString()).isEqualTo("auth.account_locked");

        // Recorded even though every one of those requests ended in an error response - the
        // counter is written in its own transaction precisely so the rollback cannot erase it.
        // Four rows, not three: the attempt that was refused because the account was already
        // locked is itself an attempt worth recording.
        Long failures =
                jdbc.sql(
                                """
                                SELECT count(*) FROM auth.login_attempts
                                WHERE email_normalized = :email AND successful = false
                                """)
                        .param("email", user.email().toLowerCase())
                        .query(Long.class)
                        .single();
        assertThat(failures).isEqualTo(4);

        String lastReason =
                jdbc.sql(
                                """
                                SELECT failure_reason FROM auth.login_attempts
                                WHERE email_normalized = :email
                                ORDER BY attempted_at DESC LIMIT 1
                                """)
                        .param("email", user.email().toLowerCase())
                        .query(String.class)
                        .single();
        assertThat(lastReason).isEqualTo("account_locked");
    }

    // --- sessions -------------------------------------------------------------

    @Test
    void logoutEndsTheSession() {
        Registered user = registerAndVerify("logout");
        ResponseEntity<String> loggedIn =
                post(
                        "/api/v1/auth/login",
                        Map.of("email", user.email(), "password", user.password()));
        String refreshToken = json(loggedIn).get("refreshToken").asString();

        assertThat(
                        post("/api/v1/auth/logout", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(
                        post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Idempotent: logging out twice is not an error and reveals nothing.
        assertThat(
                        post("/api/v1/auth/logout", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(
                        post("/api/v1/auth/logout", Map.of("refreshToken", "never-existed"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("resetting a password ends every other session")
    void passwordResetEndsAllSessions() {
        Registered user = registerAndVerify("reset");
        String refreshToken =
                json(post(
                                "/api/v1/auth/login",
                                Map.of("email", user.email(), "password", user.password())))
                        .get("refreshToken")
                        .asString();

        assertThat(
                        post("/api/v1/auth/forgot-password", Map.of("email", user.email()))
                                .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        String resetToken = latestPasswordResetTokenFor(user.email());
        String newPassword = "ABrandNewPassword9";

        assertThat(
                        post(
                                        "/api/v1/auth/reset-password",
                                        Map.of("token", resetToken, "newPassword", newPassword))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // A reset is triggered when an account may be compromised, so other sessions must die.
        assertThat(
                        post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // The old password is gone and the new one works.
        assertThat(
                        post(
                                        "/api/v1/auth/login",
                                        Map.of("email", user.email(), "password", user.password()))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        post(
                                        "/api/v1/auth/login",
                                        Map.of("email", user.email(), "password", newPassword))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The token is single use.
        assertThat(
                        post(
                                        "/api/v1/auth/reset-password",
                                        Map.of(
                                                "token",
                                                resetToken,
                                                "newPassword",
                                                "YetAnotherPass7"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- authorization --------------------------------------------------------

    @Test
    @DisplayName("administrative endpoints are denied to an account without the permission")
    void adminEndpointsRequirePermission() {
        Registered user = registerAndVerify("nopriv");
        String token = loginForAccessToken(user.email(), user.password());

        ResponseEntity<String> denied = get("/api/v1/users", token);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(denied).get("code").asString()).isEqualTo("auth.forbidden");

        assertThat(get("/api/v1/users", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/roles", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a custom role built at runtime decides what a token can do")
    void customRoleChangesWhatATokenCanDo() {
        String admin = adminAccessToken();

        // 1. Build a role with one permission. No deployment involved.
        String roleCode = "LIMITED_VIEWER_" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> createdRole =
                post(
                        "/api/v1/roles",
                        Map.of(
                                "code",
                                roleCode,
                                "name",
                                "Limited viewer",
                                "description",
                                "Can look at users and nothing else",
                                "permissions",
                                List.of("user:view")),
                        admin);
        assertThat(createdRole.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String roleId = json(createdRole).get("id").asString();
        // Read the code back rather than reusing the input: the service normalises role codes to
        // upper case, and the stored value is the one that resolves.
        String storedRoleCode = json(createdRole).get("code").asString();
        assertThat(storedRoleCode).isEqualTo(roleCode.toUpperCase(java.util.Locale.ROOT));

        // 2. Give it to a new user.
        String email = "limited-" + System.nanoTime() + "@pos.test";
        String password = "TemporaryPassword1";
        ResponseEntity<String> createdUser =
                post(
                        "/api/v1/users",
                        Map.of(
                                "email",
                                email,
                                "temporaryPassword",
                                password,
                                "fullName",
                                "Limited Person",
                                "roles",
                                List.of(storedRoleCode)),
                        admin);
        assertThat(createdUser.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(createdUser).get("mustChangePassword").asBoolean()).isTrue();

        String limitedToken = loginForAccessToken(email, password);

        // 3. The permission is in force immediately, and nothing else is.
        assertThat(get("/api/v1/users", limitedToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> cannotCreate =
                post(
                        "/api/v1/users",
                        Map.of(
                                "email", "blocked-" + System.nanoTime() + "@pos.test",
                                "temporaryPassword", "TemporaryPassword1",
                                "fullName", "Blocked"),
                        limitedToken);
        assertThat(cannotCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 4. Widen the role.
        ResponseEntity<String> updatedRole =
                patch(
                        "/api/v1/roles/" + roleId,
                        Map.of("permissions", List.of("user:view", "user:manage")),
                        admin);
        assertThat(updatedRole.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Holders' token versions were bumped and their sessions ended, because permissions are
        // carried inside the access token and cannot be edited in place.
        Integer tokenVersion =
                jdbc.sql("SELECT token_version FROM auth.users WHERE email_normalized = :email")
                        .param("email", email)
                        .query(Integer.class)
                        .single();
        assertThat(tokenVersion).isGreaterThan(1);

        // The already-issued token still carries the old permission set until it expires. That is
        // the cost of stateless authorization, and the reason the token carries tv for the gateway
        // to enforce (Phase 4).
        assertThat(
                        post(
                                        "/api/v1/users",
                                        Map.of(
                                                "email",
                                                        "still-blocked-"
                                                                + System.nanoTime()
                                                                + "@pos.test",
                                                "temporaryPassword", "TemporaryPassword1",
                                                "fullName", "Still blocked"),
                                        limitedToken)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 5. A fresh sign-in picks up the widened role.
        String widenedToken = loginForAccessToken(email, password);
        assertThat(
                        post(
                                        "/api/v1/users",
                                        Map.of(
                                                "email",
                                                        "allowed-"
                                                                + System.nanoTime()
                                                                + "@pos.test",
                                                "temporaryPassword", "TemporaryPassword1",
                                                "fullName", "Allowed"),
                                        widenedToken)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a built-in role cannot be deleted")
    void systemRolesAreProtected() {
        String admin = adminAccessToken();
        JsonNode roles = json(get("/api/v1/roles", admin));

        String superAdminId = null;
        for (JsonNode role : roles) {
            if ("SUPER_ADMIN".equals(role.get("code").asString())) {
                superAdminId = role.get("id").asString();
            }
        }
        assertThat(superAdminId).isNotNull();

        ResponseEntity<String> deleted =
                rest.exchange(
                        baseUrl + "/api/v1/roles/" + superAdminId,
                        org.springframework.http.HttpMethod.DELETE,
                        new org.springframework.http.HttpEntity<>(bearer(admin)),
                        String.class);

        // Deleting it would lock the business out of its own system permanently.
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(deleted).get("code").asString()).isEqualTo("role.system_role_immutable");
    }

    @Test
    @DisplayName("an unknown permission is rejected rather than quietly dropped")
    void unknownPermissionsAreRejected() {
        String admin = adminAccessToken();

        ResponseEntity<String> response =
                post(
                        "/api/v1/roles",
                        Map.of(
                                "code",
                                "TYPO_ROLE_" + UUID.randomUUID().toString().substring(0, 8),
                                "name",
                                "Role with a typo",
                                "permissions",
                                List.of("user:view", "sale:vodi")),
                        admin);

        // Ignoring it would create a role that looks right in the UI and silently does less.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("code").asString()).isEqualTo("permission.unknown");
        assertThat(json(response).get("detail").asString()).contains("sale:vodi");
    }

    @Test
    @DisplayName(
            "the bootstrap administrator exists, holds SUPER_ADMIN and must change its password")
    void bootstrapAdministratorIsUsable() {
        String admin = adminAccessToken();
        JsonNode me = json(get("/api/v1/auth/me", admin));

        assertThat(me.get("roles").toString()).contains("SUPER_ADMIN");
        assertThat(me.get("permissions").toString()).contains("*");
        assertThat(me.get("mustChangePassword").asBoolean()).isTrue();
        // Bootstrapped with every branch, so it can act anywhere from the outset.
        assertThat(me.get("branchIds")).isNotEmpty();
    }

    // --- validation -----------------------------------------------------------

    @Test
    @DisplayName("a short password is rejected and never echoed back")
    void weakPasswordIsRejected() {
        ResponseEntity<String> response =
                post(
                        "/api/v1/auth/register",
                        Map.of(
                                "email", "weak-" + System.nanoTime() + "@pos.test",
                                "password", "short",
                                "fullName", "Weak Password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("code").asString()).isEqualTo("request.validation_failed");
        assertThat(response.getBody()).contains("password").doesNotContain("\"short\"");
    }

    @Test
    void malformedEmailIsRejected() {
        ResponseEntity<String> response =
                post(
                        "/api/v1/auth/register",
                        Map.of(
                                "email",
                                "not-an-email",
                                "password",
                                "CorrectHorseBattery1",
                                "fullName",
                                "X"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers --------------------------------------------------------------

    private static JsonNode json(ResponseEntity<String> response) {
        return EventJson.mapper().readTree(response.getBody());
    }

    private static org.springframework.http.HttpHeaders bearer(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
