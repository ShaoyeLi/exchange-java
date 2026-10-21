package com.ethan.exchange.messaging;

public final class EventDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
