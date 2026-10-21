package com.ethan.exchange.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties(prefix = "exchange.security")
public record SecurityProperties(String issuer, Duration tokenTtl, String jwtSecret) {
    public SecurityProperties {
        if (issuer == null || issuer.isBlank() || tokenTtl == null || tokenTtl.isZero()
                || tokenTtl.isNegative() || jwtSecret == null
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("invalid security configuration");
        }
    }
}
