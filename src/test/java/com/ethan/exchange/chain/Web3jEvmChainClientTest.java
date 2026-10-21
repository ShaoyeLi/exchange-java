package com.ethan.exchange.chain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class Web3jEvmChainClientTest {

    private static final String TOKEN = "0x0000000000000000000000000000000000001000";
    private static final String FROM = "0x00000000000000000000000000000000000000f1";
    private static final String TO = "0x00000000000000000000000000000000000000a1";

    @Test
    void usesJsonRpcAndDecodesAnErc20Transfer() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> respond(exchange, requests));
        server.start();
        Web3j web3j = Web3j.build(new HttpService(
                "http://localhost:" + server.getAddress().getPort()));
        try {
            Web3jEvmChainClient client = new Web3jEvmChainClient(web3j);

            assertThat(client.chainId()).isEqualTo(31_337);
            assertThat(client.latestBlockNumber()).isEqualTo(2);
            assertThat(client.block(2)).isEqualTo(new ChainBlock(
                    2, hash(2), hash(1), java.time.Instant.ofEpochSecond(100)));
            assertThat(client.transfers(2, 2, List.of(TOKEN))).singleElement()
                    .satisfies(transfer -> {
                        assertThat(transfer.tokenAddress()).isEqualTo(TOKEN);
                        assertThat(transfer.fromAddress()).isEqualTo(FROM);
                        assertThat(transfer.toAddress()).isEqualTo(TO);
                        assertThat(transfer.rawAmount()).isEqualTo(java.math.BigInteger.valueOf(42));
                        assertThat(transfer.logIndex()).isEqualTo(3);
                    });
            assertThat(requests).anySatisfy(body -> assertThat(body).contains(
                    "eth_getLogs", "0x2", TOKEN, Web3jEvmChainClient.TRANSFER_TOPIC));
        } finally {
            web3j.shutdown();
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, List<String> requests) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        requests.add(body);
        String result;
        if (body.contains("eth_chainId")) {
            result = "\"0x7a69\"";
        } else if (body.contains("eth_blockNumber")) {
            result = "\"0x2\"";
        } else if (body.contains("eth_getBlockByNumber")) {
            result = "{\"number\":\"0x2\",\"hash\":\"" + hash(2)
                    + "\",\"parentHash\":\"" + hash(1) + "\",\"timestamp\":\"0x64\"}";
        } else {
            result = "[{\"removed\":false,\"logIndex\":\"0x3\","
                    + "\"transactionIndex\":\"0x0\",\"transactionHash\":\"" + hash(90)
                    + "\",\"blockHash\":\"" + hash(2) + "\",\"blockNumber\":\"0x2\","
                    + "\"address\":\"" + TOKEN + "\",\"data\":\"0x2a\",\"topics\":[\""
                    + Web3jEvmChainClient.TRANSFER_TOPIC + "\",\"" + topic(FROM) + "\",\""
                    + topic(TO) + "\"]}]";
        }
        byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                + result + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static String topic(String address) {
        return "0x000000000000000000000000" + address.substring(2);
    }

    private static String hash(long value) {
        return "0x" + String.format("%064x", value);
    }
}
