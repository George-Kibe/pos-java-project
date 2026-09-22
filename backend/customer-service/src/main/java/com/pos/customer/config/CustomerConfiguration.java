package com.pos.customer.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CustomerConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
