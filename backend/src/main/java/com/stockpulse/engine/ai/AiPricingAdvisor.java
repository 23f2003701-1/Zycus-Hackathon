package com.stockpulse.engine.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.ai.LlmException;
import com.stockpulse.ai.LlmGateway;
import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.engine.PricingRecommendation;
import com.stockpulse.engine.StreamingPricingAdvisor;

/**
 * LLM-backed pricing advisor.
 *
 * <p>Implements the same contract as the rule-based baseline and nothing more, so switching to it
 * requires no change in any caller. It never attempts its own fallback: every failure mode is
 * raised as {@link LlmException} and the service layer degrades to the deterministic advisor. That
 * keeps "what do we do when the model misbehaves" in exactly one place.
 *
 * <p>Because a model can stream, it also offers {@link StreamingPricingAdvisor}. Both entry points
 * converge on {@link #interpret} before anything is returned, so a streamed recommendation passes
 * the identical parsing and guardrails as a blocking one - the stream changes when a merchandiser
 * sees the reasoning, never what the system is willing to accept.
 */
@Component
public class AiPricingAdvisor implements StreamingPricingAdvisor {

    public static final String NAME = "aiAdvisor";

    private static final Logger log = LoggerFactory.getLogger(AiPricingAdvisor.class);

    private final LlmGateway gateway;
    private final PricingPromptFactory prompts;
    private final LlmJsonParser parser;
    private final CommerceProperties properties;

    public AiPricingAdvisor(LlmGateway gateway, PricingPromptFactory prompts,
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
    public PricingRecommendation recommendPrice(CommerceContext context) {
        return interpret(context, gateway.callLlm(promptFor(context)));
    }

    /**
     * Streams the model's reasoning, then validates the finished document exactly as the blocking
     * path does. The filter exists because the prompt asks for JSON: the raw tokens spell out a
     * data structure, and only the prose inside {@code reasoning} is fit to show a human.
     */
    @Override
    public PricingRecommendation recommendPriceStreaming(CommerceContext context,
                                                        Consumer<String> onReasoningToken) {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        String raw = gateway.streamLlm(promptFor(context), rawChunk -> {
            String readable = filter.accept(rawChunk);
            if (!readable.isEmpty()) {
                onReasoningToken.accept(readable);
            }
        });

        return interpret(context, raw);
    }

    private String promptFor(CommerceContext context) {
        String prompt = prompts.create(context);
        log.debug("Pricing prompt for {} [{}]:\n{}", context.product().getId(), context.trigger(), prompt);
        return prompt;
    }

    private PricingRecommendation interpret(CommerceContext context, String rawCompletion) {
        JsonNode answer = parser.parseObject(rawCompletion);

        BigDecimal recommendedPrice = parser.requireDecimal(answer, "recommendedPrice")
                .setScale(2, RoundingMode.HALF_UP);
        validate(context, recommendedPrice);

        PricingRecommendation recommendation = new PricingRecommendation(
                recommendedPrice,
                parser.optionalConfidence(answer, 0.5),
                parser.requireReasoning(answer),
                NAME);

        log.info("AI priced {} [{}] at {} (was {}) with confidence {}",
                context.product().getId(), context.trigger(), recommendedPrice,
                context.currentPrice(), recommendation.confidence());
        return recommendation;
    }

    /**
     * Rejects recommendations no merchandiser would accept. A rejection is not a degraded answer -
     * it is a failure, so it throws and lets the caller fall back to rules rather than quietly
     * clamping a number the model never actually proposed.
     */
    private void validate(CommerceContext context, BigDecimal recommendedPrice) {
        if (recommendedPrice.signum() <= 0) {
            throw new LlmException("model recommended a non-positive price: " + recommendedPrice);
        }

        BigDecimal current = context.currentPrice();
        BigDecimal changePct = recommendedPrice.subtract(current)
                .abs()
                .multiply(new BigDecimal("100"))
                .divide(current, 2, RoundingMode.HALF_UP);

        if (changePct.compareTo(properties.getMaxAiPriceChangePct()) > 0) {
            throw new LlmException("model recommended %s against a current price of %s, a %s%% change that exceeds the %s%% guardrail"
                    .formatted(recommendedPrice, current, changePct, properties.getMaxAiPriceChangePct()));
        }

        BigDecimal marginFloor = context.product().getMarginFloor();
        if (marginFloor != null && recommendedPrice.compareTo(marginFloor) < 0) {
            throw new LlmException("model recommended %s, below the margin floor of %s"
                    .formatted(recommendedPrice, marginFloor));
        }
    }
}
