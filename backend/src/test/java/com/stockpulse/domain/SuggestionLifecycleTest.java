package com.stockpulse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SuggestionLifecycleTest {

    private static Product product() {
        return new Product("PRD-T", "SKU-T", "Test Widget", Category.HOME,
                new BigDecimal("20.00"), 10, 15);
    }

    @Test
    void directionIsDerivedFromTheRecommendation() {
        Product p = product();

        var increase = new PricingSuggestion(p, new BigDecimal("22.00"), 0.8, "why",
                TriggerReason.INVENTORY_LOW, "ruleBased");
        var decrease = new PricingSuggestion(p, new BigDecimal("18.00"), 0.8, "why",
                TriggerReason.INVENTORY_LOW, "ruleBased");
        var hold = new PricingSuggestion(p, new BigDecimal("20.00"), 0.8, "why",
                TriggerReason.MANUAL, "ruleBased");

        assertThat(increase.getDirection()).isEqualTo(PriceDirection.INCREASE);
        assertThat(decrease.getDirection()).isEqualTo(PriceDirection.DECREASE);
        assertThat(hold.getDirection()).isEqualTo(PriceDirection.HOLD);
    }

    @Test
    void acceptingAPricingSuggestionPublishesThePrice() {
        Product p = product();
        var suggestion = new PricingSuggestion(p, new BigDecimal("22.00"), 0.8, "why",
                TriggerReason.INVENTORY_LOW, "aiAdvisor");

        suggestion.decide(SuggestionStatus.ACCEPTED);

        assertThat(p.getCurrentPrice()).isEqualByComparingTo("22.00");
        assertThat(suggestion.getDecidedAt()).isNotNull();
    }

    @Test
    void rejectingAPricingSuggestionLeavesThePriceAlone() {
        Product p = product();
        var suggestion = new PricingSuggestion(p, new BigDecimal("22.00"), 0.8, "why",
                TriggerReason.INVENTORY_LOW, "aiAdvisor");

        suggestion.decide(SuggestionStatus.REJECTED);

        assertThat(p.getCurrentPrice()).isEqualByComparingTo("20.00");
    }

    @Test
    void acceptingAReorderSuggestionReceivesStock() {
        Product p = product();
        var suggestion = new ReorderSuggestion(p, 35, 7, 0.7, "why",
                TriggerReason.INVENTORY_LOW, "ruleBased");

        suggestion.decide(SuggestionStatus.ACCEPTED);

        assertThat(p.getStockLevel()).isEqualTo(45);
    }

    @Test
    void aDecidedSuggestionCannotBeDecidedTwice() {
        Product p = product();
        var suggestion = new PricingSuggestion(p, new BigDecimal("22.00"), 0.8, "why",
                TriggerReason.INVENTORY_LOW, "aiAdvisor");
        suggestion.decide(SuggestionStatus.ACCEPTED);

        assertThatThrownBy(() -> suggestion.decide(SuggestionStatus.REJECTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already ACCEPTED");
    }

    @Test
    void confidenceIsClampedIntoRange() {
        Product p = product();

        var tooHigh = new PricingSuggestion(p, new BigDecimal("22.00"), 4.2, "why",
                TriggerReason.MANUAL, "aiAdvisor");
        var negative = new PricingSuggestion(p, new BigDecimal("22.00"), -1.0, "why",
                TriggerReason.MANUAL, "aiAdvisor");

        assertThat(tooHigh.getConfidence()).isEqualTo(1.0);
        assertThat(negative.getConfidence()).isEqualTo(0.0);
    }

    @Test
    void autoTriggeredReasonsAreDistinguishableForUiBadges() {
        assertThat(TriggerReason.INVENTORY_LOW.isAutoTriggered()).isTrue();
        assertThat(TriggerReason.DEMAND_SPIKE.isAutoTriggered()).isTrue();
        assertThat(TriggerReason.MANUAL.isAutoTriggered()).isFalse();
        assertThat(TriggerReason.INITIAL.isAutoTriggered()).isFalse();
    }
}
