package com.ethan.exchange.messaging;

public final class UnsupportedEventSchemaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedEventSchemaException(int schemaVersion) {
        super("unsupported event schema version: " + schemaVersion);
    }
}
