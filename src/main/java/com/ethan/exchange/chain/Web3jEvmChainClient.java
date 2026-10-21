package com.ethan.exchange.chain;

import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.Response;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Web3jEvmChainClient implements EvmChainClient {

    static final String TRANSFER_TOPIC = Hash.sha3String("Transfer(address,address,uint256)");

    private final Web3j web3j;

    public Web3jEvmChainClient(Web3j web3j) {
        this.web3j = web3j;
    }

    @Override
    public long chainId() {
        return send(web3j.ethChainId()).getChainId().longValueExact();
    }

    @Override
    public long latestBlockNumber() {
        EthBlockNumber response = send(web3j.ethBlockNumber());
        return response.getBlockNumber().longValueExact();
    }

    @Override
    public ChainBlock block(long number) {
        EthBlock response = send(web3j.ethGetBlockByNumber(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(number)), false));
        EthBlock.Block block = response.getBlock();
        if (block == null || block.getNumber() == null || block.getTimestamp() == null) {
            throw new ChainDataException("RPC returned no block at height " + number);
        }
        return new ChainBlock(block.getNumber().longValueExact(), block.getHash(),
                block.getParentHash(), Instant.ofEpochSecond(block.getTimestamp().longValueExact()));
    }

    @Override
    public List<Erc20Transfer> transfers(
            long fromBlock, long toBlock, List<String> tokenAddresses) {
        if (tokenAddresses.isEmpty()) {
            return List.of();
        }
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(fromBlock)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(toBlock)),
                tokenAddresses).addSingleTopic(TRANSFER_TOPIC);
        EthLog response = send(web3j.ethGetLogs(filter));
        List<Erc20Transfer> transfers = new ArrayList<>();
        for (EthLog.LogResult<?> result : response.getLogs()) {
            Object value = result.get();
            if (!(value instanceof Log log) || log.isRemoved()) {
                continue;
            }
            List<String> topics = log.getTopics();
            if (topics == null || topics.size() != 3 || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0))) {
                throw new ChainDataException("RPC returned a malformed ERC-20 Transfer log");
            }
            transfers.add(new Erc20Transfer(
                    log.getAddress(), log.getTransactionHash(), log.getLogIndex().longValueExact(),
                    log.getBlockNumber().longValueExact(), log.getBlockHash(),
                    topicAddress(topics.get(1)), topicAddress(topics.get(2)),
                    unsignedHex(log.getData())));
        }
        return List.copyOf(transfers);
    }

    private static String topicAddress(String topic) {
        if (topic == null || !topic.matches("(?i)0x[0-9a-f]{64}")) {
            throw new ChainDataException("invalid indexed address topic");
        }
        return "0x" + topic.substring(topic.length() - 40);
    }

    private static BigInteger unsignedHex(String value) {
        if (value == null || !value.matches("(?i)0x[0-9a-f]{1,64}")) {
            throw new ChainDataException("invalid uint256 event data");
        }
        return new BigInteger(value.substring(2), 16);
    }

    private static <T extends Response<?>> T send(
            org.web3j.protocol.core.Request<?, T> request) {
        try {
            T response = request.send();
            if (response.hasError()) {
                throw new ChainDataException("JSON-RPC error " + response.getError().getCode()
                        + ": " + response.getError().getMessage());
            }
            return response;
        } catch (IOException exception) {
            throw new ChainDataException("JSON-RPC request failed", exception);
        }
    }
}
