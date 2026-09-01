package com.stockpulse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "reorder_suggestions", indexes = {
        @Index(name = "idx_reorder_dedupe", columnList = "product_id, trigger_reason, status")
})
public class ReorderSuggestion extends Suggestion {

    @Column(name = "current_stock", nullable = false)
    private int currentStock;

    @Column(name = "recommended_quantity", nullable = false)
    private int recommendedQuantity;

    @Column(name = "suggested_lead_time_days", nullable = false)
    private int suggestedLeadTimeDays;

    protected ReorderSuggestion() {
        // for JPA
    }

    public ReorderSuggestion(Product product, int recommendedQuantity, int suggestedLeadTimeDays,
                             double confidence, String reasoning, TriggerReason triggerReason,
                             String generatedBy) {
        super(product, confidence, reasoning, triggerReason, generatedBy);
        if (recommendedQuantity <= 0) {
            throw new IllegalArgumentException("recommended reorder quantity must be positive");
        }
        this.currentStock = product.getStockLevel();
        this.recommendedQuantity = recommendedQuantity;
        this.suggestedLeadTimeDays = suggestedLeadTimeDays;
    }

    @Override
    protected void applyToProduct() {
        getProduct().receiveShipment(recommendedQuantity);
    }

    public int getCurrentStock() {
        return currentStock;
    }

    public int getRecommendedQuantity() {
        return recommendedQuantity;
    }

    public int getSuggestedLeadTimeDays() {
        return suggestedLeadTimeDays;
    }
}
