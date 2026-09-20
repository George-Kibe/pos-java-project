package com.pos.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.pos.gateway.config.GatewayRateLimitProperties;

/** The single ingress. Nothing reaches a service except through here. */
@SpringBootApplication
@EnableConfigurationProperties(GatewayRateLimitProperties.class)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
