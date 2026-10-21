package com.ethan.exchange.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "exchange.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final DemoProperties properties;

    public DemoDataInitializer(
            JdbcTemplate jdbc, PasswordEncoder passwords, DemoProperties properties) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        long traderId = upsertUser(
                properties.traderEmail(), properties.traderPassword(), "TRADER");
        upsertUser(properties.adminEmail(), properties.adminPassword(), "ADMIN");
        fundOnce(traderId, 1, 2_000_000);
        fundOnce(traderId, 2, 10_000_000_000_000L);
    }

    private long upsertUser(String email, String password, String role) {
        jdbc.update("""
                INSERT INTO app_users (email, password_hash, user_role)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash),
                    user_role = VALUES(user_role), enabled = TRUE
                """, email.toLowerCase(java.util.Locale.ROOT), passwords.encode(password), role);
        Long id = jdbc.queryForObject(
                "SELECT id FROM app_users WHERE email = ?", Long.class,
                email.toLowerCase(java.util.Locale.ROOT));
        if (id == null) {
            throw new IllegalStateException("demo user ID was not returned");
        }
        return id;
    }

    private void fundOnce(long userId, long assetId, long amount) {
        String journalId = UUID.randomUUID().toString();
        String businessRef = "DEMO_FUND:" + userId + ":" + assetId;
        int claimed = jdbc.update("""
                INSERT IGNORE INTO ledger_journals (journal_id, journal_type, business_ref)
                VALUES (?, 'DEMO_FUND', ?)
                """, journalId, businessRef);
        if (claimed == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO wallets (user_id, asset_id, available, locked)
                VALUES (?, ?, 0, 0)
                ON DUPLICATE KEY UPDATE id = wallets.id
                """, userId, assetId);
        Map<String, Object> wallet = jdbc.queryForMap("""
                SELECT id, available, version FROM wallets
                WHERE user_id = ? AND asset_id = ? FOR UPDATE
                """, userId, assetId);
        long next = Math.addExact(((Number) wallet.get("available")).longValue(), amount);
        int changed = jdbc.update("""
                UPDATE wallets SET available = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, next, wallet.get("id"), wallet.get("version"));
        if (changed != 1) {
            throw new IllegalStateException("demo wallet version changed");
        }
        jdbc.update("""
                INSERT INTO ledger_entries (
                    journal_id, business_ref, wallet_id, asset_id, posting_type,
                    delta_available, delta_locked)
                VALUES (?, ?, ?, ?, 'DEMO_FUND', ?, 0)
                """, journalId, businessRef, wallet.get("id"), assetId, amount);
    }
}
