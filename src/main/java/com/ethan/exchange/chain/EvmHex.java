package com.ethan.exchange.chain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EvmHex {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");
    private static final Pattern HASH = Pattern.compile("0x[0-9a-f]{64}");

    private EvmHex() {
    }

    public static String address(String value) {
        return normalize(value, ADDRESS, "address");
    }

    public static String hash(String value) {
        return normalize(value, HASH, "hash");
    }

    private static String normalize(String value, Pattern pattern, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid EVM " + label);
        }
        return normalized;
    }
}
