package com.ethan.exchange.withdrawal;

public final class WithdrawalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final WithdrawalErrorCode code;

    public WithdrawalException(WithdrawalErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public WithdrawalErrorCode code() {
        return code;
    }
}
