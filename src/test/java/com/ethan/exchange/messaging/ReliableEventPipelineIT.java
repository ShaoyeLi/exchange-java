package com.ethan.exchange.messaging;

import com.ethan.exchange.api.market.MarketEventSnapshot;
import com.ethan.exchange.matching.domain.OrderSide;
import com.ethan.exchange.matching.domain.OrderType;
import com.ethan.exchange.matching.domain.TimeInForce;
import com.ethan.exchange.order.application.ExchangeOrderService;
import com.ethan.exchange.order.application.PlaceOrderCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "exchange.messaging.enabled=true",
                "exchange.messaging.scheduling-enabled=false",
                "exchange.messaging.initial-backoff=0ms"
        })
class ReliableEventPipelineIT {

    private static final long BTC = 1;
    private static final long QUANTITY = 10_000;
    private static final long PRICE = 10_000;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("exchange_java")
            .withUsername("exchange")
            .withPassword("exchange")
            .withStartupTimeout(Duration.ofSeconds(120));

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0")
            .withStartupTimeout(Duration.ofSeconds(120));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ExchangeOrderService orders;

    @Autowired
    private OutboxRelayService relay;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private MarketEventStore eventStore;

    @Autowired
    private RedisMarketEventView redisView;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM market_event_projection");
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM market_event_sequences");
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM ledger_journals");
        jdbc.update("DELETE FROM trades");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallets WHERE user_id <> 0");
        jdbc.update("UPDATE wallets SET available = 0, locked = 0, version = 0 WHERE user_id = 0");
        jdbc.update("UPDATE markets SET last_arrival_sequence = 0, last_execution_sequence = 0");
        jdbc.update("""
                UPDATE outbox_relay_leases
                SET owner_id = NULL, lease_until = NULL, version = version + 1
                """);
        assertThat(REDIS.execInContainer("redis-cli", "FLUSHDB").getExitCode()).isZero();
    }

    @Test
    void committedOutboxRelaysCompleteEnvelopeToKafkaMysqlAndRedis() {
        String eventId = placeOneOrder(101, "relay-complete");

        assertThat(relay.relayOnce()).isEqualTo(1);
        awaitProjection(eventId);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT market_sequence, aggregate_version, schema_version, status
                FROM outbox_events WHERE event_id = ?
                """, eventId);
        assertThat(((Number) row.get("market_sequence")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("aggregate_version")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(redisView.lastSequence("BTC/USDT")).isEqualTo(1);
        assertThat(redisView.recent("BTC/USDT")).singleElement()
                .satisfies(json -> assertThat(json).contains(eventId, "ORDER_UPDATED"));
    }

    @Test
    void readyEventsForOneMarketRelayAsAnOrderedBatch() {
        String first = placeOneOrder(111, "relay-batch-1");
        String second = placeOneOrder(112, "relay-batch-2");
        String third = placeOneOrder(113, "relay-batch-3");

        assertThat(relay.relayOnce()).isEqualTo(3);
        awaitProjection(third);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT event_id, market_sequence, status
                FROM outbox_events
                ORDER BY market_sequence
                """);
        assertThat(rows).extracting(row -> row.get("event_id"))
                .containsExactly(first, second, third);
        assertThat(rows).extracting(row -> ((Number) row.get("market_sequence")).longValue())
                .containsExactly(1L, 2L, 3L);
        assertThat(rows).extracting(row -> row.get("status"))
                .containsOnly("PUBLISHED");
    }

    @Test
    void acknowledgedButUnmarkedEventIsResentAndProjectedOnce() {
        String eventId = placeOneOrder(102, "ack-crash");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        OutboxRelayService crashing = new OutboxRelayService(
                outbox, kafka, transactions, mapper, properties, "crash-worker",
                Clock.systemUTC(), event -> {
                    if (failOnce.compareAndSet(true, false)) {
                        throw new EventDeliveryException("simulated post-ack crash", null);
                    }
                });

        assertThat(crashing.relayOnce()).isZero();
        awaitProjection(eventId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId))
                .isEqualTo("PENDING");

        assertThat(relay.relayOnce()).isEqualTo(1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(count("SELECT COUNT(*) FROM inbox_events WHERE event_id = '" + eventId + "'"))
                    .isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM market_event_projection WHERE event_id = '"
                    + eventId + "'")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId))
                    .isEqualTo("PUBLISHED");
        });
    }

    @Test
    void databaseLeaseAllowsOnlyOneRelayOwnerAndExpiredLeaseCanBeTakenOver() {
        Instant now = Instant.now();
        Boolean firstOwner = transactions.execute(status -> outbox.tryAcquireLease(
                properties.relayName(), "owner-a", now, Duration.ofSeconds(10)));
        Boolean competingOwner = transactions.execute(status -> outbox.tryAcquireLease(
                properties.relayName(), "owner-b", now, Duration.ofSeconds(10)));
        assertThat(firstOwner).isTrue();
        assertThat(competingOwner).isFalse();
        transactions.executeWithoutResult(status -> outbox.releaseLease(
                properties.relayName(), "owner-a"));
        Boolean takeover = transactions.execute(status -> outbox.tryAcquireLease(
                properties.relayName(), "owner-b", now, Duration.ofSeconds(10)));
        assertThat(takeover).isTrue();
        transactions.executeWithoutResult(status -> outbox.releaseLease(
                properties.relayName(), "owner-b"));
    }

    @Test
    void deadEventRequiresExplicitReplayAndThenPublishes() {
        String eventId = placeOneOrder(103, "dead-replay");
        OutboxRelayService failing = new OutboxRelayService(
                outbox, kafka, transactions, mapper, properties, "failing-worker",
                Clock.systemUTC(), event -> {
                    throw new EventDeliveryException("always fail after acknowledgement", null);
                });

        for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
            assertThat(failing.relayOnce()).isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId))
                .isEqualTo("DEAD");
        assertThat(relay.relayOnce()).isZero();
        assertThat(outbox.replayDead(eventId)).isTrue();
        assertThat(relay.relayOnce()).isEqualTo(1);
        awaitProjection(eventId);
        assertThat(count("SELECT COUNT(*) FROM market_event_projection WHERE event_id = '"
                + eventId + "'")).isEqualTo(1);
    }

    @Test
    void redisLossCanBeRebuiltFromDurableProjectionAndSnapshotReportsSequence()
            throws Exception {
        String eventId = placeOneOrder(104, "redis-rebuild");
        assertThat(relay.relayOnce()).isEqualTo(1);
        awaitProjection(eventId);
        assertThat(REDIS.execInContainer("redis-cli", "FLUSHDB").getExitCode()).isZero();
        assertThat(redisView.lastSequence("BTC/USDT")).isZero();

        assertThat(redisView.rebuild("BTC/USDT")).isEqualTo(1);
        assertThat(redisView.lastSequence("BTC/USDT")).isEqualTo(1);

        ResponseEntity<MarketEventSnapshot> response = http.getForEntity(
                "/api/public/markets/events?market={market}&afterSequence=0&limit=20",
                MarketEventSnapshot.class, "BTC/USDT");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().lastSequence()).isEqualTo(1);
        assertThat(response.getBody().events()).singleElement()
                .extracting(DomainEventEnvelope::eventId).isEqualTo(eventId);
    }

    @Test
    void redisRebuildKeepsTheLatestBoundedWindowInAscendingSequence() throws Exception {
        for (long sequence = 1; sequence <= 503; sequence++) {
            eventStore.insertProjection(new DomainEventEnvelope(
                    UUID.randomUUID().toString(), "MARKET", 1, sequence,
                    "BTC/USDT", sequence, "BOOK_CHANGED", Instant.now(), 1,
                    mapper.createObjectNode().put("sequence", sequence)));
        }

        assertThat(redisView.rebuild("BTC/USDT")).isEqualTo(500);
        List<String> cached = redisView.recent("BTC/USDT");
        assertThat(cached).hasSize(500);
        assertThat(mapper.readValue(cached.getFirst(), DomainEventEnvelope.class)
                .marketSequence()).isEqualTo(4);
        assertThat(mapper.readValue(cached.getLast(), DomainEventEnvelope.class)
                .marketSequence()).isEqualTo(503);
        assertThat(redisView.lastSequence("BTC/USDT")).isEqualTo(503);
    }

    @Test
    void failingDerivedSinkDoesNotRollbackInboxOrDurableProjection() {
        DomainEventEnvelope event = new DomainEventEnvelope(
                UUID.randomUUID().toString(), "ORDER", 999, 1,
                "BTC/USDT", 1, "ORDER_UPDATED", Instant.now(), 1,
                mapper.createObjectNode().put("orderId", 999));
        MarketEventProjector isolated = new MarketEventProjector(
                eventStore, transactions,
                List.of(ignored -> {
                    throw new EventDeliveryException("redis unavailable", null);
                }));

        assertThat(isolated.process("sink-failure", event, "test-topic", 0, 0)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM inbox_events WHERE event_id = '"
                + event.eventId() + "'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM market_event_projection WHERE event_id = '"
                + event.eventId() + "'")).isEqualTo(1);
    }

    @Test
    void invalidKafkaMessageMovesToDeadLetterTopicAfterBoundedRetries() throws Exception {
        kafka.send(properties.topic(), "BTC/USDT", "not-json")
                .get(10, TimeUnit.SECONDS);

        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(properties.topic() + ".DLT"));
            AtomicBoolean found = new AtomicBoolean(false);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> {
                    if ("not-json".equals(record.value())) {
                        found.set(true);
                    }
                });
                return found.get();
            });
        }
    }

    private String placeOneOrder(long userId, String key) {
        fund(userId, BTC, QUANTITY);
        orders.place(new PlaceOrderCommand(
                userId, "BTC/USDT", OrderSide.SELL, OrderType.LIMIT,
                TimeInForce.GTC, PRICE, null, QUANTITY, key));
        return jdbc.queryForObject(
                "SELECT event_id FROM outbox_events ORDER BY created_at DESC LIMIT 1",
                String.class);
    }

    private void fund(long userId, long assetId, long available) {
        jdbc.update("""
                INSERT INTO wallets (user_id, asset_id, available, locked, version)
                VALUES (?, ?, ?, 0, 0)
                """, userId, assetId, available);
    }

    private void awaitProjection(String eventId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM market_event_projection WHERE event_id = '"
                        + eventId + "'")).isEqualTo(1));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
