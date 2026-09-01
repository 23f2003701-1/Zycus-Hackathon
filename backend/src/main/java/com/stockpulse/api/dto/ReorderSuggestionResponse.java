package com.stockpulse.api.dto;

import java.time.Instant;

import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;

public record ReorderSuggestionResponse(
        Long id,
        String productId,
        String productSku,
        String productName,
        int currentStock,
        int recommendedQuantity,
        int suggestedLeadTimeDays,
        double confidence,
        String reasoning,
        SuggestionStatus status,
        TriggerReason triggerReason,
        boolean autoTriggered,
        String generatedBy,
        Instant createdAt,
        Instant decidedAt) {

    public static ReorderSuggestionResponse from(ReorderSuggestion suggestion) {
        var product = suggestion.getProduct();
        return new ReorderSuggestionResponse(
                suggestion.getId(),
                product.getId(),
                product.getSku(),
                product.getName(),
                suggestion.getCurrentStock(),
                suggestion.getRecommendedQuantity(),
                suggestion.getSuggestedLeadTimeDays(),
                suggestion.getConfidence(),
                suggestion.getReasoning(),
                suggestion.getStatus(),
                suggestion.getTriggerReason(),
                suggestion.getTriggerReason().isAutoTriggered(),
                suggestion.getGeneratedBy(),
                suggestion.getCreatedAt(),
                suggestion.getDecidedAt());
    }
}
