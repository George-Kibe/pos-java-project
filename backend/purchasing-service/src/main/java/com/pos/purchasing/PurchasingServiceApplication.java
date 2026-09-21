package com.pos.purchasing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Suppliers, purchase orders, goods receipts, landed cost and invoice matching. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PurchasingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PurchasingServiceApplication.class, args);
    }
}
