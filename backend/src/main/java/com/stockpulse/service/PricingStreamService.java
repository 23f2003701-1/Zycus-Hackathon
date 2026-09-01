package com.stockpulse.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.CommerceAdvisorRegistry;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.engine.PricingAdvisor;
import com.stockpulse.engine.PricingRecommendation;
import com.stockpulse.engine.StreamingPricingAdvisor;

/**
 * Runs a pricing recommendation as an observable sequence of events instead of one opaque wait.
 *
 * <p>Three properties matter more than the streaming itself:
 *
 * <ul>
 *   <li><b>The stream never silent-drops.</b> Every path - streaming advisor, non-streaming
 *       advisor, model failure, guardrail rejection - ends in either a {@code suggestion} event or
 *       an explicit {@code error} event. A merchandiser is never left with reasoning on screen and
 *       no decision attached to it.
 *   <li><b>A fallback is announced, not disguised.</b> When the model fails after emitting tokens,
 *       a {@code fallback} event says so before the rule-based answer arrives, so nobody reads a
 *       deterministic price as if the model had argued for it.
 *   <li><b>No transaction spans the model call.</b> The context is read in one short transaction,
 *       the advisor runs outside any transaction, and the suggestion is written in another.
 * </ul>
 *
 * <p>Deliberately not annotated {@code @Transactional}: that is the whole point.
 */
@Service
public class PricingStreamService {

    /** Named SSE events. Named rather than one polymorphic payload so a client can ignore what it does not handle. */
    static final String EVENT_STATUS = "status";
    static final String EVENT_TOKEN = "token";
    static final String EVENT_FALLBACK = "fallback";
    static final String EVENT_SUGGESTION = "suggestion";
    static final String EVENT_ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(PricingStreamService.class);

    private final SuggestionService suggestions;
    private final CommerceAdvisorRegistry registry;

    public PricingStreamService(SuggestionService suggestions, CommerceAdvisorRegistry registry) {
        this.suggestions = suggestions;
        this.registry = registry;
    }

    public void streamPricing(String productId, StreamSink sink) {
        try {
            CommerceContext context = suggestions.contextFor(productId, TriggerReason.MANUAL);
            PricingRecommendation recommendation = recommend(context, sink);

            PricingSuggestionResponse saved =
                    suggestions.persistPricing(productId, TriggerReason.MANUAL, recommendation);

            sink.send(EVENT_SUGGESTION, saved);
            sink.complete();

        } catch (StreamClientGoneException ex) {
            // Someone closed the tab. The suggestion may well have been written already, which is
            // fine - it will be waiting in the queue when they come back.
            log.debug("Pricing stream for {} abandoned by the client: {}", productId, ex.getMessage());
            sink.completeWithError(ex);

        } catch (RuntimeException ex) {
            // Reached only if the deterministic fallback or the write itself failed, which is a
            // genuine defect rather than a model having a bad day - so it is logged at error.
            log.error("Pricing stream for {} could not be completed", productId, ex);
            reportFailure(sink, ex);
        }
    }

    private static void reportFailure(StreamSink sink, RuntimeException cause) {
        try {
            sink.send(EVENT_ERROR, Map.of("message", String.valueOf(cause.getMessage())));
        } catch (RuntimeException unreachable) {
            log.debug("Could not deliver the error event: {}", unreachable.getMessage());
        }
        sink.completeWithError(cause);
    }

    private PricingRecommendation recommend(CommerceContext context, StreamSink sink) {
        PricingAdvisor active = registry.activePricingAdvisor();

        if (!(active instanceof StreamingPricingAdvisor streaming)) {
            // Honest about it: the console shows "computing" rather than pretending to stream a
            // rule-based answer that was never produced token by token.
            sink.send(EVENT_STATUS, status("computing", active.name(), false));
            return active.recommendPrice(context);
        }

        sink.send(EVENT_STATUS, status("reasoning", active.name(), true));

        // The advisor wraps anything the token callback throws as a model failure, so a client
        // hanging up would otherwise look like a reason to fall back. Remember it separately.
        var disconnect = new AtomicReference<StreamClientGoneException>();
        try {
            return streaming.recommendPriceStreaming(context, token -> {
                try {
                    sink.send(EVENT_TOKEN, Map.of("text", token));
                } catch (StreamClientGoneException gone) {
                    disconnect.set(gone);
                    throw gone;
                }
            });

        } catch (RuntimeException ex) {
            if (disconnect.get() != null) {
                throw disconnect.get();
            }
            PricingAdvisor fallback = registry.fallbackPricingAdvisor();
            log.warn("Streaming advisor '{}' failed for {} ({}); falling back to '{}'",
                    active.name(), context.product().getId(), ex.getMessage(), fallback.name());

            sink.send(EVENT_FALLBACK, Map.of(
                    "reason", String.valueOf(ex.getMessage()),
                    "advisor", fallback.name()));
            return fallback.recommendPrice(context);
        }
    }

    private static Map<String, Object> status(String phase, String advisor, boolean streaming) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("phase", phase);
        payload.put("advisor", advisor);
        payload.put("streaming", streaming);
        return payload;
    }
}
