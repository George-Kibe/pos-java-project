package com.pos.payment.config;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.pos.payment.client.daraja.DarajaClient;
import com.pos.payment.client.daraja.DarajaProperties;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfiguration.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DarajaClient darajaClient(
            RestClient.Builder builder, DarajaProperties properties, Clock clock) {
        if (!properties.isConfigured()) {
            // Loud once at startup, then every M-Pesa tender fails fast with a clear reason.
            log.warn(
                    "M-Pesa is not configured (consumer key/secret, shortcode, passkey, callback URL"
                            + " and callback token are all required); M-Pesa tenders will fail as"
                            + " PROVIDER_UNAVAILABLE");
        } else if (!properties.canReverse()) {
            log.warn("M-Pesa reversals are not configured; M-Pesa refunds will need a person");
        }
        RestClient http =
                builder.requestFactory(
                                ClientHttpRequestFactoryBuilder.detect()
                                        .build(
                                                HttpClientSettings.defaults()
                                                        .withTimeouts(
                                                                properties
                                                                        .connectTimeoutOrDefault(),
                                                                properties.readTimeoutOrDefault())))
                        .build();
        return new DarajaClient(http, properties, clock);
    }
}
