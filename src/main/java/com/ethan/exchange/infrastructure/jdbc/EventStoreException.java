package com.ethan.exchange.infrastructure.jdbc;

public final class EventStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventStoreException(String message) {
        super(message);
    }
}
