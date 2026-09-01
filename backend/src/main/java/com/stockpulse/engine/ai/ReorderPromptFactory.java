package com.stockpulse.engine.ai;

import org.springframework.stereotype.Component;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.CommerceContext;

/**
 * Builds the replenishment prompt. Also trigger-aware: refilling a product that quietly drained is
 * a different sizing problem from refilling one that is currently going viral, because the second
 * carries real overstock risk if the spike does not last.
 */
@Component
public class ReorderPromptFactory {

    private static final String ROLE =
            "You are an inventory planner for an online retailer. You size replenishment orders for "
                    + "a human merchandiser to approve.";

    private final CommerceProperties properties;

    public ReorderPromptFactory(CommerceProperties properties) {
        this.properties = properties;
    }

    public String create(CommerceContext context) {
        return switch (context.trigger()) {
            case DEMAND_SPIKE -> spikeReplenishment(context);
            case INVENTORY_LOW, MANUAL, INITIAL -> standardReplenishment(context);
        };
    }

    private String standardReplenishment(CommerceContext context) {
        return """
                %s

                SITUATION: STOCK BELOW REORDER THRESHOLD
                This product needs replenishing. Size the order.

                %s
                THE DECISION
                Order enough to cover the supplier lead time plus a sensible buffer, without tying up
                working capital in stock that will sit. Weigh days of cover remaining, the steady
                demand rate, and how far below threshold stock has fallen.

                A reasonable default is to restore stock to roughly %dx the reorder threshold. Depart
                from that default if the demand facts justify it, and say so.

                %s
                """.formatted(ROLE, ProductFacts.of(context),
                properties.getReorderTargetMultiplier(), outputContract(context));
    }

    private String spikeReplenishment(CommerceContext context) {
        return """
                %s

                SITUATION: REPLENISHING INTO A DEMAND SPIKE
                This product is selling far faster than its category peers. You are sizing an order
                against demand that is elevated right now but may not stay that way.

                %s
                THE DECISION
                The asymmetry matters here. Under-ordering means stocking out during the only period
                with this much traffic. Over-ordering means holding inventory bought for a spike that
                has since passed, which in a category like apparel can mean markdowns later.

                Size for the elevated rate over the lead time, but be explicit about how much of the
                spike you are assuming persists. If you judge the spike likely to be transient, order
                conservatively and say why.

                %s
                """.formatted(ROLE, ProductFacts.of(context), outputContract(context));
    }

    private String outputContract(CommerceContext context) {
        int maxQuantity = context.product().getReorderThreshold() * properties.getMaxAiReorderMultiplier();

        return """
                CONSTRAINTS
                  - recommendedQuantity must be a positive whole number, at most %d units
                  - suggestedLeadTimeDays must be a positive whole number of days
                  - confidence is your own honest certainty, from 0.0 to 1.0
                  - reasoning must be 2 to 3 sentences of plain English referring to the numbers above

                Reply with a single JSON object and nothing else. No markdown, no commentary.
                {"recommendedQuantity": <integer>, "suggestedLeadTimeDays": <integer>, \
                "confidence": <number 0.0-1.0>, "reasoning": "<2-3 sentences>"}
                """.formatted(maxQuantity);
    }
}
