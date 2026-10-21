package com.ethan.exchange.chain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = "exchange.chain.enabled=false")
class EvmDepositIndexingIT {

    private static final long CHAIN_ID = 31_337;
    private static final String TOKEN = "0x0000000000000000000000000000000000001000";
    private static final String TARGET = "0x00000000000000000000000000000000000000a1";
    private static final String SENDER = "0x00000000000000000000000000000000000000f1";
    private static final long USER_ID = 101;
    private static final long ASSET_ID = 1;

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
    private ChainStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private ChainProperties properties;
    private FakeChainClient chain;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM chain_deposits");
        jdbc.update("DELETE FROM chain_blocks");
        jdbc.update("DELETE FROM deposit_addresses");
        jdbc.update("DELETE FROM chain_token_contracts");
        jdbc.update("DELETE FROM chain_sync_state");
        jdbc.update("DELETE FROM chain_networks");
        jdbc.update("DELETE FROM ledger_entries WHERE posting_type = 'DEPOSIT_CREDIT'");
        jdbc.update("DELETE FROM ledger_journals WHERE journal_type = 'DEPOSIT'");
        jdbc.update("DELETE FROM wallets WHERE user_id = ?", USER_ID);
        properties = new ChainProperties(
                false, CHAIN_ID, "test-evm", "http://unused", 0,
                2, 2, 10, Duration.ofSeconds(20));
        store.ensureNetwork(properties);
        configureToken(6);
        jdbc.update("""
                INSERT INTO deposit_addresses (chain_id, address, user_id)
                VALUES (?, ?, ?)
                """, CHAIN_ID, TARGET, USER_ID);
        chain = new FakeChainClient();
    }

    @Test
    void scansBoundedRangesThenCreditsExactlyOnceAfterConfirmation() {
        chain.canonical(0, 3, 100);
        chain.transfer(1, 10, BigInteger.valueOf(1_500_000), TARGET);
        chain.head = 1;

        ChainScanResult first = scanner().scanOnce();
        assertThat(first.scannedBlocks()).isEqualTo(2);
        assertThat(first.observedDeposits()).isEqualTo(1);
        assertThat(first.creditedDeposits()).isZero();
        assertThat(value("SELECT next_block FROM chain_sync_state WHERE chain_id = " + CHAIN_ID))
                .isEqualTo(2);
        assertThat(text("SELECT deposit_status FROM chain_deposits"))
                .isEqualTo("SEEN");

        chain.head = 3;
        ChainScanResult second = scanner().scanOnce();
        assertThat(second.scannedBlocks()).isEqualTo(2);
        assertThat(second.creditedDeposits()).isEqualTo(1);
        assertThat(walletAvailable()).isEqualTo(1_500_000);
        assertThat(value("SELECT COUNT(*) FROM ledger_entries "
                + "WHERE posting_type = 'DEPOSIT_CREDIT'")).isEqualTo(1);

        assertThat(scanner().scanOnce().creditedDeposits()).isZero();
        assertThat(walletAvailable()).isEqualTo(1_500_000);
        assertThat(value("SELECT COUNT(*) FROM chain_deposits")).isEqualTo(1);
    }

    @Test
    void shallowReorgOrphansUncreditedDepositAndRewindsForRescan() {
        chain.canonical(0, 1, 100);
        chain.transfer(1, 20, BigInteger.valueOf(50), TARGET);
        chain.head = 1;
        scanner().scanOnce();

        chain.replaceFrom(1, 1, 200);
        chain.transfers.clear();
        chain.transfer(1, 21, BigInteger.valueOf(70), TARGET);
        ChainScanResult reorg = scanner().scanOnce();
        assertThat(reorg.rewound()).isTrue();
        assertThat(text("SELECT deposit_status FROM chain_deposits WHERE transaction_hash = ?",
                hash(20))).isEqualTo("ORPHANED");
        assertThat(value("SELECT next_block FROM chain_sync_state WHERE chain_id = " + CHAIN_ID))
                .isEqualTo(1);

        scanner().scanOnce();
        assertThat(value("SELECT COUNT(*) FROM chain_deposits")).isEqualTo(2);
        assertThat(text("SELECT deposit_status FROM chain_deposits WHERE transaction_hash = ?",
                hash(21))).isEqualTo("SEEN");
    }

    @Test
    void reorgTouchingCreditedDepositBlocksAutomaticProcessing() {
        chain.canonical(0, 3, 100);
        chain.transfer(1, 30, BigInteger.valueOf(90), TARGET);
        chain.head = 3;
        assertThat(scanner().scanOnce().creditedDeposits()).isEqualTo(1);
        scanner().scanOnce();
        assertThat(walletAvailable()).isEqualTo(90);

        chain.replaceFrom(1, 3, 300);
        ChainScanResult result = scanner().scanOnce();
        assertThat(result.blocked()).isTrue();
        assertThat(text("SELECT sync_status FROM chain_sync_state WHERE chain_id = " + CHAIN_ID))
                .isEqualTo("BLOCKED");
        assertThat(text("SELECT deposit_status FROM chain_deposits"))
                .isEqualTo("CREDITED");
        assertThat(walletAvailable()).isEqualTo(90);
    }

    @Test
    void unrepresentableAndZeroTransfersNeverMoveMoney() {
        jdbc.update("DELETE FROM chain_token_contracts WHERE chain_id = ?", CHAIN_ID);
        configureToken(8);
        chain.canonical(0, 1, 100);
        chain.transfer(1, 40, BigInteger.valueOf(123), TARGET);
        chain.transfer(1, 41, BigInteger.ZERO, TARGET);
        chain.head = 1;

        scanner().scanOnce();
        assertThat(jdbc.queryForList(
                "SELECT deposit_status FROM chain_deposits ORDER BY transaction_hash",
                String.class)).containsExactly("REVIEW", "IGNORED");
        assertThat(value("SELECT COUNT(*) FROM wallets WHERE user_id = " + USER_ID)).isZero();
    }

    @Test
    void duplicateRpcLogHasOneFinancialEffect() {
        chain.canonical(0, 3, 100);
        Erc20Transfer transfer = chain.transfer(
                1, 50, BigInteger.valueOf(200), TARGET);
        chain.transfers.add(transfer);
        chain.head = 3;

        assertThat(scanner().scanOnce().creditedDeposits()).isEqualTo(1);
        assertThat(value("SELECT COUNT(*) FROM chain_deposits")).isEqualTo(1);
        assertThat(walletAvailable()).isEqualTo(200);
    }

    @Test
    void rpcFailureOrMismatchedLogCannotAdvanceTheCursor() {
        chain.canonical(0, 1, 100);
        chain.failBlock = 1L;
        chain.head = 1;
        assertThatThrownBy(() -> scanner().scanOnce())
                .isInstanceOf(ChainDataException.class);
        assertThat(value("SELECT next_block FROM chain_sync_state WHERE chain_id = " + CHAIN_ID))
                .isZero();
        assertThat(value("SELECT COUNT(*) FROM chain_blocks")).isZero();

        chain.failBlock = null;
        chain.transfers.add(new Erc20Transfer(
                TOKEN, hash(60), 0, 1, hash(999), SENDER, TARGET, BigInteger.ONE));
        assertThatThrownBy(() -> scanner().scanOnce())
                .isInstanceOf(ChainDataException.class)
                .hasMessageContaining("canonical range");
        assertThat(value("SELECT next_block FROM chain_sync_state WHERE chain_id = " + CHAIN_ID))
                .isZero();
    }

    @Test
    void wrongRpcNetworkCannotAdvanceTheCursor() {
        chain.canonical(0, 0, 100);
        chain.head = 0;
        chain.actualChainId = 1;

        assertThatThrownBy(() -> scanner().scanOnce())
                .isInstanceOf(ChainDataException.class)
                .hasMessageContaining("does not match");
        assertThat(value("SELECT next_block FROM chain_sync_state WHERE chain_id = " + CHAIN_ID))
                .isZero();
        assertThat(value("SELECT COUNT(*) FROM chain_blocks")).isZero();
    }

    private EvmDepositScanner scanner() {
        return new EvmDepositScanner(properties, chain, store);
    }

    private void configureToken(int tokenDecimals) {
        jdbc.update("""
                INSERT INTO chain_token_contracts (
                    chain_id, contract_address, asset_id, token_decimals)
                VALUES (?, ?, ?, ?)
                """, CHAIN_ID, TOKEN, ASSET_ID, tokenDecimals);
    }

    private long walletAvailable() {
        return value("SELECT available FROM wallets WHERE user_id = " + USER_ID
                + " AND asset_id = " + ASSET_ID);
    }

    private long value(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private static String hash(long value) {
        return "0x" + String.format("%064x", value);
    }

    private static final class FakeChainClient implements EvmChainClient {

        private final Map<Long, ChainBlock> blocks = new HashMap<>();
        private final List<Erc20Transfer> transfers = new ArrayList<>();
        private long actualChainId = CHAIN_ID;
        private long head;
        private Long failBlock;

        void canonical(long from, long to, long branch) {
            for (long number = from; number <= to; number++) {
                String parent = number == 0 ? hash(0) : blocks.get(number - 1).hash();
                blocks.put(number, new ChainBlock(
                        number, hash(branch + number), parent,
                        Instant.ofEpochSecond(1_000 + number)));
            }
        }

        void replaceFrom(long from, long to, long branch) {
            for (long number = from; number <= to; number++) {
                String parent = blocks.get(number - 1).hash();
                blocks.put(number, new ChainBlock(
                        number, hash(branch + number), parent,
                        Instant.ofEpochSecond(2_000 + number)));
            }
        }

        Erc20Transfer transfer(long block, long transaction, BigInteger amount, String to) {
            Erc20Transfer transfer = new Erc20Transfer(
                    TOKEN, hash(transaction), transfers.size(), block, blocks.get(block).hash(),
                    SENDER, to, amount);
            transfers.add(transfer);
            return transfer;
        }

        @Override
        public long chainId() {
            return actualChainId;
        }

        @Override
        public long latestBlockNumber() {
            return head;
        }

        @Override
        public ChainBlock block(long number) {
            if (Long.valueOf(number).equals(failBlock)) {
                throw new ChainDataException("simulated RPC failure");
            }
            ChainBlock block = blocks.get(number);
            if (block == null) {
                throw new ChainDataException("missing fake block " + number);
            }
            return block;
        }

        @Override
        public List<Erc20Transfer> transfers(
                long fromBlock, long toBlock, List<String> tokenAddresses) {
            return transfers.stream()
                    .filter(transfer -> transfer.blockNumber() >= fromBlock
                            && transfer.blockNumber() <= toBlock
                            && tokenAddresses.contains(transfer.tokenAddress()))
                    .toList();
        }
    }
}
