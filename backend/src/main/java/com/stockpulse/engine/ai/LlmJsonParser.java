package com.stockpulse.engine.ai;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.ai.LlmException;

/**
 * Extracts a JSON object from whatever the model actually sent back.
 *
 * <p>Even with a strict output contract and JSON mode requested, models wrap answers in markdown
 * fences or add a sentence of preamble. Rather than treat that as a failure, this locates the
 * outermost balanced {@code { ... }} span and parses it, so a cosmetically imperfect answer still
 * produces a usable recommendation. Anything genuinely unparseable becomes an
 * {@link LlmException}, which the advisor turns into a rule-based fallback.
 */
@Component
public class LlmJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException("model returned no content");
        }
        String candidate = extractObject(raw);
        try {
            JsonNode node = mapper.readTree(candidate);
            if (!node.isObject()) {
                throw new LlmException("expected a JSON object but got: " + abbreviate(candidate));
            }
            return node;
        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmException("unparseable model output: " + abbreviate(raw), ex);
        }
    }

    /** Finds the outermost balanced object, ignoring braces inside string literals. */
    private static String extractObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            throw new LlmException("no JSON object present in model output: " + abbreviate(raw));
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return raw.substring(start, i + 1);
            }
        }
        throw new LlmException("unterminated JSON object in model output: " + abbreviate(raw));
    }

    public BigDecimal requireDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LlmException("model output is missing required field '" + field + "'");
        }
        try {
            // Tolerates "$29.99" and "29.99" as well as a bare number.
            return new BigDecimal(value.isNumber()
                    ? value.decimalValue().toPlainString()
                    : value.asText().replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException ex) {
            throw new LlmException("field '" + field + "' is not numeric: " + value.asText(), ex);
        }
    }

    public int requireInt(JsonNode node, String field) {
        BigDecimal value = requireDecimal(node, field);
        try {
            return value.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            throw new LlmException("field '" + field + "' is not a whole number: " + value, ex);
        }
    }

    /** Confidence is advisory, so a missing or silly value is clamped rather than fatal. */
    public double optionalConfidence(JsonNode node, double fallback) {
        JsonNode value = node.get("confidence");
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        return Math.min(1.0, Math.max(0.0, value.asDouble()));
    }

    public String requireReasoning(JsonNode node) {
        JsonNode value = node.get("reasoning");
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new LlmException("model output is missing reasoning, which is the point of asking it");
        }
        return value.asText().trim();
    }

    private static String abbreviate(String raw) {
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 300 ? collapsed : collapsed.substring(0, 297) + "...";
    }
}
