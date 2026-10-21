package com.ethan.exchange.matching.domain;

public record Price(long unscaled, int scale) implements Comparable<Price> {

    public Price {
        if (unscaled <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "price must be positive");
        }
        if (scale < 0 || scale > 18) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "price scale must be between 0 and 18");
        }
    }

    public static Price of(long unscaled, int scale) {
        return new Price(unscaled, scale);
    }

    @Override
    public int compareTo(Price other) {
        requireSameScale(other);
        return Long.compare(unscaled, other.unscaled);
    }

    public Notional multiply(Quantity quantity) {
        int resultScale;
        long result;
        try {
            resultScale = Math.addExact(scale, quantity.scale());
            result = Math.multiplyExact(unscaled, quantity.unscaled());
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                    "price multiplied by quantity exceeds fixed-point range", exception);
        }
        return new Notional(result, resultScale);
    }

    private void requireSameScale(Price other) {
        if (other == null || scale != other.scale) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "cannot compare prices with different scales");
        }
    }
}
