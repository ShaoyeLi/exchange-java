package com.ethan.exchange.api;

import com.ethan.exchange.api.auth.LoginRequest;
import com.ethan.exchange.api.auth.LoginResponse;
import com.ethan.exchange.api.order.CancelOrderHttpRequest;
import com.ethan.exchange.api.order.PlaceOrderHttpRequest;
import com.ethan.exchange.api.query.ExchangeQueryService;
import com.ethan.exchange.api.withdrawal.CreateWithdrawalRequest;
import com.ethan.exchange.order.application.OrderCommandResult;
import com.ethan.exchange.withdrawal.WithdrawalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "exchange.demo.enabled=true",
                "exchange.chain.enabled=false",
                "exchange.messaging.enabled=false"
        })
class ExchangeApiIT {

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
    void authenticationAndRoleBoundariesAreEnforced() {
        assertThat(http.getForEntity(
                "/api/account/balances", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders browserHeaders = new HttpHeaders();
        browserHeaders.setContentType(MediaType.APPLICATION_JSON);
        browserHeaders.setOrigin("http://127.0.0.1:5173");
        ResponseEntity<LoginResponse> browserLogin = http.exchange(
                "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(new LoginRequest("trader@example.com", "Trader123!"),
                        browserHeaders),
                LoginResponse.class);
        assertThat(browserLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browserLogin.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://127.0.0.1:5173");

        LoginResponse trader = login("trader@example.com", "Trader123!");
        assertThat(exchange(HttpMethod.GET, "/api/ops/overview", null,
                trader.accessToken(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        LoginResponse admin = login("admin@example.com", "Admin123!");
        assertThat(exchange(HttpMethod.GET, "/api/ops/overview", null,
                admin.accessToken(), null, ExchangeQueryService.OperationsView.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void authenticatedOrderFlowUsesTokenIdentityAndReplaysIdempotently() {
        LoginResponse trader = login("trader@example.com", "Trader123!");
        String key = UUID.randomUUID().toString();
        PlaceOrderHttpRequest request = new PlaceOrderHttpRequest(
                "BTC/USDT", "SELL", "LIMIT", "GTC", 10_000L,
                null, 10_000);

        ResponseEntity<OrderCommandResult> first = exchange(
                HttpMethod.POST, "/api/orders", request, trader.accessToken(), key,
                OrderCommandResult.class);
        ResponseEntity<OrderCommandResult> replay = exchange(
                HttpMethod.POST, "/api/orders", request, trader.accessToken(), key,
                OrderCommandResult.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().orderId()).isEqualTo(first.getBody().orderId());
        assertThat(replay.getBody().replayed()).isTrue();

        ResponseEntity<ExchangeQueryService.OrderView[]> orders = exchange(
                HttpMethod.GET, "/api/account/orders", null, trader.accessToken(), null,
                ExchangeQueryService.OrderView[].class);
        assertThat(Arrays.asList(required(orders.getBody())))
                .extracting(ExchangeQueryService.OrderView::id)
                .contains(first.getBody().orderId());

        ResponseEntity<OrderCommandResult> canceled = exchange(
                HttpMethod.POST, "/api/orders/" + first.getBody().orderId() + "/cancel",
                new CancelOrderHttpRequest("BTC/USDT"), trader.accessToken(),
                UUID.randomUUID().toString(), OrderCommandResult.class);
        assertThat(canceled.getBody()).isNotNull();
        assertThat(canceled.getBody().status().name()).isEqualTo("CANCELED");
    }

    @Test
    void withdrawalReplayDoesNotLockTwiceAndOwnerCanCancel() {
        LoginResponse trader = login("trader@example.com", "Trader123!");
        LoginResponse admin = login("admin@example.com", "Admin123!");
        long before = balance(trader.accessToken(), "BTC").available();
        String key = UUID.randomUUID().toString();
        CreateWithdrawalRequest request = new CreateWithdrawalRequest(
                "BTC", "0x00000000000000000000000000000000000000b1", 100);

        WithdrawalResult first = required(exchange(
                HttpMethod.POST, "/api/withdrawals", request, trader.accessToken(), key,
                WithdrawalResult.class).getBody());
        WithdrawalResult replay = required(exchange(
                HttpMethod.POST, "/api/withdrawals", request, trader.accessToken(), key,
                WithdrawalResult.class).getBody());
        assertThat(replay.withdrawalId()).isEqualTo(first.withdrawalId());
        assertThat(replay.replayed()).isTrue();
        ExchangeQueryService.BalanceView reserved = balance(trader.accessToken(), "BTC");
        assertThat(reserved.available()).isEqualTo(before - 100);
        assertThat(reserved.locked()).isGreaterThanOrEqualTo(100);

        assertThat(exchange(HttpMethod.POST,
                "/api/withdrawals/" + first.withdrawalId() + "/cancel", null,
                admin.accessToken(), UUID.randomUUID().toString(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        WithdrawalResult canceled = required(exchange(HttpMethod.POST,
                "/api/withdrawals/" + first.withdrawalId() + "/cancel", null,
                trader.accessToken(), UUID.randomUUID().toString(), WithdrawalResult.class)
                .getBody());
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(balance(trader.accessToken(), "BTC").available()).isEqualTo(before);
    }

    private LoginResponse login(String email, String password) {
        ResponseEntity<LoginResponse> response = http.postForEntity(
                "/api/auth/login", new LoginRequest(email, password), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return required(response.getBody());
    }

    private ExchangeQueryService.BalanceView balance(String token, String asset) {
        ResponseEntity<ExchangeQueryService.BalanceView[]> response = exchange(
                HttpMethod.GET, "/api/account/balances", null, token, null,
                ExchangeQueryService.BalanceView[].class);
        return Arrays.stream(required(response.getBody()))
                .filter(balance -> asset.equals(balance.asset()))
                .findFirst().orElseThrow();
    }

    private <T> ResponseEntity<T> exchange(
            HttpMethod method,
            String path,
            Object body,
            String token,
            String idempotencyKey,
            Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new AssertionError("expected a response body");
        }
        return value;
    }
}
