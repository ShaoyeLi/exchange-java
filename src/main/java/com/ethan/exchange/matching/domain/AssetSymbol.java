package com.ethan.exchange.matching.domain;

import java.util.Locale;

public record AssetSymbol(String value) {

    public AssetSymbol {
        if (value == null || value.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "asset symbol must not be blank");
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9]{2,12}")) {
            throw new DomainException(DomainErrorCode.INVALID_MARKET,
                    "asset symbol must contain 2-12 uppercase letters or digits");
        }
    }

    public static AssetSymbol of(String value) {
        return new AssetSymbol(value);
    }
}
