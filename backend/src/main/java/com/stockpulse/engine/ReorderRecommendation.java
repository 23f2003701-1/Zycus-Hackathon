package com.stockpulse.engine;

/**
 * An advisor's replenishment answer.
 *
 * @param source the advisor that produced this, so a rule-based fallback stays visible
 */
public record ReorderRecommendation(
        int recommendedQuantity,
        int suggestedLeadTimeDays,
        double confidence,
        String reasoning,
        String source) {

    public ReorderRecommendation {
        if (recommendedQuantity <= 0) {
            throw new IllegalArgumentException("recommended quantity must be a positive integer");
        }
        if (suggestedLeadTimeDays <= 0) {
            throw new IllegalArgumentException("suggested lead time must be positive");
        }
    }
}
