package com.ethan.exchange.observability;

import com.ethan.exchange.chain.ChainScanResult;
import com.ethan.exchange.order.application.OrderCommandResult;
import com.ethan.exchange.withdrawal.WithdrawalResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ExchangeOperationObservationAspect {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ExchangeOperationObservationAspect.class);

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public ExchangeOperationObservationAspect(
            ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    @Around("execution(public * com.ethan.exchange.order.application.ExchangeOrderService.place(..))")
    public Object observeOrderPlace(ProceedingJoinPoint invocation) throws Throwable {
        return observe(invocation, "order.place");
    }

    @Around("execution(public * com.ethan.exchange.order.application.ExchangeOrderService.cancel(..))")
    public Object observeOrderCancel(ProceedingJoinPoint invocation) throws Throwable {
        return observe(invocation, "order.cancel");
    }

    @Around("execution(public * com.ethan.exchange.withdrawal.WithdrawalService.request(..))")
    public Object observeWithdrawalRequest(ProceedingJoinPoint invocation) throws Throwable {
        return observe(invocation, "withdrawal.request");
    }

    @Around("execution(public * com.ethan.exchange.withdrawal.WithdrawalService.cancel(..))")
    public Object observeWithdrawalCancel(ProceedingJoinPoint invocation) throws Throwable {
        return observe(invocation, "withdrawal.cancel");
    }

    @Around("execution(public * com.ethan.exchange.messaging.OutboxRelayService.relayOnce(..))")
    public Object observeOutboxRelay(ProceedingJoinPoint invocation) throws Throwable {
        return observe(invocation, "outbox.relay");
    }

    @Around("execution(public * com.ethan.exchange.chain.EvmDepositScanner.scanOnce(..))")
    public Object observeDepositScan(ProceedingJoinPoint invocation) throws Throwable {
        return observe(invocation, "deposit.scan");
    }

    private Object observe(ProceedingJoinPoint invocation, String operation) throws Throwable {
        Observation observation = Observation.createNotStarted("exchange.operation", observations)
                .contextualName(operation)
                .lowCardinalityKeyValue("operation", operation)
                .start();
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        Throwable failure = null;
        Observation.Scope scope = observation.openScope();
        try {
            Object result = invocation.proceed();
            if (isReplay(result)) {
                outcome = "replay";
            }
            recordResultMetrics(operation, result);
            return result;
        } catch (Throwable exception) {
            outcome = "failure";
            failure = exception;
            observation.error(exception);
            throw exception;
        } finally {
            scope.close();
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.stop();
            Counter.builder("exchange.operation.total")
                    .description("Completed exchange business operations")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meters)
                    .increment();
            sample.stop(Timer.builder("exchange.operation.duration")
                    .description("Exchange business operation duration")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meters));
            if (failure == null) {
                if (isScheduledOperation(operation)) {
                    LOGGER.atDebug()
                            .addKeyValue("operation", operation)
                            .addKeyValue("outcome", outcome)
                            .log("exchange scheduled operation completed");
                } else {
                    LOGGER.atInfo()
                            .addKeyValue("operation", operation)
                            .addKeyValue("outcome", outcome)
                            .log("exchange operation completed");
                }
            } else {
                LOGGER.atWarn()
                        .addKeyValue("operation", operation)
                        .addKeyValue("outcome", outcome)
                        .addKeyValue("errorType", failure.getClass().getSimpleName())
                        .log("exchange operation failed");
            }
        }
    }

    private static boolean isReplay(Object result) {
        return result instanceof OrderCommandResult order && order.replayed()
                || result instanceof WithdrawalResult withdrawal && withdrawal.replayed();
    }

    private void recordResultMetrics(String operation, Object result) {
        if ("outbox.relay".equals(operation) && result instanceof Integer published
                && published > 0) {
            Counter.builder("exchange.outbox.published")
                    .description("Outbox events acknowledged by Kafka")
                    .register(meters)
                    .increment(published);
        }
        if ("deposit.scan".equals(operation) && result instanceof ChainScanResult scan) {
            increment("exchange.deposit.blocks.scanned", "EVM blocks scanned",
                    scan.scannedBlocks());
            increment("exchange.deposit.observed", "EVM deposits observed",
                    scan.observedDeposits());
            increment("exchange.deposit.credited", "Confirmed deposits credited",
                    scan.creditedDeposits());
            if (scan.rewound()) {
                increment("exchange.deposit.reorg", "Detected shallow reorganizations", 1);
            }
        }
    }

    private void increment(String name, String description, int amount) {
        if (amount > 0) {
            Counter.builder(name).description(description).register(meters).increment(amount);
        }
    }

    private static boolean isScheduledOperation(String operation) {
        return "outbox.relay".equals(operation) || "deposit.scan".equals(operation);
    }
}
