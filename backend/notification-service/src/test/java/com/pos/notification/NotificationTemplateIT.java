package com.pos.notification;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Changing the wording of the emails: the administrator's, previewed before it is saved. */
@AutoConfigureMockMvc
@DisplayName("Email wording")
class NotificationTemplateIT extends NotificationTestBase {

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor as(String... permissions) {
        UUID user = UUID.randomUUID();
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .build())
                .authorities(
                        java.util.Arrays.stream(permissions)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(SimpleGrantedAuthority[]::new));
    }

    @Test
    @DisplayName("previewed with sample values, saved, listed as changed, and reset")
    void wordingIsPreviewedSavedAndReset() throws Exception {
        String wording =
                "{\"subject\":\"Welcome aboard, from {brand}\",\"intro\":\"Glad to have you.\","
                        + "\"closing\":\"See you on the floor.\"}";

        mockMvc.perform(
                        post("/api/v1/notification-templates/WELCOME/preview")
                                .with(as("settings:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wording))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject", is("Welcome aboard, from Test Supermarket")))
                .andExpect(jsonPath("$.html", containsString("Glad to have you.")))
                .andExpect(jsonPath("$.html", not(containsString("cid:"))))
                .andExpect(jsonPath("$.text", containsString("See you on the floor.")));

        mockMvc.perform(
                        put("/api/v1/notification-templates/WELCOME")
                                .with(as("settings:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wording))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notification-templates").with(as("settings:manage")))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(
                        jsonPath(
                                "$[?(@.type == 'WELCOME')].changed",
                                org.hamcrest.Matchers.hasItem(true)));

        mockMvc.perform(
                        delete("/api/v1/notification-templates/WELCOME")
                                .with(as("settings:manage")))
                .andExpect(jsonPath("$.subject", is("Welcome to {brand}")))
                .andExpect(jsonPath("$.changed", is(false)));

        // A subject is one line; and the wording is the administrator's alone.
        mockMvc.perform(
                        put("/api/v1/notification-templates/WELCOME")
                                .with(as("settings:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subject\":\"Two\\nlines\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        put("/api/v1/notification-templates/WELCOME")
                                .with(as("branch:manage", "user:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wording))
                .andExpect(status().isForbidden());
    }
}
