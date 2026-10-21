package com.ethan.exchange.withdrawal;

public record WithdrawalResult(long withdrawalId, String status, boolean replayed) {
}
