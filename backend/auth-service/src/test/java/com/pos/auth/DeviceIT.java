package com.pos.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.pos.events.EventJson;

import tools.jackson.databind.JsonNode;

/** Signing in only from registered devices, as production runs. */
@DisplayName("Registered devices")
@TestPropertySource(properties = "pos.auth.devices.required=true")
class DeviceIT extends AuthTestBase {

    private static final String PASSWORD = "TemporaryPassword1";

    private record Person(String id, String email) {}

    private record Enrolled(String id, String secret) {}

    @Test
    @DisplayName("a manager's code enrols a device once, and it then admits its staff")
    void aCodeEnrolsADeviceOnceAndItAdmitsStaff() {
        String branch = newBranch();
        Enrolled till =
                enrol(
                        register(adminAccessToken(), branch, "Till 1")
                                .get("enrolmentCode")
                                .asString());
        Person manager = person("BRANCH_MANAGER", branch);
        String managerToken = accessToken(login(manager, till.secret()));

        JsonNode registration = register(managerToken, branch, "Back office PC");
        String code = registration.get("enrolmentCode").asString();
        assertThat(code).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
        assertThat(registration.get("device").get("status").asString()).isEqualTo("PENDING");

        // Typed as a person would: lower case, no dash.
        ResponseEntity<String> enrolled =
                post(
                        "/api/v1/device-enrolments",
                        Map.of("code", code.replace("-", "").toLowerCase()));
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json(enrolled);
        assertThat(body.get("name").asString()).isEqualTo("Back office PC");
        assertThat(body.get("branchName").asString()).startsWith("Device branch");
        assertThat(body.get("deviceSecret").asString()).isNotBlank();

        ResponseEntity<String> again = post("/api/v1/device-enrolments", Map.of("code", code));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).contains("device.enrolment_invalid");

        JsonNode listed = json(get("/api/v1/devices?branchId=" + branch, managerToken));
        assertThat(listed.findValuesAsString("status")).containsOnly("ACTIVE");
        assertThat(listed.findValuesAsString("name"))
                .containsExactlyInAnyOrder("Till 1", "Back office PC");
    }

    @Test
    @DisplayName("a cashier's right password is refused from an unregistered device")
    void aCashierCannotSignInFromAnUnregisteredDevice() {
        String branch = newBranch();
        Enrolled till =
                enrol(
                        register(adminAccessToken(), branch, "Till 1")
                                .get("enrolmentCode")
                                .asString());
        Person cashier = person("CASHIER", branch);

        ResponseEntity<String> home = login(cashier, null);
        assertThat(home.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(home.getBody()).contains("auth.device_not_registered");

        assertThat(login(cashier, "a-secret-nobody-was-given").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(login(cashier, till.secret()).getStatusCode()).isEqualTo(HttpStatus.OK);
        Long lastSeen =
                jdbc.sql(
                                "SELECT count(*) FROM auth.devices WHERE id = :id::uuid AND last_seen_at IS NOT NULL")
                        .param("id", till.id())
                        .query(Long.class)
                        .single();
        assertThat(lastSeen).isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong password from an unregistered device still reads as a wrong password")
    void theDeviceIsCheckedOnlyAfterThePassword() {
        Person cashier = person("CASHIER", newBranch());
        ResponseEntity<String> response =
                post(
                        "/api/v1/auth/login",
                        Map.of("email", cashier.email(), "password", "NotThePassword1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("auth.invalid_credentials");
    }

    @Test
    @DisplayName("the administrator may sign in from any device, to register the first one")
    void theAdministratorIsExempt() {
        assertThat(adminAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("revoking a device ends the sessions begun on it and refuses it from then on")
    void revokingADeviceEndsItsSessions() {
        String branch = newBranch();
        String admin = adminAccessToken();
        Enrolled till = enrol(register(admin, branch, "Till 2").get("enrolmentCode").asString());
        Person cashier = person("CASHIER", branch);
        String refreshToken = json(login(cashier, till.secret())).get("refreshToken").asString();

        ResponseEntity<String> revoked =
                post(
                        "/api/v1/devices/" + till.id() + "/revoke",
                        Map.of("reason", "Stolen from the lane"),
                        admin);
        assertThat(json(revoked).get("status").asString()).isEqualTo("REVOKED");

        assertThat(
                        post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(cashier, till.secret()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Long audited =
                jdbc.sql(
                                "SELECT count(*) FROM auth.audit_log WHERE action = 'device.revoked'"
                                        + " AND resource_id = :id AND details::text LIKE '%Stolen from the lane%'")
                        .param("id", till.id())
                        .query(Long.class)
                        .single();
        assertThat(audited).isEqualTo(1);
    }

    @Test
    @DisplayName("a session started before registration was required ends at its next refresh")
    void aSessionWithoutADeviceDoesNotOutliveTheRequirement() {
        Person cashier = person("CASHIER", newBranch());
        // As though issued before registration was switched on: a live token with no device.
        String refreshToken = "pre-requirement-" + System.nanoTime();
        jdbc.sql(
                        "INSERT INTO auth.refresh_tokens (id, user_id, family_id, token_hash, expires_at)"
                                + " VALUES (gen_random_uuid(), :user::uuid, gen_random_uuid(), :hash, now() + interval '1 day')")
                .param("user", cashier.id())
                .param("hash", com.pos.auth.security.SecureTokens.hash(refreshToken))
                .update();

        assertThat(
                        post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a manager runs only their own branches' devices")
    void aManagerCannotRegisterOrRevokeAtAnotherBranch() {
        String admin = adminAccessToken();
        String own = newBranch();
        String other = newBranch();
        Enrolled till = enrol(register(admin, own, "Till 1").get("enrolmentCode").asString());
        Enrolled elsewhere =
                enrol(register(admin, other, "Till 9").get("enrolmentCode").asString());
        String manager = accessToken(login(person("BRANCH_MANAGER", own), till.secret()));

        assertThat(
                        post(
                                        "/api/v1/devices",
                                        Map.of("branchId", other, "name", "Sneaky"),
                                        manager)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        post(
                                        "/api/v1/devices/" + elsewhere.id() + "/revoke",
                                        Map.of("reason", "x"),
                                        manager)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/devices?branchId=" + other, manager).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String status =
                jdbc.sql("SELECT status FROM auth.devices WHERE id = :id::uuid")
                        .param("id", elsewhere.id())
                        .query(String.class)
                        .single();
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a cashier may not register a device")
    void aCashierCannotRegisterADevice() {
        String branch = newBranch();
        Enrolled till =
                enrol(
                        register(adminAccessToken(), branch, "Till 1")
                                .get("enrolmentCode")
                                .asString());
        String cashier = accessToken(login(person("CASHIER", branch), till.secret()));
        assertThat(
                        post(
                                        "/api/v1/devices",
                                        Map.of("branchId", branch, "name", "My phone"),
                                        cashier)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a code past its time is refused, and the device reads as expired")
    void anExpiredCodeIsRefused() {
        String branch = newBranch();
        String admin = adminAccessToken();
        JsonNode registration = register(admin, branch, "Till 3");
        jdbc.sql(
                        "UPDATE auth.devices SET enrolment_expires_at = now() - interval '1 minute' WHERE id = :id::uuid")
                .param("id", registration.get("device").get("id").asString())
                .update();

        assertThat(
                        post(
                                        "/api/v1/device-enrolments",
                                        Map.of(
                                                "code",
                                                registration.get("enrolmentCode").asString()))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        json(get("/api/v1/devices?branchId=" + branch, admin))
                                .findValuesAsString("status"))
                .containsExactly("EXPIRED");
    }

    // --- helpers --------------------------------------------------------------

    private JsonNode register(String token, String branch, String name) {
        return json(post("/api/v1/devices", Map.of("branchId", branch, "name", name), token));
    }

    private Enrolled enrol(String code) {
        JsonNode body = json(post("/api/v1/device-enrolments", Map.of("code", code)));
        return new Enrolled(body.get("deviceId").asString(), body.get("deviceSecret").asString());
    }

    private ResponseEntity<String> login(Person person, String deviceSecret) {
        Map<String, Object> body =
                new HashMap<>(Map.of("email", person.email(), "password", PASSWORD));
        if (deviceSecret != null) {
            body.put("deviceSecret", deviceSecret);
        }
        return post("/api/v1/auth/login", body);
    }

    private static String accessToken(ResponseEntity<String> login) {
        return json(login).get("accessToken").asString();
    }

    private String newBranch() {
        String code = "DV" + (System.nanoTime() % 100_000_000);
        return json(post(
                        "/api/v1/branches",
                        Map.of("code", code, "name", "Device branch " + code),
                        adminAccessToken()))
                .get("id")
                .asString();
    }

    /** Created by the administrator, who is exempt; each then signs in from a device. */
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
        return new Person(id, email);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(response.getStatusCode() + " " + response.getBody());
        }
        return EventJson.mapper().readTree(response.getBody());
    }
}
