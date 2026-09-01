package com.stockpulse.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.stockpulse.ai.LlmException;

class LlmJsonParserTest {

    private final LlmJsonParser parser = new LlmJsonParser();

    @Test
    void parsesACleanJsonObject() {
        var node = parser.parseObject("""
                {"recommendedPrice":29.99,"direction":"INCREASE","confidence":0.82,"reasoning":"ok"}
                """);

        assertThat(parser.requireDecimal(node, "recommendedPrice")).isEqualByComparingTo("29.99");
        assertThat(parser.optionalConfidence(node, 0.5)).isEqualTo(0.82);
        assertThat(parser.requireReasoning(node)).isEqualTo("ok");
    }

    @Test
    void survivesMarkdownFences() {
        var node = parser.parseObject("""
                ```json
                {"recommendedPrice": 31.50, "confidence": 0.7, "reasoning": "fenced"}
                ```
                """);

        assertThat(parser.requireDecimal(node, "recommendedPrice")).isEqualByComparingTo("31.50");
    }

    @Test
    void survivesConversationalPreambleAndTrailingChatter() {
        var node = parser.parseObject("""
                Sure! Here is my recommendation:
                {"recommendedPrice": 27.00, "confidence": 0.6, "reasoning": "chatty"}
                Let me know if you would like me to reconsider.
                """);

        assertThat(parser.requireReasoning(node)).isEqualTo("chatty");
    }

    @Test
    void handlesBracesInsideStringValues() {
        var node = parser.parseObject(
                "{\"recommendedPrice\": 10.00, \"confidence\": 0.5, \"reasoning\": \"uses { and } inside\"}");

        assertThat(parser.requireReasoning(node)).isEqualTo("uses { and } inside");
    }

    @Test
    void toleratesACurrencySymbolOnTheNumber() {
        var node = parser.parseObject("{\"recommendedPrice\": \"$29.99\", \"reasoning\": \"quoted\"}");

        assertThat(parser.requireDecimal(node, "recommendedPrice")).isEqualByComparingTo("29.99");
    }

    @Test
    void roundsAFractionalQuantityToAWholeNumber() {
        var node = parser.parseObject("{\"recommendedQuantity\": 149.6, \"reasoning\": \"fractional\"}");

        assertThat(parser.requireInt(node, "recommendedQuantity")).isEqualTo(150);
    }

    @Test
    void rejectsOutputWithNoJsonAtAll() {
        assertThatThrownBy(() -> parser.parseObject("I am unable to help with that request."))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no JSON object");
    }

    @Test
    void rejectsTruncatedJson() {
        assertThatThrownBy(() -> parser.parseObject("{\"recommendedPrice\": 29.99, \"reasoning\": \"cut o"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("unterminated");
    }

    @Test
    void rejectsEmptyOutput() {
        assertThatThrownBy(() -> parser.parseObject("   "))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no content");
    }

    @Test
    void rejectsAMissingRequiredField() {
        var node = parser.parseObject("{\"confidence\": 0.8, \"reasoning\": \"no price\"}");

        assertThatThrownBy(() -> parser.requireDecimal(node, "recommendedPrice"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("missing required field");
    }

    @Test
    void missingReasoningIsFatalBecauseItIsThePointOfAskingAModel() {
        var node = parser.parseObject("{\"recommendedPrice\": 29.99, \"confidence\": 0.9}");

        assertThatThrownBy(() -> parser.requireReasoning(node))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("missing reasoning");
    }

    @Test
    void nonsensicalConfidenceIsClampedRatherThanFatal() {
        assertThat(parser.optionalConfidence(parser.parseObject("{\"confidence\": 42}"), 0.5)).isEqualTo(1.0);
        assertThat(parser.optionalConfidence(parser.parseObject("{\"confidence\": -3}"), 0.5)).isEqualTo(0.0);
        assertThat(parser.optionalConfidence(parser.parseObject("{}"), 0.5)).isEqualTo(0.5);
        assertThat(parser.optionalConfidence(parser.parseObject("{\"confidence\":\"high\"}"), 0.5))
                .isEqualTo(0.5);
    }
}
