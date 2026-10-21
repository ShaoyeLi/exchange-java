package com.ethan.exchange.observability;

import com.ethan.exchange.api.auth.LoginRequest;
import com.ethan.exchange.api.auth.LoginResponse;
import com.ethan.exchange.api.order.PlaceOrderHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@AutoConfigureObservability
@ActiveProfiles("observability")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "exchange.demo.enabled=true",
                "exchange.chain.enabled=false",
                "exchange.messaging.enabled=false"
        })
class ObservabilityIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("exchange_java")
            .withUsername("exchange")
            .withPassword("exchange")
            .withStartupTimeout(Duration.ofSeconds(120));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void probesCorrelationMetricsAndActuatorAuthorizationAreOperational() {
        assertThat(http.getForEntity("/livez", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/readyz", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders correlation = new HttpHeaders();
        correlation.set(RequestCorrelationFilter.HEADER, "request-correlation-test");
        ResponseEntity<String> publicResponse = http.exchange(
                "/api/public/markets/config?market=BTC/USDT",
                HttpMethod.GET, new HttpEntity<>(correlation), String.class);
        assertThat(publicResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicResponse.getHeaders().getFirst(RequestCorrelationFilter.HEADER))
                .isEqualTo("request-correlation-test");

        LoginResponse trader = login("trader@example.com", "Trader123!");
        HttpHeaders commandHeaders = authenticated(trader.accessToken());
        commandHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        PlaceOrderHttpRequest order = new PlaceOrderHttpRequest(
                "BTC/USDT", "SELL", "LIMIT", "GTC", 99_999L,
                null, 10_000L);
        assertThat(http.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(order, commandHeaders), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        LoginResponse admin = login("admin@example.com", "Admin123!");
        ResponseEntity<String> prometheus = http.exchange(
                "/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(authenticated(admin.accessToken())), String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("exchange_operation_total")
                .contains("operation=\"order.place\"")
                .contains("exchange_outbox_pending");
    }

    private LoginResponse login(String email, String password) {
        ResponseEntity<LoginResponse> response = http.postForEntity(
                "/api/auth/login", new LoginRequest(email, password), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static HttpHeaders authenticated(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
