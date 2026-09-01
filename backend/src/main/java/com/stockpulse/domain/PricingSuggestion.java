package com.stockpulse.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "pricing_suggestions", indexes = {
        @Index(name = "idx_pricing_dedupe", columnList = "product_id, trigger_reason, status")
})
public class PricingSuggestion extends Suggestion {

    /** Price at the moment of the recommendation, so the UI can show the delta after the fact. */
    @Column(name = "current_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "recommended_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal recommendedPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PriceDirection direction;

    protected PricingSuggestion() {
        // for JPA
    }

    public PricingSuggestion(Product product, BigDecimal recommendedPrice, double confidence,
                             String reasoning, TriggerReason triggerReason, String generatedBy) {
        super(product, confidence, reasoning, triggerReason, generatedBy);
        this.currentPrice = product.getCurrentPrice();
        this.recommendedPrice = recommendedPrice.setScale(2, RoundingMode.HALF_UP);
        this.direction = PriceDirection.between(this.currentPrice, this.recommendedPrice);
    }

    @Override
    protected void applyToProduct() {
        getProduct().applyApprovedPrice(recommendedPrice);
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public BigDecimal getRecommendedPrice() {
        return recommendedPrice;
    }

    public PriceDirection getDirection() {
        return direction;
    }
}
