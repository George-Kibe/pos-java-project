package com.pos.gateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Which networks may use the POS, and which proxies may say where a request came from.
 *
 * <p>Staff work only from a branch or head office, so production lists those sites' router
 * addresses. Traefik enforces the same list at the edge, where the connection's address cannot be
 * forged; this is the second line, for anything that reaches the gateway another way. The client's
 * address is Tomcat's, after its RemoteIpValve has read {@code X-Forwarded-For} past the proxies on
 * the private networks ({@code server.forward-headers-strategy: native}).
 */
@ConfigurationProperties(prefix = "pos.gateway.client-access")
@Getter
@Setter
public class GatewayClientAccessProperties {

    /**
     * Addresses or CIDR ranges a client may come from. Empty admits every network - development,
     * where the published ports are bound to this machine instead.
     */
    private List<String> allowedNetworks = new ArrayList<>();

    /**
     * Paths any network may reach: probes, and payment providers calling back from their own
     * addresses (payment-service authenticates those by their secret path token).
     */
    private List<String> openPaths =
            new ArrayList<>(List.of("/actuator/**", "/api/v1/payments/mpesa/callbacks/**"));
}
