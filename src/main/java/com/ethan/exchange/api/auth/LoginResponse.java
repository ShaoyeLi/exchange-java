package com.ethan.exchange.api.auth;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        Instant expiresAt,
        long userId,
        String email,
        String role
) {
}
