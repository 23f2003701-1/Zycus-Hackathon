package com.stockpulse.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.config.CommerceProperties;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.service.ProductService;
import com.stockpulse.service.SuggestionService;

/**
 * Proves the loop fires on its own. Nothing here asks for a suggestion - a sale is simulated and
 * the suggestions have to appear by themselves.
 *
 * <p>Pinned to the rule-based advisor so the assertions do not depend on a live LLM key.
 */
@SpringBootTest(properties = {
        "commerce.active-strategy=ruleBased",
        "commerce.agentic-loop-enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgenticLoopIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ProductService products;

    @Autowired
    private SuggestionService suggestions;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CommerceProperties commerceProperties;

    @Test
    void aSaleThatDrainsStockBelowThresholdQueuesBothSuggestionTypesUnasked() {
        // PRD-007 starts at stock 18 against a threshold of 25, so one sale keeps it below
        products.recordOrder("PRD-007", 1);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(autoTriggeredPricing("PRD-007", TriggerReason.INVENTORY_LOW)).isNotEmpty();
            assertThat(autoTriggeredReorder("PRD-007", TriggerReason.INVENTORY_LOW)).isNotEmpty();
        });

        var pricing = autoTriggeredPricing("PRD-007", TriggerReason.INVENTORY_LOW).getFirst();
        assertThat(pricing.status()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(pricing.autoTriggered()).isTrue();
        assertThat(pricing.reasoning()).isNotBlank();

        // The human checkpoint holds: the price has not moved
        assertThat(products.get("PRD-007").currentPrice()).isEqualByComparingTo("44.99");
    }

    /**
     * The spike bar is derived rather than hardcoded, because it is relative: other tests in this
     * class sell APPAREL products, which lifts PRD-008's peer average and moves the bar. Reading it
     * at the moment of the test keeps this independent of execution order.
     */
    @Test
    void anOrderThatPushesVelocityPastPeersQueuesASpikeSuggestion() {
        double peerAverage = productRepository.averagePeerDemandVelocity(Category.APPAREL, "PRD-008");
        int barToClear = (int) Math.floor(peerAverage * commerceProperties.getDemandSpikeMultiplier());
        int currentVelocity = products.get("PRD-008").demandVelocity();
        int unitsNeeded = Math.max(1, barToClear + 1 - currentVelocity);

        // Stock it up first, so this exercises a pure demand spike with no low-stock signal mixed in
        products.adjustStock("PRD-008", unitsNeeded + 50);
        products.recordOrder("PRD-008", unitsNeeded);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(autoTriggeredPricing("PRD-008", TriggerReason.DEMAND_SPIKE)).isNotEmpty());

        var spike = autoTriggeredPricing("PRD-008", TriggerReason.DEMAND_SPIKE).getFirst();
        assertThat(spike.autoTriggered()).isTrue();
        assertThat(spike.reasoning()).isNotBlank();

        // A spike alone must not masquerade as a stock problem
        assertThat(autoTriggeredPricing("PRD-008", TriggerReason.INVENTORY_LOW)).isEmpty();
    }

    @Test
    void repeatedSignalsDoNotPileUpDuplicatePendingSuggestions() {
        // PRD-003 is seeded at stock 8 against a threshold of 15, so it is already below and stays
        // below for every order below - each one genuinely re-satisfies INVENTORY_LOW.
        products.recordOrder("PRD-003", 1);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(autoTriggeredPricing("PRD-003", TriggerReason.INVENTORY_LOW)).isNotEmpty());

        for (int i = 0; i < 5; i++) {
            products.recordOrder("PRD-003", 1);
        }

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(autoTriggeredPricing("PRD-003", TriggerReason.INVENTORY_LOW)).hasSize(1);
            assertThat(autoTriggeredReorder("PRD-003", TriggerReason.INVENTORY_LOW)).hasSize(1);
        });
    }

    /**
     * The counterpart to the duplicate test: a product comfortably above its threshold must not
     * attract a suggestion just because it sold something.
     */
    @Test
    void aSaleThatLeavesStockHealthyTriggersNothing() {
        // PRD-005 has stock 22 against a threshold of 10
        products.recordOrder("PRD-005", 1);

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(suggestions.pricingForProduct("PRD-005")).isEmpty());
    }

    @Test
    void aHealthyProductTriggersNothing() {
        // PRD-002 has stock 120 against a threshold of 30 and the lowest velocity in its category
        products.recordOrder("PRD-002", 1);

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(suggestions.pricingForProduct("PRD-002")).isEmpty());
    }

    /** A stock correction is a signal in its own right, not just an order. */
    @Test
    void aStockAdjustmentAlsoDrivesTheLoop() {
        products.adjustStock("PRD-004", 3);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(autoTriggeredReorder("PRD-004", TriggerReason.INVENTORY_LOW)).isNotEmpty());
    }

    private List<PricingSuggestionResponse> autoTriggeredPricing(String productId, TriggerReason trigger) {
        return suggestions.pricingForProduct(productId).stream()
                .filter(s -> s.triggerReason() == trigger)
                .filter(s -> s.status() == SuggestionStatus.PENDING)
                .toList();
    }

    private List<ReorderSuggestionResponse> autoTriggeredReorder(String productId, TriggerReason trigger) {
        return suggestions.reorderForProduct(productId).stream()
                .filter(s -> s.triggerReason() == trigger)
                .filter(s -> s.status() == SuggestionStatus.PENDING)
                .toList();
    }
}
