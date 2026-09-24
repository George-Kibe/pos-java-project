package com.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.pos.events.EventJson;

import tools.jackson.databind.JsonNode;

@DisplayName("Supervisor PINs and lane approvals")
class ApprovalIT extends AuthTestBase {

    private static final String PASSWORD = "TemporaryPassword1";

    private record Person(String id, String email, String token) {}

    @Test
    @DisplayName("a supervisor's PIN yields a two-minute token for one permission at one branch")
    void aSupervisorsPinApprovesOneActionAtOneBranch() {
        String branch = newBranch();
        Person supervisor = person("SUPERVISOR", branch);
        Person cashier = person("CASHIER", branch);

        assertThat(setPin(supervisor, "4826").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(json(get("/api/v1/auth/me", supervisor.token())).get("hasPin").asBoolean())
                .isTrue();

        JsonNode approvers = json(get(approversPath(branch, "price:override"), cashier.token()));
        assertThat(approvers.findValuesAsString("id")).contains(supervisor.id());
        assertThat(approvers.findValuesAsString("id")).doesNotContain(cashier.id());

        ResponseEntity<String> approved =
                approve(cashier, supervisor, "4826", "price:override", branch);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(approved);
        assertThat(body.get("approverName").asString()).isEqualTo("Person SUPERVISOR");

        JsonNode claims = claims(body.get("approvalToken").asString());
        assertThat(claims.get("sub").asString()).isEqualTo(supervisor.id());
        assertThat(claims.get("perms").get(0).asString()).isEqualTo("price:override");
        assertThat(claims.get("perms").size()).isEqualTo(1);
        assertThat(claims.get("branches").size()).isEqualTo(1);
        assertThat(claims.get("branches").get(0).asString()).isEqualTo(branch);
        assertThat(claims.get("act").asString()).isEqualTo(cashier.id());
        assertThat(claims.get("approval").asBoolean()).isTrue();
        long lifetime = claims.get("exp").asLong() - claims.get("iat").asLong();
        assertThat(lifetime).isEqualTo(120);

        // The approval is audited as the supervisor.
        Long granted =
                jdbc.sql(
                                "SELECT count(*) FROM auth.audit_log WHERE action = 'approval.granted'"
                                        + " AND actor_id = :id::uuid AND resource_id = :cashier")
                        .param("id", supervisor.id())
                        .param("cashier", cashier.id())
                        .query(Long.class)
                        .single();
        assertThat(granted).isEqualTo(1);
    }

    @Test
    @DisplayName("each wrong PIN is counted despite the refusal, and five lock the PIN")
    void wrongPinsAreCountedAndLockThePin() {
        String branch = newBranch();
        Person supervisor = person("SUPERVISOR", branch);
        Person cashier = person("CASHIER", branch);
        setPin(supervisor, "4826");

        ResponseEntity<String> wrong = approve(cashier, supervisor, "1357", "sale:void", branch);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody()).contains("approval.pin_incorrect");
        approve(cashier, supervisor, "1358", "sale:void", branch);
        // The rollback trap: counted in its own transaction, so the refusals did not undo it.
        assertThat(pinFailures(supervisor)).isEqualTo(2);

        for (int i = 0; i < 3; i++) {
            approve(cashier, supervisor, "2468", "sale:void", branch);
        }
        ResponseEntity<String> locked = approve(cashier, supervisor, "4826", "sale:void", branch);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(locked.getBody()).contains("approval.pin_locked");

        // A locked PIN is not a locked account: the supervisor still signs in.
        assertThat(
                        post(
                                        "/api/v1/auth/login",
                                        Map.of("email", supervisor.email(), "password", PASSWORD))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a correct PIN clears the count of earlier wrong ones")
    void aCorrectPinClearsTheFailureCount() {
        String branch = newBranch();
        Person supervisor = person("SUPERVISOR", branch);
        Person cashier = person("CASHIER", branch);
        setPin(supervisor, "4826");

        approve(cashier, supervisor, "1357", "sale:refund", branch);
        assertThat(approve(cashier, supervisor, "4826", "sale:refund", branch).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(pinFailures(supervisor)).isZero();
    }

    @Test
    @DisplayName(
            "setting a PIN needs an approver's role, the password, and a PIN that is not obvious")
    void settingAPinIsGuarded() {
        String branch = newBranch();
        Person supervisor = person("SUPERVISOR", branch);
        Person cashier = person("CASHIER", branch);

        assertThat(setPin(cashier, "4826").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (String obvious : List.of("1111", "1234", "987654", "3456")) {
            ResponseEntity<String> refused = setPin(supervisor, obvious);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody()).contains("pin.too_simple");
        }
        assertThat(setPin(supervisor, "12a4").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> wrongPassword =
                put(
                        "/api/v1/auth/pin",
                        Map.of("currentPassword", "NotMyPassword1", "pin", "4826"),
                        supervisor.token());
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(json(get("/api/v1/auth/me", supervisor.token())).get("hasPin").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName(
            "an approval is refused for another branch, oneself, or a permission no PIN covers")
    void approvalsStayWithinTheirLimits() {
        String branch = newBranch();
        String elsewhere = newBranch();
        Person supervisor = person("SUPERVISOR", branch);
        Person farAway = person("SUPERVISOR", elsewhere);
        Person cashier = person("CASHIER", branch);
        setPin(supervisor, "4826");
        setPin(farAway, "4826");

        // A supervisor not assigned to the lane's branch.
        ResponseEntity<String> otherBranch = approve(cashier, farAway, "4826", "sale:void", branch);
        assertThat(otherBranch.getBody()).contains("approval.not_an_approver");
        assertThat(
                        json(get(approversPath(branch, "sale:void"), cashier.token()))
                                .findValuesAsString("id"))
                .doesNotContain(farAway.id());

        // A lane at a branch the cashier does not work at.
        assertThat(approve(cashier, supervisor, "4826", "sale:void", elsewhere).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Only lane actions; a PIN never approves administration.
        ResponseEntity<String> admin = approve(cashier, supervisor, "4826", "user:manage", branch);
        assertThat(admin.getBody()).contains("approval.permission_not_approvable");

        // Four eyes: nobody approves their own request.
        assertThat(approve(supervisor, supervisor, "4826", "sale:void", branch).getBody())
                .contains("approval.self");
    }

    // --- helpers ---------------------------------------------------------------

    private ResponseEntity<String> setPin(Person person, String pin) {
        return put(
                "/api/v1/auth/pin",
                Map.of("currentPassword", PASSWORD, "pin", pin),
                person.token());
    }

    private ResponseEntity<String> approve(
            Person requester, Person approver, String pin, String permission, String branch) {
        Map<String, Object> body = new HashMap<>();
        body.put("approverId", approver.id());
        body.put("pin", pin);
        body.put("permission", permission);
        body.put("branchId", branch);
        return post("/api/v1/auth/approvals", body, requester.token());
    }

    private static String approversPath(String branch, String permission) {
        return "/api/v1/auth/approvers?branchId=" + branch + "&permission=" + permission;
    }

    private int pinFailures(Person person) {
        Integer failures =
                jdbc.sql("SELECT pin_failed_attempts FROM auth.users WHERE id = :id::uuid")
                        .param("id", person.id())
                        .query(Integer.class)
                        .single();
        return failures == null ? -1 : failures;
    }

    private String newBranch() {
        String code = "AP" + (System.nanoTime() % 100_000_000);
        return json(post(
                        "/api/v1/branches",
                        Map.of("code", code, "name", "Approval branch " + code),
                        adminAccessToken()))
                .get("id")
                .asString();
    }

    private Person person(String role, String branch) {
        String admin = adminAccessToken();
        String email = role.toLowerCase() + "-" + System.nanoTime() + "@pos.test";
        String id =
                json(post(
                                "/api/v1/users",
                                Map.of(
                                        "email",
                                        email,
                                        "temporaryPassword",
                                        PASSWORD,
                                        "fullName",
                                        "Person " + role,
                                        "roles",
                                        List.of(role)),
                                admin))
                        .get("id")
                        .asString();
        put("/api/v1/users/" + id + "/branches", Map.of("branchIds", List.of(branch)), admin);
        return new Person(id, email, loginForAccessToken(email, PASSWORD));
    }

    private static JsonNode claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        return EventJson.mapper()
                .readTree(
                        new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private static JsonNode json(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(response.getStatusCode() + " " + response.getBody());
        }
        return EventJson.mapper().readTree(response.getBody());
    }
}
