package com.stockpulse.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CommerceStrategyRequest(@NotBlank String activeStrategy) {
}
