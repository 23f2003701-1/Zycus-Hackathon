package com.stockpulse.engine.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.CommerceContext;

/**
 * Builds the pricing prompt for the situation that actually occurred.
 *
 * <p>Inventory-low and demand-spike are not the same question with a different label. Low stock is
 * an ambiguous choice between protecting scarce inventory and clearing it; a spike is a question
 * about how much elevated willingness to pay can be captured before conversion suffers. The two
 * prompts therefore frame different option sets and ask the model to weigh different evidence.
 * Only the numeric fact sheet is shared.
 */
@Component
public class PricingPromptFactory {

    private static final String ROLE =
            "You are a merchandising pricing analyst for an online retailer. You advise a human "
                    + "merchandiser who will approve or reject your recommendation; you never set "
                    + "prices yourself.";

    private final CommerceProperties properties;

    public PricingPromptFactory(CommerceProperties properties) {
        this.properties = properties;
    }

    public String create(CommerceContext context) {
        return switch (context.trigger()) {
            case INVENTORY_LOW -> inventoryLow(context);
            case DEMAND_SPIKE -> demandSpike(context);
            case MANUAL, INITIAL -> routineReview(context);
        };
    }

    private String inventoryLow(CommerceContext context) {
        return """
                %s

                SITUATION: INVENTORY RUNNING LOW
                This product has fallen below its reorder threshold and replenishment has not
                arrived yet. You are deciding what to do with the price of the units still on hand.

                %s
                THE DECISION
                Low stock is genuinely ambiguous. Two opposing moves are both defensible:

                  A) RAISE the price. Slows depletion, protects the remaining units for customers
                     willing to pay more, and earns more margin on inventory you cannot currently
                     replace. Strongest when demand looks durable and a restock is expected.

                  B) DISCOUNT the price. Clears the remaining units quickly. Strongest when the
                     product is losing momentum, is seasonal or end-of-life, or when being left
                     with a stranded handful of units is worse than a thinner margin.

                  C) HOLD, when neither case is convincing.

                Weigh at least: days of cover at the current rate, how far below threshold stock has
                fallen, the margin headroom above unit cost, and whether the demand signal looks
                durable or is fading.

                Do not just emit a number. Your reasoning must name the option you rejected and say
                why, because the merchandiser is deciding between these two stories, not between
                two prices.

                %s
                """.formatted(ROLE, ProductFacts.of(context), outputContract(context));
    }

    private String demandSpike(CommerceContext context) {
        return """
                %s

                SITUATION: DEMAND SPIKE
                Sales velocity on this product has jumped sharply above comparable products in its
                category. Stock is not necessarily the binding constraint here - attention is. This
                item is trending right now.

                %s
                THE DECISION
                A spike is a chance to capture willingness to pay that did not exist last week. The
                risk sits on the other side of the trade:

                  - Too small an increase leaves obvious margin on the table during the only window
                    when this product has the traffic to earn it.
                  - Too large an increase suppresses conversion at exactly the moment traffic peaks,
                    and visible opportunism on a trending item costs more in customer trust than it
                    gains in margin.

                Weigh at least: whether the spike looks durable or like a one-off burst, how many
                days of cover remain at the elevated rate, and how price-sensitive this category
                usually is. If stock cover is short as well, say so - that argues for a larger move.

                Increases of roughly 3-12%% are typical for a spike. Justify the magnitude you chose
                instead of defaulting to the middle of that range. Recommending HOLD is acceptable
                if you judge the spike to be noise.

                %s
                """.formatted(ROLE, ProductFacts.of(context), outputContract(context));
    }

    private String routineReview(CommerceContext context) {
        return """
                %s

                SITUATION: ROUTINE PRICE REVIEW
                A merchandiser has asked for a price opinion on this product. No inventory or demand
                alarm has fired - this is a considered review, so the default answer is HOLD and the
                burden is on you to justify moving.

                %s
                THE DECISION
                Recommend a change only if the facts support one. Consider whether stock cover and
                demand are in balance, whether margin is unusually thin or unusually fat for the
                category, and whether velocity relative to peers suggests the product is mispriced.

                %s
                """.formatted(ROLE, ProductFacts.of(context), outputContract(context));
    }

    /**
     * Bounds are stated in the prompt as well as enforced in code. Telling the model the guardrail
     * makes a usable answer likely; validating afterwards makes a bad one harmless.
     */
    private String outputContract(CommerceContext context) {
        BigDecimal current = context.currentPrice();
        BigDecimal maxChange = properties.getMaxAiPriceChangePct();
        BigDecimal lower = boundary(current, maxChange.negate());
        BigDecimal upper = boundary(current, maxChange);

        String floor = context.product().getMarginFloor() == null
                ? ""
                : "\n  - Never recommend below the margin floor of $"
                        + context.product().getMarginFloor().toPlainString();

        return """
                CONSTRAINTS
                  - recommendedPrice must be between $%s and $%s%s
                  - confidence is your own honest certainty, from 0.0 to 1.0
                  - reasoning must be 2 to 3 sentences of plain English a merchandiser can act on,
                    referring to the actual numbers above

                Reply with a single JSON object and nothing else. No markdown, no commentary.
                {"recommendedPrice": <number>, "direction": "INCREASE" | "DECREASE" | "HOLD", \
                "confidence": <number 0.0-1.0>, "reasoning": "<2-3 sentences>"}
                """.formatted(lower.toPlainString(), upper.toPlainString(), floor);
    }

    private static BigDecimal boundary(BigDecimal current, BigDecimal percent) {
        return current
                .multiply(BigDecimal.ONE.add(percent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
