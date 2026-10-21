package com.ethan.exchange.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "exchange.messaging")
public record MessagingProperties(
        boolean enabled,
        String topic,
        String consumerName,
        String relayName,
        int batchSize,
        int maxAttempts,
        Duration leaseDuration,
        Duration sendTimeout,
        Duration initialBackoff
) {

    public MessagingProperties {
        if (topic == null || topic.isBlank() || consumerName == null || consumerName.isBlank()
                || relayName == null || relayName.isBlank() || batchSize <= 0 || batchSize > 500
                || maxAttempts <= 0 || leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero() || sendTimeout == null || sendTimeout.isNegative()
                || sendTimeout.isZero() || initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("invalid messaging configuration");
        }
    }
}
