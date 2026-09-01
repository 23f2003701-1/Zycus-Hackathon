package com.stockpulse.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.CommerceContext;

/**
 * The brief is explicit that the two pricing prompts must be genuinely different documents rather
 * than one document with a field swapped, so that difference is asserted rather than assumed.
 */
class PromptDifferentiationTest {

    private final CommerceProperties properties = new CommerceProperties();
    private final PricingPromptFactory pricingPrompts = new PricingPromptFactory(properties);
    private final ReorderPromptFactory reorderPrompts = new ReorderPromptFactory(properties);

    private static CommerceContext context(TriggerReason trigger) {
        Product product = new Product("PRD-T", "SKU-T", "Organic Cotton T-Shirt", Category.APPAREL,
                new BigDecimal("24.99"), 8, 15);
        product.setDemandVelocity(12);
        product.setCostPrice(new BigDecimal("11.00"));
        product.setMarginFloor(new BigDecimal("14.99"));
        return new CommerceContext(product, 4.0, trigger);
    }

    @Test
    void inventoryLowFramesTheProtectVersusClearTradeoff() {
        String prompt = pricingPrompts.create(context(TriggerReason.INVENTORY_LOW));

        assertThat(prompt)
                .contains("INVENTORY RUNNING LOW")
                .contains("RAISE the price")
                .contains("DISCOUNT the price")
                .contains("name the option you rejected");
    }

    @Test
    void demandSpikeFramesTheCaptureVersusConversionTradeoff() {
        String prompt = pricingPrompts.create(context(TriggerReason.DEMAND_SPIKE));

        assertThat(prompt)
                .contains("DEMAND SPIKE")
                .contains("willingness to pay")
                .contains("suppresses conversion")
                .doesNotContain("DISCOUNT the price");
    }

    @Test
    void theTwoTriggerPromptsShareOnlyTheFactSheet() {
        String low = pricingPrompts.create(context(TriggerReason.INVENTORY_LOW));
        String spike = pricingPrompts.create(context(TriggerReason.DEMAND_SPIKE));

        assertThat(low).isNotEqualTo(spike);

        // Both carry the same numbers...
        assertThat(low).contains("BELOW THRESHOLD by 7");
        assertThat(spike).contains("BELOW THRESHOLD by 7");

        // ...but the guidance shares little wording, so this is not one document relabelled.
        long sharedLines = low.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> spike.contains(line))
                .count();
        long totalLines = low.lines().filter(line -> !line.isBlank()).count();
        assertThat((double) sharedLines / totalLines).isLessThan(0.6);
    }

    @Test
    void aManualRequestDefaultsToHoldRatherThanInventingAnAlarm() {
        String prompt = pricingPrompts.create(context(TriggerReason.MANUAL));

        assertThat(prompt)
                .contains("ROUTINE PRICE REVIEW")
                .contains("default answer is HOLD");
    }

    @Test
    void everyPromptCarriesTheDerivedNumbersAndTheOutputContract() {
        for (TriggerReason trigger : TriggerReason.values()) {
            String prompt = pricingPrompts.create(context(trigger));

            assertThat(prompt)
                    .as("prompt for %s", trigger)
                    .contains("Organic Cotton T-Shirt")
                    .contains("APPAREL")
                    .contains("$24.99")
                    .contains("0.7 days")                 // days of cover, computed for the model
                    .contains("3.0x its peers")           // velocity ratio against peers
                    .contains("current gross margin 56.0%")
                    .contains("margin floor of $14.99")
                    .contains("recommendedPrice");
        }
    }

    @Test
    void reorderPromptsDifferBetweenQuietDrainAndViralSpike() {
        String low = reorderPrompts.create(context(TriggerReason.INVENTORY_LOW));
        String spike = reorderPrompts.create(context(TriggerReason.DEMAND_SPIKE));

        assertThat(low).contains("STOCK BELOW REORDER THRESHOLD");
        assertThat(spike)
                .contains("REPLENISHING INTO A DEMAND SPIKE")
                .contains("may not stay that way");
        assertThat(low).isNotEqualTo(spike);
    }

    @Test
    void reorderPromptStatesTheQuantityGuardrail() {
        // Threshold 15 x maxAiReorderMultiplier 20 = 300
        assertThat(reorderPrompts.create(context(TriggerReason.INVENTORY_LOW)))
                .contains("at most 300 units");
    }

    @Test
    void priceGuardrailsAreStatedAsConcreteBoundsNotPercentages() {
        // 24.99 with a 50% guardrail
        assertThat(pricingPrompts.create(context(TriggerReason.MANUAL)))
                .contains("between $12.50 and $37.49");
    }
}
