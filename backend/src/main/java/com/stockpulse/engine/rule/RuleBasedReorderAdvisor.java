package com.stockpulse.engine.rule;

import org.springframework.stereotype.Component;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.engine.ReorderAdvisor;
import com.stockpulse.engine.ReorderRecommendation;

/**
 * Replenishment baseline: order up to whichever is larger of a multiple of the reorder threshold
 * and the demand expected over the supplier lead time.
 *
 * <p>The demand term matters. Sizing against the threshold alone ignores how fast the product is
 * actually selling, which under-orders exactly the products that are running away and produces
 * nonsense for a fast mover that happens to be well stocked - a threshold-only target of 36 units
 * against 60 units on hand yields "order 1", while the product is selling 31 a day.
 *
 * <p>Still deliberately crude, so the AI advisor has something obvious to improve on.
 */
@Component
public class RuleBasedReorderAdvisor implements ReorderAdvisor {

    public static final String NAME = "ruleBased";

    private final CommerceProperties properties;

    public RuleBasedReorderAdvisor(CommerceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ReorderRecommendation recommendReorder(CommerceContext context) {
        int stock = context.product().getStockLevel();
        int leadTimeDays = properties.getDefaultLeadTimeDays();

        int thresholdTarget = context.product().getReorderThreshold() * properties.getReorderTargetMultiplier();
        int leadTimeDemand = context.product().getDemandVelocity() * leadTimeDays;
        int target = Math.max(thresholdTarget, leadTimeDemand);
        int shortfall = target - stock;

        return new ReorderRecommendation(
                Math.max(1, shortfall),
                leadTimeDays,
                0.6,
                reasoning(context, stock, leadTimeDays, thresholdTarget, leadTimeDemand, target, shortfall),
                NAME);
    }

    private String reasoning(CommerceContext context, int stock, int leadTimeDays,
                             int thresholdTarget, int leadTimeDemand, int target, int shortfall) {
        if (shortfall <= 0) {
            // Only reachable from an explicit request - the agentic loop screens these out.
            return ("Stock of %d units already covers the target of %d, so no meaningful replenishment "
                    + "is needed. Recommending the minimum order only because one was requested.")
                    .formatted(stock, target);
        }

        String basis = leadTimeDemand > thresholdTarget
                ? "%d units to cover %d days of lead time at the current rate of %d per day"
                        .formatted(leadTimeDemand, leadTimeDays, context.product().getDemandVelocity())
                : "%d units, which is %dx the reorder threshold of %d"
                        .formatted(thresholdTarget, properties.getReorderTargetMultiplier(),
                                context.product().getReorderThreshold());

        return "Targeting %s. Current stock is %d, so %d units are required."
                .formatted(basis, stock, shortfall);
    }
}
