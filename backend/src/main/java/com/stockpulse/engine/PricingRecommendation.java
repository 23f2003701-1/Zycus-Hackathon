package com.stockpulse.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An advisor's pricing answer. Deliberately not an entity - advisors recommend, the service
 * layer decides whether to persist, and only an accepted suggestion changes a price.
 *
 * @param source the advisor that produced this, recorded on the suggestion so a fallback is
 *               visible rather than indistinguishable from a successful AI call
 */
public record PricingRecommendation(
        BigDecimal recommendedPrice,
        double confidence,
        String reasoning,
        String source) {

    public PricingRecommendation {
        if (recommendedPrice == null || recommendedPrice.signum() <= 0) {
            throw new IllegalArgumentException("recommended price must be positive");
        }
        recommendedPrice = recommendedPrice.setScale(2, RoundingMode.HALF_UP);
    }

    public static PricingRecommendation hold(BigDecimal currentPrice, String reasoning, String source) {
        return new PricingRecommendation(currentPrice, 0.6, reasoning, source);
    }
}
