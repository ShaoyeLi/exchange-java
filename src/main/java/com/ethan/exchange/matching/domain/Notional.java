package com.ethan.exchange.matching.domain;

public record Notional(long unscaled, int scale) implements Comparable<Notional> {

    public Notional {
        if (unscaled < 0) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "notional must not be negative");
        }
        if (scale < 0 || scale > 36) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "notional scale must be between 0 and 36");
        }
    }

    public static Notional of(long unscaled, int scale) {
        return new Notional(unscaled, scale);
    }

    @Override
    public int compareTo(Notional other) {
        if (other == null || scale != other.scale) {
            throw new DomainException(DomainErrorCode.INVALID_NUMERIC_VALUE,
                    "cannot compare notionals with different scales");
        }
        return Long.compare(unscaled, other.unscaled);
    }
}
