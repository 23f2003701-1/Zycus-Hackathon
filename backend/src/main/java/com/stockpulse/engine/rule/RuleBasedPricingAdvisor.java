package com.stockpulse.engine.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.engine.PricingAdvisor;
import com.stockpulse.engine.PricingRecommendation;

/**
 * Deterministic pricing baseline. No external dependencies, so it doubles as the fallback
 * whenever the AI advisor times out, returns unparseable JSON, or proposes an absurd price.
 */
@Component
public class RuleBasedPricingAdvisor implements PricingAdvisor {

    public static final String NAME = "ruleBased";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CommerceProperties properties;

    public RuleBasedPricingAdvisor(CommerceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PricingRecommendation recommendPrice(CommerceContext context) {
        BigDecimal currentPrice = context.currentPrice();

        if (context.stockBelowThreshold()) {
            BigDecimal pct = properties.getLowStockIncreasePct();
            return new PricingRecommendation(
                    increaseBy(currentPrice, pct),
                    0.7,
                    ("Stock is %d against a reorder threshold of %d. Raising price by %s%% to slow "
                            + "depletion and protect the remaining units until replenishment arrives.")
                            .formatted(context.product().getStockLevel(),
                                    context.product().getReorderThreshold(),
                                    pct.stripTrailingZeros().toPlainString()),
                    NAME);
        }

        double ratio = context.velocityRatio();
        if (ratio > properties.getVelocityPremiumMultiplier()) {
            BigDecimal pct = properties.getVelocityPremiumPct();
            return new PricingRecommendation(
                    increaseBy(currentPrice, pct),
                    0.65,
                    ("Demand velocity is %d, about %.1fx the %s peer average of %.1f. Applying a "
                            + "%s%% premium to capture the elevated willingness to pay.")
                            .formatted(context.product().getDemandVelocity(), ratio,
                                    context.product().getCategory(),
                                    context.peerAverageDemandVelocity(),
                                    pct.stripTrailingZeros().toPlainString()),
                    NAME);
        }

        return PricingRecommendation.hold(currentPrice,
                ("Stock of %d is at or above the threshold of %d and demand velocity of %d is within "
                        + "normal range for %s. No price change warranted.")
                        .formatted(context.product().getStockLevel(),
                                context.product().getReorderThreshold(),
                                context.product().getDemandVelocity(),
                                context.product().getCategory()),
                NAME);
    }

    private static BigDecimal increaseBy(BigDecimal price, BigDecimal percent) {
        BigDecimal multiplier = BigDecimal.ONE.add(percent.divide(HUNDRED, 6, RoundingMode.HALF_UP));
        return price.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
