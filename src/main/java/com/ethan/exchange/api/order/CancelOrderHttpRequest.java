package com.ethan.exchange.api.order;

import jakarta.validation.constraints.NotBlank;

public record CancelOrderHttpRequest(@NotBlank String market) {
}
