package com.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.pos.events.EventJson;

import tools.jackson.databind.JsonNode;

/** Administration: branches, the permission catalogue, user lifecycle and self-service password. */
class AdminManagementIT extends AuthTestBase {

    // --- branches -------------------------------------------------------------

    @Test
    void branchesCanBeListedCreatedAndUpdated() {
        String admin = adminAccessToken();

        // The migration seeds a first branch so a bootstrapped admin has somewhere to act.
        JsonNode initial = json(get("/api/v1/branches", admin));
        assertThat(initial).isNotEmpty();

        String code = "BR" + UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<String> created =
                post(
                        "/api/v1/branches",
                        Map.of("code", code, "name", "Westlands", "timezone", "Africa/Nairobi"),
                        admin);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();

        JsonNode branch = json(created);
        // Codes are normalised, so a lookup by code is unambiguous.
        assertThat(branch.get("code").asString())
                .isEqualTo(code.toUpperCase(java.util.Locale.ROOT));
        assertThat(branch.get("active").asBoolean()).isTrue();

        String id = branch.get("id").asString();
        assertThat(json(get("/api/v1/branches/" + id, admin)).get("name").asString())
                .isEqualTo("Westlands");

        ResponseEntity<String> updated =
                patch(
                        "/api/v1/branches/" + id,
                        Map.of("name", "Westlands Mall", "active", false),
                        admin);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(updated).get("name").asString()).isEqualTo("Westlands Mall");
        assertThat(json(updated).get("active").asBoolean()).isFalse();
    }

    @Test
    void duplicateBranchCodeIsRejected() {
        String admin = adminAccessToken();
        String code = "DUP" + UUID.randomUUID().toString().substring(0, 5);

        assertThat(
                        post("/api/v1/branches", Map.of("code", code, "name", "First"), admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> again =
                post("/api/v1/branches", Map.of("code", code, "name", "Second"), admin);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(again).get("code").asString()).isEqualTo("branch.code_taken");
    }

    @Test
    void unknownBranchIsNotFound() {
        assertThat(get("/api/v1/branches/" + UUID.randomUUID(), adminAccessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- the permission catalogue ---------------------------------------------

    @Test
    @DisplayName("the permission matrix the role builder renders is grouped by category")
    void permissionCatalogueIsGroupedByCategory() {
        JsonNode catalogue = json(get("/api/v1/permissions", adminAccessToken())).get("byCategory");

        assertThat(catalogue.propertyNames()).contains("sales", "inventory", "access", "system");
        assertThat(catalogue.get("sales").toString()).contains("sale:void");
        assertThat(catalogue.get("access").toString()).contains("user:manage");
    }

    // --- user lifecycle -------------------------------------------------------

    @Test
    void userProfileRolesAndBranchesCanBeAdministered() {
        String admin = adminAccessToken();
        String email = "managed-" + System.nanoTime() + "@pos.test";

        String userId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        email,
                                        "temporaryPassword",
                                        "TemporaryPassword1",
                                        "fullName",
                                        "Managed Person",
                                        "roles",
                                        List.of("CASHIER")),
                                admin))
                        .get("id")
                        .asString();

        ResponseEntity<String> profile =
                patch(
                        "/api/v1/users/" + userId,
                        Map.of("fullName", "Managed Person Renamed", "phone", "0712345678"),
                        admin);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(profile).get("fullName").asString()).isEqualTo("Managed Person Renamed");

        ResponseEntity<String> roles =
                put(
                        "/api/v1/users/" + userId + "/roles",
                        Map.of("roles", List.of("SUPERVISOR", "CASHIER")),
                        admin);
        assertThat(roles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(roles).get("roles").toString()).contains("SUPERVISOR").contains("CASHIER");

        String branchId = json(get("/api/v1/branches", admin)).get(0).get("id").asString();
        ResponseEntity<String> branches =
                put(
                        "/api/v1/users/" + userId + "/branches",
                        Map.of("branchIds", List.of(branchId)),
                        admin);
        assertThat(branches.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(branches).get("branchIds").toString()).contains(branchId);

        // Searchable by name and by email.
        assertThat(json(get("/api/v1/users?query=Renamed", admin)).get("totalElements").asLong())
                .isPositive();
        assertThat(json(get("/api/v1/users?query=managed-", admin)).get("totalElements").asLong())
                .isPositive();
    }

    @Test
    @DisplayName("suspending a user stops them signing in and ends their sessions")
    void suspendingAUserEndsAccess() {
        String admin = adminAccessToken();
        String email = "suspend-" + System.nanoTime() + "@pos.test";
        String password = "TemporaryPassword1";

        String userId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        email,
                                        "temporaryPassword",
                                        password,
                                        "fullName",
                                        "Soon Suspended",
                                        "roles",
                                        List.of("CASHIER")),
                                admin))
                        .get("id")
                        .asString();

        String refreshToken =
                json(post("/api/v1/auth/login", Map.of("email", email, "password", password)))
                        .get("refreshToken")
                        .asString();

        ResponseEntity<String> suspended =
                put("/api/v1/users/" + userId + "/status", Map.of("status", "SUSPENDED"), admin);
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(suspended).get("status").asString()).isEqualTo("SUSPENDED");

        // Existing session is gone, and a fresh sign-in is refused.
        assertThat(
                        post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        post("/api/v1/auth/login", Map.of("email", email, "password", password))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Reinstating restores access.
        assertThat(
                        put(
                                        "/api/v1/users/" + userId + "/status",
                                        Map.of("status", "ACTIVE"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        post("/api/v1/auth/login", Map.of("email", email, "password", password))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an administrator cannot change their own status and lock themselves out")
    void administratorCannotSuspendThemselves() {
        String admin = adminAccessToken();
        String ownId = json(get("/api/v1/auth/me", admin)).get("id").asString();

        ResponseEntity<String> response =
                put("/api/v1/users/" + ownId + "/status", Map.of("status", "DEACTIVATED"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("code").asString())
                .isEqualTo("user.cannot_change_own_status");
    }

    @Test
    void creatingAUserWithATakenEmailIsRejected() {
        String admin = adminAccessToken();
        Registered existing = registerAndVerify("taken");

        ResponseEntity<String> response =
                post(
                        "/api/v1/users",
                        Map.of(
                                "email", existing.email(),
                                "temporaryPassword", "TemporaryPassword1",
                                "fullName", "Duplicate"),
                        admin);

        // An administrator is already authorised to know this, so unlike self-registration it is
        // reported plainly.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("code").asString()).isEqualTo("user.email_taken");
    }

    @Test
    void assigningAnUnknownRoleOrBranchIsRejected() {
        String admin = adminAccessToken();
        String email = "badassign-" + System.nanoTime() + "@pos.test";

        ResponseEntity<String> unknownRole =
                post(
                        "/api/v1/users",
                        Map.of(
                                "email",
                                email,
                                "temporaryPassword",
                                "TemporaryPassword1",
                                "fullName",
                                "Bad Assign",
                                "roles",
                                List.of("NOT_A_ROLE")),
                        admin);
        assertThat(unknownRole.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(unknownRole).get("code").asString()).isEqualTo("role.unknown");

        ResponseEntity<String> unknownBranch =
                post(
                        "/api/v1/users",
                        Map.of(
                                "email",
                                email,
                                "temporaryPassword",
                                "TemporaryPassword1",
                                "fullName",
                                "Bad Assign",
                                "branchIds",
                                List.of(UUID.randomUUID().toString())),
                        admin);
        assertThat(unknownBranch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(unknownBranch).get("code").asString()).isEqualTo("branch.unknown");
    }

    @Test
    void unknownUserIsNotFound() {
        assertThat(get("/api/v1/users/" + UUID.randomUUID(), adminAccessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- self-service password change -----------------------------------------

    @Test
    @DisplayName("changing your own password clears the forced-change flag and ends other sessions")
    void changingOwnPassword() {
        String admin = adminAccessToken();
        String email = "changepw-" + System.nanoTime() + "@pos.test";
        String temporary = "TemporaryPassword1";

        post(
                "/api/v1/users",
                Map.of(
                        "email",
                        email,
                        "temporaryPassword",
                        temporary,
                        "fullName",
                        "Change Password",
                        "roles",
                        List.of("CASHIER")),
                admin);

        JsonNode session =
                json(post("/api/v1/auth/login", Map.of("email", email, "password", temporary)));
        String token = session.get("accessToken").asString();
        String refreshToken = session.get("refreshToken").asString();
        // Created by an administrator with a password they chose, so it must be replaced.
        assertThat(session.get("mustChangePassword").asBoolean()).isTrue();

        // The current password is required even though the caller is authenticated: a stolen
        // access token must not be enough to take the account over.
        ResponseEntity<String> wrongCurrent =
                post(
                        "/api/v1/auth/change-password",
                        Map.of(
                                "currentPassword",
                                "NotMyPassword1",
                                "newPassword",
                                "MyOwnPassword77"),
                        token);
        assertThat(wrongCurrent.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(wrongCurrent).get("code").asString())
                .isEqualTo("password.current_incorrect");

        ResponseEntity<String> reuse =
                post(
                        "/api/v1/auth/change-password",
                        Map.of("currentPassword", temporary, "newPassword", temporary),
                        token);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(reuse).get("code").asString()).isEqualTo("password.unchanged");

        String chosen = "MyOwnPassword77";
        assertThat(
                        post(
                                        "/api/v1/auth/change-password",
                                        Map.of("currentPassword", temporary, "newPassword", chosen),
                                        token)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Other sessions ended; the new password works and the flag is cleared.
        assertThat(
                        post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode after =
                json(post("/api/v1/auth/login", Map.of("email", email, "password", chosen)));
        assertThat(after.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(
                        post("/api/v1/auth/login", Map.of("email", email, "password", temporary))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changingPasswordRequiresAuthentication() {
        assertThat(
                        post(
                                        "/api/v1/auth/change-password",
                                        Map.of(
                                                "currentPassword", "whatever12345",
                                                "newPassword", "somethingElse12"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- authorization on the admin surface -----------------------------------

    @Test
    void branchAdministrationRequiresPermission() {
        Registered user = registerAndVerify("branchperm");
        String token = loginForAccessToken(user.email(), user.password());

        assertThat(get("/api/v1/branches", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        post("/api/v1/branches", Map.of("code", "NOPE", "name", "Nope"), token)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/permissions", token).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a role change publishes every holder's new token version for the gateway")
    void roleChangesPublishTokenVersionsForTheGateway() {
        String admin = adminAccessToken();

        String roleCode = "PUBLISHER_" + UUID.randomUUID().toString().substring(0, 8);
        String roleId =
                json(post(
                                "/api/v1/roles",
                                Map.of(
                                        "code",
                                        roleCode,
                                        "name",
                                        "Publisher test",
                                        "permissions",
                                        List.of("user:view")),
                                admin))
                        .get("id")
                        .asString();

        String email = "publish-" + System.nanoTime() + "@pos.test";
        String userId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        email,
                                        "temporaryPassword",
                                        "TemporaryPassword1",
                                        "fullName",
                                        "Publish Test",
                                        "roles",
                                        List.of(roleCode.toUpperCase(java.util.Locale.ROOT))),
                                admin))
                        .get("id")
                        .asString();

        // Nothing published yet: the account has never had its version bumped.
        assertThat(publishedTokenVersion(UUID.fromString(userId))).isNull();

        patch(
                "/api/v1/roles/" + roleId,
                Map.of("permissions", List.of("user:view", "user:manage")),
                admin);

        // This is what the gateway reads to refuse the token the user is holding right now.
        assertThat(publishedTokenVersion(UUID.fromString(userId))).isEqualTo("2");
    }

    @Test
    void suspendingAUserPublishesTheirTokenVersion() {
        String admin = adminAccessToken();
        String email = "suspend-publish-" + System.nanoTime() + "@pos.test";

        String userId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email", email,
                                        "temporaryPassword", "TemporaryPassword1",
                                        "fullName", "Suspend Publish"),
                                admin))
                        .get("id")
                        .asString();

        put("/api/v1/users/" + userId + "/status", Map.of("status", "SUSPENDED"), admin);

        assertThat(publishedTokenVersion(UUID.fromString(userId))).isNotNull();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        return EventJson.mapper().readTree(response.getBody());
    }
}
