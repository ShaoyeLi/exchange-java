package com.ethan.exchange.matching.domain;

public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DomainErrorCode code;

    public DomainException(DomainErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public DomainException(DomainErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DomainErrorCode code() {
        return code;
    }
}
