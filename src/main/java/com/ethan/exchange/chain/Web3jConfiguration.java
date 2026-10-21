package com.ethan.exchange.chain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
@ConditionalOnProperty(name = "exchange.chain.enabled", havingValue = "true")
public class Web3jConfiguration {

    @Bean(destroyMethod = "shutdown")
    Web3j web3j(ChainProperties properties) {
        return Web3j.build(new HttpService(properties.rpcUrl()));
    }

    @Bean
    EvmChainClient evmChainClient(Web3j web3j) {
        return new Web3jEvmChainClient(web3j);
    }
}
