package com.stockpulse.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.rule.RuleBasedPricingAdvisor;
import com.stockpulse.engine.rule.RuleBasedReorderAdvisor;

/**
 * No Spring context needed - the whole point of CommerceContext is that advisors are pure
 * functions of their input.
 */
class RuleBasedAdvisorTest {

    private final CommerceProperties properties = new CommerceProperties();
    private final RuleBasedPricingAdvisor pricing = new RuleBasedPricingAdvisor(properties);
    private final RuleBasedReorderAdvisor reorder = new RuleBasedReorderAdvisor(properties);

    private static Product product(int stock, int threshold, int velocity) {
        Product p = new Product("PRD-T", "SKU-T", "Test Widget", Category.HOME,
                new BigDecimal("100.00"), stock, threshold);
        p.setDemandVelocity(velocity);
        return p;
    }

    @Test
    void lowStockRecommendsATenPercentIncrease() {
        var context = new CommerceContext(product(8, 15, 1), 1.0, TriggerReason.INVENTORY_LOW);

        var recommendation = pricing.recommendPrice(context);

        assertThat(recommendation.recommendedPrice()).isEqualByComparingTo("110.00");
        assertThat(recommendation.source()).isEqualTo("ruleBased");
        assertThat(recommendation.reasoning()).contains("reorder threshold of 15");
    }

    @Test
    void highVelocityRecommendsAFivePercentPremium() {
        // Stock is healthy, but velocity is 10 against a category average of 2 -> ratio 5.0
        var context = new CommerceContext(product(100, 15, 10), 2.0, TriggerReason.DEMAND_SPIKE);

        var recommendation = pricing.recommendPrice(context);

        assertThat(recommendation.recommendedPrice()).isEqualByComparingTo("105.00");
    }

    @Test
    void lowStockTakesPrecedenceOverVelocity() {
        var context = new CommerceContext(product(8, 15, 10), 2.0, TriggerReason.INVENTORY_LOW);

        assertThat(pricing.recommendPrice(context).recommendedPrice()).isEqualByComparingTo("110.00");
    }

    @Test
    void normalConditionsHold() {
        var context = new CommerceContext(product(100, 15, 2), 2.0, TriggerReason.MANUAL);

        var recommendation = pricing.recommendPrice(context);

        assertThat(recommendation.recommendedPrice()).isEqualByComparingTo("100.00");
        assertThat(recommendation.reasoning()).contains("No price change warranted");
    }

    @Test
    void velocityExactlyAtTheMultiplierDoesNotEarnThePremium() {
        // ratio is exactly 2.0 and the rule is "greater than"
        var context = new CommerceContext(product(100, 15, 4), 2.0, TriggerReason.MANUAL);

        assertThat(pricing.recommendPrice(context).recommendedPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void aQuietCategoryNeverLooksLikeASpike() {
        var context = new CommerceContext(product(100, 15, 0), 0.0, TriggerReason.MANUAL);

        assertThat(context.velocityRatio()).isZero();
        assertThat(pricing.recommendPrice(context).recommendedPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void aSlowMoverIsToppedUpToThreeTimesTheThreshold() {
        // Lead time demand is 1/day * 7 = 7, well under the threshold target of 45, so that wins
        var context = new CommerceContext(product(8, 15, 1), 1.0, TriggerReason.INVENTORY_LOW);

        var recommendation = reorder.recommendReorder(context);

        assertThat(recommendation.recommendedQuantity()).isEqualTo(37);
        assertThat(recommendation.suggestedLeadTimeDays()).isEqualTo(7);
        assertThat(recommendation.reasoning()).contains("3x the reorder threshold of 15");
    }

    @Test
    void aFastMoverIsSizedAgainstLeadTimeDemandRatherThanTheThreshold() {
        // 31/day over a 7 day lead time is 217 units, far above the threshold target of 36
        var context = new CommerceContext(product(60, 12, 31), 4.0, TriggerReason.DEMAND_SPIKE);

        var recommendation = reorder.recommendReorder(context);

        assertThat(recommendation.recommendedQuantity()).isEqualTo(157);
        assertThat(recommendation.reasoning())
                .contains("7 days of lead time at the current rate of 31 per day");
    }

    @Test
    void reorderNeverRecommendsZeroOrNegative() {
        // Stock exceeds both targets, so the shortfall would be negative
        var context = new CommerceContext(product(500, 15, 1), 1.0, TriggerReason.MANUAL);

        var recommendation = reorder.recommendReorder(context);

        assertThat(recommendation.recommendedQuantity()).isEqualTo(1);
        assertThat(recommendation.reasoning()).contains("no meaningful replenishment is needed");
    }
}
