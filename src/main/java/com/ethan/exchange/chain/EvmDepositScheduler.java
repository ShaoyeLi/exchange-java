package com.ethan.exchange.chain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"exchange.chain.enabled", "exchange.chain.scheduling-enabled"},
        havingValue = "true")
public class EvmDepositScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvmDepositScheduler.class);

    private final EvmDepositScanner scanner;

    public EvmDepositScheduler(EvmDepositScanner scanner) {
        this.scanner = scanner;
    }

    @Scheduled(fixedDelayString = "${exchange.chain.poll-delay:2000}")
    public void run() {
        try {
            scanner.scanOnce();
        } catch (RuntimeException exception) {
            LOGGER.error("EVM deposit scan cycle failed", exception);
        }
    }
}
