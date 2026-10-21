package com.ethan.exchange.messaging;

public final class InvalidEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
