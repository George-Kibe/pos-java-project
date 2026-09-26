package com.pos.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * The gateway serving only the business's own networks. The test client connects from localhost,
 * which stands in for Traefik: a proxy on a private network, whose {@code X-Forwarded-For} Tomcat
 * reads from the right to find the client. 198.51.100.0/24 plays a branch; 203.0.113.0/24 someone's
 * home.
 */
@TestPropertySource(properties = "pos.gateway.client-access.allowed-networks=198.51.100.0/24")
class ClientNetworkIT extends GatewayTestBase {

    private static final String LOGIN = "{\"email\":\"a@b.c\",\"password\":\"x\"}";

    private ResponseEntity<String> postFrom(String forwardedFor, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        return rest.exchange(
                baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void stubLogin() {
        DOWNSTREAM.stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/auth/login"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("{}")));
    }

    @Test
    @DisplayName("a branch may sign in")
    void aBranchNetworkIsServed() {
        stubLogin();
        assertThat(postFrom("198.51.100.9", "/api/v1/auth/login", LOGIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a home network is refused before sign-in is attempted")
    void aHomeNetworkIsRefusedBeforeSignIn() {
        stubLogin();
        ResponseEntity<String> response = postFrom("203.0.113.7", "/api/v1/auth/login", LOGIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("access.network_denied");
        DOWNSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/auth/login")));
    }

    @Test
    @DisplayName("claiming a branch address in the header does not make a home network a branch")
    void aForgedForwardedForIsNotBelieved() {
        stubLogin();
        // The client wrote the first entry; the proxy appended where it really came from.
        assertThat(
                        postFrom("198.51.100.9, 203.0.113.7", "/api/v1/auth/login", LOGIN)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an entry that is not an address is refused, never looked up")
    void aMalformedForwardedForIsRefused() {
        stubLogin();
        assertThat(postFrom("branch.example.com", "/api/v1/auth/login", LOGIN).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("M-Pesa still reaches its callback from Safaricom's own addresses")
    void providerCallbacksAreOpenToEveryNetwork() {
        DOWNSTREAM.stubFor(
                WireMock.post(WireMock.urlPathMatching("/api/v1/payments/mpesa/callbacks/stk/.*"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("{}")));

        assertThat(
                        postFrom("196.201.214.200", "/api/v1/payments/mpesa/callbacks/stk/t", "{}")
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a callback whose service is down gets the fallback's 503, not a network refusal")
    void anOpenPathsFallbackIsNotRefused() {
        DOWNSTREAM.stubFor(
                WireMock.post(WireMock.urlPathMatching("/api/v1/payments/mpesa/callbacks/stk/.*"))
                        .willReturn(WireMock.aResponse().withStatus(503)));

        assertThat(
                        postFrom("196.201.214.200", "/api/v1/payments/mpesa/callbacks/stk/t", "{}")
                                .getStatusCode())
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("health checks answer from anywhere")
    void probesAreOpen() {
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a new made-up address per attempt does not reset the sign-in limit")
    void theLoginLimitCannotBeDodgedWithForwardedFor() {
        stubLogin();
        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            ResponseEntity<String> response =
                    postFrom("192.0.2." + i + ", 198.51.100.9", "/api/v1/auth/login", LOGIN);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
            allowed++;
        }
        assertThat(allowed).isEqualTo(10);
    }
}
