package com.ethan.exchange.chain;

public class ChainDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChainDataException(String message) {
        super(message);
    }

    public ChainDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
