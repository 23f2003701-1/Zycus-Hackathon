package com.stockpulse.api.dto;

import jakarta.validation.constraints.Min;

/**
 * Simulated sale. Quantity is optional so the console's one-click "simulate sale" button can
 * post an empty body.
 */
public record CreateOrderRequest(@Min(1) Integer quantity) {

    public int quantityOrDefault() {
        return quantity == null ? 1 : quantity;
    }
}
