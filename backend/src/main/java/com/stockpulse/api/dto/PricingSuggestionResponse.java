package com.stockpulse.api.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;

/**
 * @param autoTriggered drives the console badge that distinguishes a recommendation the system
 *                      raised on its own from one a merchandiser asked for
 */
public record PricingSuggestionResponse(
        Long id,
        String productId,
        String productSku,
        String productName,
        BigDecimal currentPrice,
        BigDecimal recommendedPrice,
        BigDecimal changePct,
        PriceDirection direction,
        double confidence,
        String reasoning,
        SuggestionStatus status,
        TriggerReason triggerReason,
        boolean autoTriggered,
        String generatedBy,
        Instant createdAt,
        Instant decidedAt) {

    public static PricingSuggestionResponse from(PricingSuggestion suggestion) {
        var product = suggestion.getProduct();
        return new PricingSuggestionResponse(
                suggestion.getId(),
                product.getId(),
                product.getSku(),
                product.getName(),
                suggestion.getCurrentPrice(),
                suggestion.getRecommendedPrice(),
                changePct(suggestion),
                suggestion.getDirection(),
                suggestion.getConfidence(),
                suggestion.getReasoning(),
                suggestion.getStatus(),
                suggestion.getTriggerReason(),
                suggestion.getTriggerReason().isAutoTriggered(),
                suggestion.getGeneratedBy(),
                suggestion.getCreatedAt(),
                suggestion.getDecidedAt());
    }

    private static BigDecimal changePct(PricingSuggestion suggestion) {
        BigDecimal current = suggestion.getCurrentPrice();
        if (current.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return suggestion.getRecommendedPrice()
                .subtract(current)
                .multiply(new BigDecimal("100"))
                .divide(current, 1, RoundingMode.HALF_UP);
    }
}
