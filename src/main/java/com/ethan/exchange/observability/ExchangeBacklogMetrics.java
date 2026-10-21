package com.ethan.exchange.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExchangeBacklogMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    public ExchangeBacklogMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "exchange.outbox.pending", "Pending transactional outbox rows",
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'");
        gauge(registry, "exchange.outbox.dead", "Dead transactional outbox rows",
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'DEAD'");
        gauge(registry, "exchange.deposit.review", "Deposits requiring manual review",
                "SELECT COUNT(*) FROM chain_deposits WHERE deposit_status = 'REVIEW'");
        gauge(registry, "exchange.withdrawal.review", "Withdrawals requiring manual review",
                "SELECT COUNT(*) FROM withdrawal_requests WHERE withdrawal_status = 'REVIEW'");
        gauge(registry, "exchange.chain.blocked", "Blocked chain indexers",
                "SELECT COUNT(*) FROM chain_sync_state WHERE sync_status = 'BLOCKED'");
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, this, ignored -> scalar(sql))
                .description(description)
                .register(registry);
    }

    private double scalar(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
