package com.ethan.exchange.matching.domain;

import java.util.Locale;
public record MarketSymbol(String value) {

    public MarketSymbol {
        if (value == null || value.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market symbol must not be blank");
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9]{2,12}/[A-Z0-9]{2,12}")) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "market symbol must use BASE/QUOTE format");
        }
    }

    public static MarketSymbol of(String value) {
        return new MarketSymbol(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
