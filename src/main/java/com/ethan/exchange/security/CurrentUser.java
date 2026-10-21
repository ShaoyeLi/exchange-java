package com.ethan.exchange.security;

import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        if (jwt == null) {
            throw new IllegalArgumentException("authenticated user is required");
        }
        return Long.parseLong(jwt.getSubject());
    }
}
