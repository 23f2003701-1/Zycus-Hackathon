package com.stockpulse.api.dto;

import java.util.Set;

public record CommerceStrategyResponse(String activeStrategy, Set<String> availableStrategies) {
}
