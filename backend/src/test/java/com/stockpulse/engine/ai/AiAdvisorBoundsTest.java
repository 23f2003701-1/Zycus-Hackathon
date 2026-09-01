package com.stockpulse.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.stockpulse.ai.LlmException;
import com.stockpulse.ai.LlmGateway;
import com.stockpulse.config.CommerceProperties;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.CommerceContext;

/**
 * The "test broken LLM" pass the brief asks for. Every one of these represents a way a real model
 * has misbehaved, and each must raise {@link LlmException} so the service layer falls back to rules
 * instead of publishing nonsense to a merchandiser.
 */
class AiAdvisorBoundsTest {

    private final CommerceProperties properties = new CommerceProperties();
    private final LlmJsonParser parser = new LlmJsonParser();
    private LlmGateway gateway;
    private AiPricingAdvisor pricing;
    private AiReorderAdvisor reorder;

    @BeforeEach
    void setUp() {
        gateway = Mockito.mock(LlmGateway.class);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.describe()).thenReturn("groq/openai/gpt-oss-20b");
        pricing = new AiPricingAdvisor(gateway, new PricingPromptFactory(properties), parser, properties);
        reorder = new AiReorderAdvisor(gateway, new ReorderPromptFactory(properties), parser, properties);
    }

    private CommerceContext context(TriggerReason trigger) {
        Product product = new Product("PRD-T", "SKU-T", "Test Widget", Category.APPAREL,
                new BigDecimal("100.00"), 8, 15);
        product.setDemandVelocity(12);
        product.setCostPrice(new BigDecimal("40.00"));
        product.setMarginFloor(new BigDecimal("60.00"));
        return new CommerceContext(product, 4.0, trigger);
    }

    private void modelReplies(String body) {
        when(gateway.callLlm(anyString())).thenReturn(body);
    }

    @Test
    void aReasonableRecommendationIsAccepted() {
        modelReplies("""
                {"recommendedPrice": 110.00, "direction": "INCREASE", "confidence": 0.82,
                 "reasoning": "Only 0.7 days of cover remain, so a 10% increase slows depletion."}
                """);

        var recommendation = pricing.recommendPrice(context(TriggerReason.INVENTORY_LOW));

        assertThat(recommendation.recommendedPrice()).isEqualByComparingTo("110.00");
        assertThat(recommendation.confidence()).isEqualTo(0.82);
        assertThat(recommendation.source()).isEqualTo("aiAdvisor");
    }

    @Test
    void anAbsurdlyHighPriceIsRejected() {
        modelReplies("{\"recommendedPrice\": 999999.00, \"reasoning\": \"runaway\"}");

        assertThatThrownBy(() -> pricing.recommendPrice(context(TriggerReason.INVENTORY_LOW)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("guardrail");
    }

    @Test
    void aZeroPriceIsRejected() {
        modelReplies("{\"recommendedPrice\": 0, \"reasoning\": \"free stuff\"}");

        assertThatThrownBy(() -> pricing.recommendPrice(context(TriggerReason.INVENTORY_LOW)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("non-positive");
    }

    @Test
    void aNegativePriceIsRejected() {
        modelReplies("{\"recommendedPrice\": -20, \"reasoning\": \"negative\"}");

        assertThatThrownBy(() -> pricing.recommendPrice(context(TriggerReason.INVENTORY_LOW)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("non-positive");
    }

    @Test
    void aPriceBelowTheMarginFloorIsRejected() {
        // Within the 50% change guardrail, but under the $60.00 floor
        modelReplies("{\"recommendedPrice\": 55.00, \"reasoning\": \"clearance\"}");

        assertThatThrownBy(() -> pricing.recommendPrice(context(TriggerReason.INVENTORY_LOW)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("margin floor");
    }

    @Test
    void aDiscountWithinBoundsIsAllowedBecauseClearanceIsAValidAnswer() {
        modelReplies("""
                {"recommendedPrice": 85.00, "direction": "DECREASE", "confidence": 0.6,
                 "reasoning": "Momentum is fading, so clearing the remaining units beats holding them."}
                """);

        var recommendation = pricing.recommendPrice(context(TriggerReason.INVENTORY_LOW));

        assertThat(recommendation.recommendedPrice()).isEqualByComparingTo("85.00");
    }

    @Test
    void unparseableOutputIsRejected() {
        modelReplies("I'm sorry, I cannot help with pricing decisions.");

        assertThatThrownBy(() -> pricing.recommendPrice(context(TriggerReason.DEMAND_SPIKE)))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void aTransportFailurePropagatesAsAnLlmException() {
        when(gateway.callLlm(anyString())).thenThrow(new LlmException("read timed out after 12000ms"));

        assertThatThrownBy(() -> pricing.recommendPrice(context(TriggerReason.DEMAND_SPIKE)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("timed out");
    }

    /**
     * Makes the gateway behave like a provider: hands the advisor's callback fixed-width slices of
     * a document, which land mid-word and mid-escape exactly as a real stream does.
     */
    private void modelStreams(String body) {
        when(gateway.streamLlm(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onToken = invocation.getArgument(1);
                    for (int i = 0; i < body.length(); i += 7) {
                        onToken.accept(body.substring(i, Math.min(i + 7, body.length())));
                    }
                    return body;
                });
    }

    @Test
    void aStreamedRecommendationDeliversProseAndNotTheJsonAroundIt() {
        modelStreams("""
                {"recommendedPrice": 110.00, "confidence": 0.8, \
                "reasoning": "Cover is under a day, so a 10% rise slows depletion."}""");
        var streamed = new StringBuilder();

        var recommendation = pricing.recommendPriceStreaming(
                context(TriggerReason.INVENTORY_LOW), streamed::append);

        assertThat(streamed.toString()).isEqualTo("Cover is under a day, so a 10% rise slows depletion.");
        assertThat(recommendation.recommendedPrice()).isEqualByComparingTo("110.00");
    }

    /**
     * The nastiest failure the stream has: tokens look perfectly fine on screen, and only the parse
     * at the end reveals there is no usable recommendation. It must still be a hard failure so the
     * service layer replaces it rather than showing a merchandiser half an argument and no price.
     */
    @Test
    void aStreamThatDiesMidSentenceIsAFailureEvenThoughTokensWereDelivered() {
        modelStreams("{\"recommendedPrice\": 110.00, \"reasoning\": \"Cover is under a day, so");
        var streamed = new StringBuilder();

        assertThatThrownBy(() -> pricing.recommendPriceStreaming(
                context(TriggerReason.INVENTORY_LOW), streamed::append))
                .isInstanceOf(LlmException.class);

        assertThat(streamed.toString()).isEqualTo("Cover is under a day, so");
    }

    @Test
    void aStreamedPriceOutOfBoundsIsRejectedByTheSameGuardrailAsABlockingOne() {
        modelStreams("{\"recommendedPrice\": 999999.00, \"reasoning\": \"Scarcity is leverage.\"}");

        assertThatThrownBy(() -> pricing.recommendPriceStreaming(
                context(TriggerReason.INVENTORY_LOW), token -> { }))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("guardrail");
    }

    @Test
    void aReorderQuantityAboveTheGuardrailIsRejected() {
        // Threshold 15 x maxAiReorderMultiplier 20 = 300
        modelReplies("{\"recommendedQuantity\": 50000, \"reasoning\": \"buy the factory\"}");

        assertThatThrownBy(() -> reorder.recommendReorder(context(TriggerReason.INVENTORY_LOW)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("guardrail");
    }

    @Test
    void aNonPositiveReorderQuantityIsRejected() {
        modelReplies("{\"recommendedQuantity\": 0, \"reasoning\": \"order nothing\"}");

        assertThatThrownBy(() -> reorder.recommendReorder(context(TriggerReason.INVENTORY_LOW)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("non-positive");
    }

    @Test
    void aMissingLeadTimeFallsBackToTheConfiguredDefaultRatherThanFailing() {
        modelReplies("""
                {"recommendedQuantity": 40, "confidence": 0.7,
                 "reasoning": "Covers the lead time plus a modest buffer."}
                """);

        var recommendation = reorder.recommendReorder(context(TriggerReason.INVENTORY_LOW));

        assertThat(recommendation.recommendedQuantity()).isEqualTo(40);
        assertThat(recommendation.suggestedLeadTimeDays()).isEqualTo(7);
    }

    @Test
    void anAbsurdLeadTimeIsReplacedByTheDefault() {
        modelReplies("""
                {"recommendedQuantity": 40, "suggestedLeadTimeDays": 9000, "reasoning": "eventually"}
                """);

        assertThat(reorder.recommendReorder(context(TriggerReason.INVENTORY_LOW)).suggestedLeadTimeDays())
                .isEqualTo(7);
    }
}
