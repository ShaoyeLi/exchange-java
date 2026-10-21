package com.ethan.exchange.order.application;

public final class ExchangeOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ExchangeErrorCode code;

    public ExchangeOperationException(ExchangeErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ExchangeOperationException(ExchangeErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ExchangeErrorCode code() {
        return code;
    }
}
