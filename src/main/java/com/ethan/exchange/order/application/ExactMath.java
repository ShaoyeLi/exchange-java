package com.ethan.exchange.order.application;

import java.math.BigInteger;

public final class ExactMath {

    private static final BigInteger BPS_DENOMINATOR = BigInteger.valueOf(10_000);

    private ExactMath() {
    }

    public static long quote(long price, long quantity) {
        return fit(BigInteger.valueOf(price).multiply(BigInteger.valueOf(quantity)),
                "trade quote exceeds BIGINT");
    }

    public static long cumulativeFee(
            long makerQuote,
            int makerBps,
            long takerQuote,
            int takerBps
    ) {
        requireBps(makerBps);
        requireBps(takerBps);
        BigInteger weighted = BigInteger.valueOf(makerQuote)
                .multiply(BigInteger.valueOf(makerBps))
                .add(BigInteger.valueOf(takerQuote).multiply(BigInteger.valueOf(takerBps)));
        if (weighted.signum() == 0) {
            return 0;
        }
        return fit(weighted.add(BPS_DENOMINATOR.subtract(BigInteger.ONE))
                        .divide(BPS_DENOMINATOR),
                "cumulative fee exceeds BIGINT");
    }

    public static long feeUpperBound(long quote, int maximumBps) {
        return cumulativeFee(quote, maximumBps, 0, 0);
    }

    public static long remainingFeeBuffer(
            long cumulativeMakerQuote,
            int makerBps,
            long cumulativeTakerQuote,
            int takerBps,
            long remainingMaximumQuote,
            int maximumBps,
            long alreadyCharged
    ) {
        requireBps(makerBps);
        requireBps(takerBps);
        requireBps(maximumBps);
        BigInteger worstFinalWeighted = BigInteger.valueOf(cumulativeMakerQuote)
                .multiply(BigInteger.valueOf(makerBps))
                .add(BigInteger.valueOf(cumulativeTakerQuote)
                        .multiply(BigInteger.valueOf(takerBps)))
                .add(BigInteger.valueOf(remainingMaximumQuote)
                        .multiply(BigInteger.valueOf(maximumBps)));
        long worstFinalFee = worstFinalWeighted.signum() == 0
                ? 0
                : fit(worstFinalWeighted.add(BPS_DENOMINATOR.subtract(BigInteger.ONE))
                        .divide(BPS_DENOMINATOR), "remaining fee buffer exceeds BIGINT");
        long buffer = subtract(worstFinalFee, alreadyCharged);
        if (buffer < 0) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "already charged fee exceeds final weighted upper bound");
        }
        return buffer;
    }

    public static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "fixed-point addition exceeds BIGINT", exception);
        }
    }

    public static long subtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "fixed-point subtraction exceeds BIGINT", exception);
        }
    }

    public static long negate(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "fixed-point negation exceeds BIGINT", exception);
        }
    }

    private static long fit(BigInteger value, String message) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW, message, exception);
        }
    }

    private static void requireBps(int bps) {
        if (bps < 0 || bps > 10_000) {
            throw new ExchangeOperationException(ExchangeErrorCode.NUMERIC_OVERFLOW,
                    "fee bps must be between 0 and 10000");
        }
    }
}
