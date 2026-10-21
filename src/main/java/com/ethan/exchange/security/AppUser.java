package com.ethan.exchange.security;

public record AppUser(long id, String email, String passwordHash, String role, boolean enabled) {
}
