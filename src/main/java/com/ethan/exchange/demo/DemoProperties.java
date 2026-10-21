package com.ethan.exchange.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "exchange.demo")
public record DemoProperties(
        boolean enabled,
        String traderEmail,
        String traderPassword,
        String adminEmail,
        String adminPassword
) {
    public DemoProperties {
        if (traderEmail == null || traderEmail.isBlank() || traderPassword == null
                || traderPassword.isBlank() || adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalArgumentException("invalid demo configuration");
        }
    }
}
