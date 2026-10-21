package com.ethan.exchange.matching.domain;

public record Quantity(long unscaled, int scale) implements Comparable<Quantity> {

    public Quantity {
        if (unscaled < 0) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "quantity must not be negative");
        }
        if (scale < 0 || scale > 18) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "quantity scale must be between 0 and 18");
        }
    }

    public static Quantity of(long unscaled, int scale) {
        if (unscaled == 0) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "order quantity must be positive");
        }
        return new Quantity(unscaled, scale);
    }

    public static Quantity zero(int scale) {
        return new Quantity(0, scale);
    }

    public Quantity plus(Quantity other) {
        requireSameScale(other);
        try {
            return new Quantity(Math.addExact(unscaled, other.unscaled), scale);
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                    "quantity addition exceeds fixed-point range", exception);
        }
    }

    public Quantity minus(Quantity other) {
        requireSameScale(other);
        try {
            return new Quantity(Math.subtractExact(unscaled, other.unscaled), scale);
        } catch (ArithmeticException exception) {
            throw new DomainException(DomainErrorCode.NUMERIC_OVERFLOW,
                    "quantity subtraction exceeds fixed-point range", exception);
        }
    }

    public static Quantity min(Quantity left, Quantity right) {
        left.requireSameScale(right);
        return left.unscaled <= right.unscaled ? left : right;
    }

    public boolean isZero() {
        return unscaled == 0;
    }

    @Override
    public int compareTo(Quantity other) {
        requireSameScale(other);
        return Long.compare(unscaled, other.unscaled);
    }

    private void requireSameScale(Quantity other) {
        if (other == null || scale != other.scale) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "cannot combine quantities with different scales");
        }
    }
}
