package com.ethan.exchange.api.withdrawal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateWithdrawalRequest(
        @NotBlank String asset,
        @NotBlank String destinationAddress,
        @Positive long amount
) {
}
