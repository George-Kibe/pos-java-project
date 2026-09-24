package com.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.pos.events.EventJson;

import tools.jackson.databind.JsonNode;

/**
 * An administrator, created the way {@code make admin} creates one: the SUPER_ADMIN role, every
 * branch, a temporary password replaced at first sign-in. Proves the account can run the business -
 * branches, supervisors, roles, the audit trail - and holds every permission there is.
 */
@DisplayName("An administrator")
class AdministratorRightsIT extends AuthTestBase {

    private static final String TEMPORARY = "Temporary-Password-1";
    private static final String PASSWORD = "Administrator-Password-9";

    private String adminId;
    private String admin;

    @BeforeEach
    void anAdministratorAsMakeAdminCreatesThem() {
        String bootstrap = adminAccessToken();
        String email = "admin-" + System.nanoTime() + "@pos.test";
        adminId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        email,
                                        "temporaryPassword",
                                        TEMPORARY,
                                        "fullName",
                                        "Head Office Administrator",
                                        "roles",
                                        List.of("SUPER_ADMIN")),
                                bootstrap))
                        .get("id")
                        .asString();
        List<String> branches = json(get("/api/v1/branches", bootstrap)).findValuesAsString("id");
        put("/api/v1/users/" + adminId + "/branches", Map.of("branchIds", branches), bootstrap);

        // The temporary password is replaced before anything else, as the web app insists.
        String first = loginForAccessToken(email, TEMPORARY);
        assertThat(
                        post(
                                        "/api/v1/auth/change-password",
                                        Map.of(
                                                "currentPassword",
                                                TEMPORARY,
                                                "newPassword",
                                                PASSWORD),
                                        first)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        admin = loginForAccessToken(email, PASSWORD);
    }

    @Test
    @DisplayName("holds every permission in the catalogue, all branches included")
    void holdsEveryPermission() {
        Set<String> catalogue =
                new HashSet<>(json(get("/api/v1/permissions", admin)).findValuesAsString("code"));
        assertThat(catalogue)
                .contains("branch:access:all", "report:view", "audit:view", "supplier:create");

        JsonNode claims = claims(admin);
        Set<String> held = new HashSet<>();
        claims.get("perms").forEach(permission -> held.add(permission.asString()));
        // Expanded from the wildcard at sign-in, so every check in every service sees the real
        // name.
        assertThat(held).containsAll(catalogue);

        JsonNode me = json(get("/api/v1/auth/me", admin));
        assertThat(me.get("roles").toString()).contains("SUPER_ADMIN");
        assertThat(me.get("mustChangePassword").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("opens a branch and staffs it with a supervisor, then promotes them")
    void runsBranchesAndStaff() {
        String code = "ADM" + (System.nanoTime() % 1_000_000);
        ResponseEntity<String> branch =
                post("/api/v1/branches", Map.of("code", code, "name", "Branch " + code), admin);
        assertThat(branch.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String branchId = json(branch).get("id").asString();

        ResponseEntity<String> supervisor =
                post(
                        "/api/v1/users",
                        Map.of(
                                "email",
                                "supervisor-" + System.nanoTime() + "@pos.test",
                                "temporaryPassword",
                                TEMPORARY,
                                "fullName",
                                "New Supervisor",
                                "roles",
                                List.of("SUPERVISOR")),
                        admin);
        assertThat(supervisor.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String supervisorId = json(supervisor).get("id").asString();

        assertThat(
                        put(
                                        "/api/v1/users/" + supervisorId + "/branches",
                                        Map.of("branchIds", List.of(branchId)),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode promoted =
                json(
                        put(
                                "/api/v1/users/" + supervisorId + "/roles",
                                Map.of("roles", List.of("SUPERVISOR", "BRANCH_MANAGER")),
                                admin));
        assertThat(promoted.get("roles").toString()).contains("BRANCH_MANAGER", "SUPERVISOR");

        assertThat(
                        patch(
                                        "/api/v1/branches/" + branchId,
                                        Map.of("name", "Renamed " + code),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        put(
                                        "/api/v1/users/" + supervisorId + "/status",
                                        Map.of("status", "SUSPENDED"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("is the only one who may add a supplier: no other role holds supplier:create")
    void aloneAddsSuppliers() {
        assertThat(claims(admin).get("perms").toString()).contains("\"supplier:create\"");
        JsonNode roles = json(get("/api/v1/roles", admin));
        java.util.List<String> holders = new java.util.ArrayList<>();
        roles.forEach(
                role -> {
                    if (role.get("permissions").toString().contains("\"supplier:create\"")) {
                        holders.add(role.get("code").asString());
                    }
                });
        assertThat(holders).isEmpty();
        // Branch managers still manage the suppliers the administrator adds.
        roles.forEach(
                role -> {
                    if (role.get("code").asString().equals("BRANCH_MANAGER")) {
                        assertThat(role.get("permissions").toString()).contains("supplier:manage");
                    }
                });
    }

    @Test
    @DisplayName("builds a role of its own and sees every user and role")
    void managesRoles() {
        String code = "AUDIT_ASSISTANT_" + (System.nanoTime() % 1_000_000);
        ResponseEntity<String> role =
                post(
                        "/api/v1/roles",
                        Map.of(
                                "code",
                                code,
                                "name",
                                "Audit assistant",
                                "permissions",
                                List.of("audit:view", "report:view")),
                        admin);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(get("/api/v1/roles", admin)).toString()).contains(code, "SUPER_ADMIN");
        assertThat(get("/api/v1/users", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("reads the audit trail, including what it did itself")
    void readsTheAuditTrail() {
        post(
                "/api/v1/branches",
                Map.of("code", "AUD" + (System.nanoTime() % 1_000_000), "name", "Audited"),
                admin);

        JsonNode mine =
                json(get("/api/v1/audit?actorId=" + adminId + "&action=branch.created", admin));
        assertThat(mine.get("content")).isNotEmpty();
        assertThat(mine.get("content").get(0).get("actorId").asString()).isEqualTo(adminId);

        JsonNode everything = json(get("/api/v1/audit?size=5", admin));
        assertThat(everything.get("content").size()).isBetween(1, 5);
    }

    @Test
    @DisplayName("is the only one of the two here allowed any of it: a cashier is refused")
    void aCashierIsRefusedWhatTheAdministratorMayDo() {
        String email = "cashier-" + System.nanoTime() + "@pos.test";
        post(
                "/api/v1/users",
                Map.of(
                        "email",
                        email,
                        "temporaryPassword",
                        TEMPORARY,
                        "fullName",
                        "A Cashier",
                        "roles",
                        List.of("CASHIER")),
                admin);
        String cashier = loginForAccessToken(email, TEMPORARY);

        assertThat(get("/api/v1/audit", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        post("/api/v1/branches", Map.of("code", "NOPE1", "name", "No"), cashier)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/users", cashier).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("sees its permissions on /me by name, not as a wildcard, as the web app needs")
    void meListsEveryPermissionByName() {
        Set<String> catalogue =
                new HashSet<>(json(get("/api/v1/permissions", admin)).findValuesAsString("code"));
        JsonNode me = json(get("/api/v1/auth/me", admin));
        Set<String> listed = new HashSet<>();
        me.get("permissions").forEach(permission -> listed.add(permission.asString()));
        assertThat(listed).containsAll(catalogue);
    }

    @Test
    @DisplayName("lists the people it manages page by page, administrators left out")
    void listsNonAdministrators() {
        String cashierId = person("CASHIER", "listed");

        JsonNode everyone = json(get("/api/v1/users?size=100", admin));
        assertThat(everyone.findValuesAsString("id")).contains(adminId, cashierId);

        JsonNode others = json(get("/api/v1/users?excludeAdministrators=true&size=100", admin));
        assertThat(others.findValuesAsString("id")).contains(cashierId).doesNotContain(adminId);
        others.get("content")
                .forEach(user -> assertThat(user.get("administrator").asBoolean()).isFalse());

        JsonNode page = json(get("/api/v1/users?excludeAdministrators=true&size=1&page=0", admin));
        assertThat(page.get("content").size()).isEqualTo(1);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode searched =
                json(get("/api/v1/users?excludeAdministrators=true&query=listed", admin));
        assertThat(searched.findValuesAsString("id")).contains(cashierId);
    }

    @Test
    @DisplayName("is the only one who can make or unmake an administrator")
    void aManagerCannotGrantOrTouchWhatTheyDoNotHold() {
        String branch = json(get("/api/v1/branches", admin)).get(0).get("id").asString();
        String managerEmail = "manager-" + System.nanoTime() + "@pos.test";
        String managerId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        managerEmail,
                                        "temporaryPassword",
                                        TEMPORARY,
                                        "fullName",
                                        "A Manager",
                                        "roles",
                                        List.of("BRANCH_MANAGER")),
                                admin))
                        .get("id")
                        .asString();
        put(
                "/api/v1/users/" + managerId + "/branches",
                Map.of("branchIds", List.of(branch)),
                admin);
        String manager = loginForAccessToken(managerEmail, TEMPORARY);
        String cashierId = person("CASHIER", "managed");

        // Within their own rights, a manager manages people.
        assertThat(
                        put(
                                        "/api/v1/users/" + cashierId + "/roles",
                                        Map.of("roles", List.of("CASHIER", "SUPERVISOR")),
                                        manager)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // But never hands out more than they hold - to someone else or to themselves.
        ResponseEntity<String> escalate =
                put(
                        "/api/v1/users/" + cashierId + "/roles",
                        Map.of("roles", List.of("SUPER_ADMIN")),
                        manager);
        assertThat(escalate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(escalate.getBody()).contains("role.beyond_your_rights");
        assertThat(
                        put(
                                        "/api/v1/users/" + managerId + "/roles",
                                        Map.of("roles", List.of("SUPER_ADMIN")),
                                        manager)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        post(
                                        "/api/v1/users",
                                        Map.of(
                                                "email",
                                                "sneaky-" + System.nanoTime() + "@pos.test",
                                                "temporaryPassword",
                                                TEMPORARY,
                                                "fullName",
                                                "Sneaky",
                                                "roles",
                                                List.of("SUPER_ADMIN")),
                                        manager)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Nor touches an administrator's account.
        ResponseEntity<String> suspend =
                put("/api/v1/users/" + adminId + "/status", Map.of("status", "SUSPENDED"), manager);
        assertThat(suspend.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspend.getBody()).contains("user.beyond_your_rights");
        assertThat(json(get("/api/v1/auth/me", admin)).get("status").asString())
                .isEqualTo("ACTIVE");

        // The administrator can do all of it.
        assertThat(
                        put(
                                        "/api/v1/users/" + managerId + "/roles",
                                        Map.of("roles", List.of("SUPER_ADMIN")),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("lists a branch's staff, and supervisors may too, for setting cash limits")
    void listsBranchStaff() {
        String code = "STF" + (System.nanoTime() % 1_000_000);
        String branch =
                json(post("/api/v1/branches", Map.of("code", code, "name", "Staff " + code), admin))
                        .get("id")
                        .asString();
        String cashierId = person("CASHIER", "staffed");
        put(
                "/api/v1/users/" + cashierId + "/branches",
                Map.of("branchIds", List.of(branch)),
                admin);

        JsonNode staff = json(get("/api/v1/branches/" + branch + "/staff", admin));
        assertThat(staff.findValuesAsString("id")).containsExactly(cashierId);
        assertThat(staff.get(0).get("fullName").asString()).isEqualTo("Person staffed");

        // A supervisor at another branch sees neither the list nor its names.
        String supervisorEmail = "sup-" + System.nanoTime() + "@pos.test";
        String supervisorId =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        supervisorEmail,
                                        "temporaryPassword",
                                        TEMPORARY,
                                        "fullName",
                                        "A Supervisor",
                                        "roles",
                                        List.of("SUPERVISOR")),
                                admin))
                        .get("id")
                        .asString();
        String home = json(get("/api/v1/branches", admin)).get(0).get("id").asString();
        put(
                "/api/v1/users/" + supervisorId + "/branches",
                Map.of("branchIds", List.of(home)),
                admin);
        String supervisor = loginForAccessToken(supervisorEmail, TEMPORARY);
        assertThat(get("/api/v1/branches/" + branch + "/staff", supervisor).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/branches/" + home + "/staff", supervisor).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // And holds the new till and intraday rights.
        JsonNode me = json(get("/api/v1/auth/me", supervisor));
        assertThat(me.get("permissions").toString()).contains("till:manage", "cash:intraday");
    }

    private String person(String role, String label) {
        return json(post(
                        "/api/v1/users",
                        Map.of(
                                "email",
                                label + "-" + System.nanoTime() + "@pos.test",
                                "temporaryPassword",
                                TEMPORARY,
                                "fullName",
                                "Person " + label,
                                "roles",
                                List.of(role)),
                        admin))
                .get("id")
                .asString();
    }

    private static JsonNode claims(String jwt) {
        return EventJson.mapper()
                .readTree(
                        new String(
                                Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                                StandardCharsets.UTF_8));
    }

    private static JsonNode json(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(response.getStatusCode() + " " + response.getBody());
        }
        return EventJson.mapper().readTree(response.getBody());
    }
}
