package com.ethan.exchange.chain;

import java.math.BigInteger;

public final class AmountScaler {

    private AmountScaler() {
    }

    public static long toInternal(BigInteger rawAmount, int tokenDecimals, int internalScale) {
        if (rawAmount == null || rawAmount.signum() <= 0 || tokenDecimals < 0
                || tokenDecimals > 77 || internalScale < 0 || internalScale > 18) {
            throw new IllegalArgumentException("invalid amount or scale");
        }
        BigInteger converted;
        if (tokenDecimals > internalScale) {
            BigInteger divisor = BigInteger.TEN.pow(tokenDecimals - internalScale);
            BigInteger[] quotient = rawAmount.divideAndRemainder(divisor);
            if (quotient[1].signum() != 0) {
                throw new ArithmeticException("deposit amount cannot be represented exactly");
            }
            converted = quotient[0];
        } else {
            converted = rawAmount.multiply(BigInteger.TEN.pow(internalScale - tokenDecimals));
        }
        if (converted.signum() <= 0 || converted.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new ArithmeticException("deposit amount exceeds internal range");
        }
        return converted.longValueExact();
    }
}
