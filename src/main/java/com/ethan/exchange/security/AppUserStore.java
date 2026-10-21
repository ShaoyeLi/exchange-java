package com.ethan.exchange.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AppUserStore {

    private final JdbcTemplate jdbc;

    public AppUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, email, password_hash, user_role, enabled
                    FROM app_users WHERE email = ?
                    """, (row, number) -> new AppUser(
                    row.getLong("id"), row.getString("email"),
                    row.getString("password_hash"), row.getString("user_role"),
                    row.getBoolean("enabled")), email.toLowerCase(java.util.Locale.ROOT)));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }
}
