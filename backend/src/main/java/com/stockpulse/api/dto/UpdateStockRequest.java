package com.stockpulse.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Absolute stock correction. Fires the agentic loop when it lands below the reorder threshold. */
public record UpdateStockRequest(@NotNull @Min(0) Integer stockLevel) {
}
