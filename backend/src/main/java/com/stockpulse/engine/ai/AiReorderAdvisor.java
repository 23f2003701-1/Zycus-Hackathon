package com.stockpulse.engine.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.ai.LlmException;
import com.stockpulse.ai.LlmGateway;
import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.engine.ReorderAdvisor;
import com.stockpulse.engine.ReorderRecommendation;

/**
 * LLM-backed replenishment advisor. Separate from {@link AiPricingAdvisor} so a pricing timeout
 * does not cost us the reorder recommendation, and vice versa.
 */
@Component
public class AiReorderAdvisor implements ReorderAdvisor {

    public static final String NAME = "aiAdvisor";

    private static final Logger log = LoggerFactory.getLogger(AiReorderAdvisor.class);

    private final LlmGateway gateway;
    private final ReorderPromptFactory prompts;
    private final LlmJsonParser parser;
    private final CommerceProperties properties;

    public AiReorderAdvisor(LlmGateway gateway, ReorderPromptFactory prompts,
                            LlmJsonParser parser, CommerceProperties properties) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.parser = parser;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ReorderRecommendation recommendReorder(CommerceContext context) {
        String prompt = prompts.create(context);
        log.debug("Reorder prompt for {} [{}]:\n{}", context.product().getId(), context.trigger(), prompt);

        JsonNode answer = parser.parseObject(gateway.callLlm(prompt));

        int quantity = parser.requireInt(answer, "recommendedQuantity");
        int leadTimeDays = leadTime(answer);
        validate(context, quantity);

        ReorderRecommendation recommendation = new ReorderRecommendation(
                quantity,
                leadTimeDays,
                parser.optionalConfidence(answer, 0.5),
                parser.requireReasoning(answer),
                NAME);

        log.info("AI recommended reordering {} units of {} [{}] with lead time {}d",
                quantity, context.product().getId(), context.trigger(), leadTimeDays);
        return recommendation;
    }

    /** Lead time is a nice-to-have, so fall back to the configured default rather than failing. */
    private int leadTime(JsonNode answer) {
        try {
            int days = parser.requireInt(answer, "suggestedLeadTimeDays");
            return days > 0 && days <= 365 ? days : properties.getDefaultLeadTimeDays();
        } catch (LlmException ex) {
            return properties.getDefaultLeadTimeDays();
        }
    }

    private void validate(CommerceContext context, int quantity) {
        if (quantity <= 0) {
            throw new LlmException("model recommended a non-positive reorder quantity: " + quantity);
        }
        int maxQuantity = context.product().getReorderThreshold() * properties.getMaxAiReorderMultiplier();
        if (quantity > maxQuantity) {
            throw new LlmException("model recommended %d units, above the %d unit guardrail for this product"
                    .formatted(quantity, maxQuantity));
        }
    }
}
