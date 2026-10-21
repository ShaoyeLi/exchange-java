package com.ethan.exchange.api.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record PlaceOrderHttpRequest(
        @NotBlank String market,
        @NotBlank String side,
        @NotBlank String type,
        @NotBlank String timeInForce,
        Long price,
        Long protectionPrice,
        @Positive long quantity
) {
}
