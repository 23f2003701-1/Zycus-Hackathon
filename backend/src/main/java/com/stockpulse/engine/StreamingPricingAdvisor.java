package com.stockpulse.engine;

import java.util.function.Consumer;

/**
 * An optional capability some {@link PricingAdvisor} implementations have: producing their answer
 * incrementally so a merchandiser can watch the reasoning form instead of staring at a spinner.
 *
 * <p>Kept separate from {@link PricingAdvisor} on purpose. Streaming is a property of how an
 * advisor is <em>implemented</em>, not of what a pricing advisor <em>is</em> - a lookup table has
 * nothing to stream. Folding it into the main contract would force every present and future
 * implementation to carry a method most of them can only answer by faking. Callers that want a
 * stream ask with {@code instanceof} and degrade to the plain call when the answer is no, so the
 * rule-based baseline stays a two-method class.
 */
public interface StreamingPricingAdvisor extends PricingAdvisor {

    /**
     * Produces the same recommendation as {@link #recommendPrice}, invoking {@code onReasoningToken}
     * as the answer arrives.
     *
     * <p>The callback receives <em>human-readable reasoning prose</em>, never the transport or
     * serialisation format the advisor happens to use underneath. An implementation that asks a
     * model for JSON is responsible for extracting the prose before it calls back, because the
     * console renders these fragments directly to a merchandiser.
     *
     * <p>Failure semantics are identical to {@link #recommendPrice}: throw, and let the service
     * layer decide what to fall back to. Tokens already delivered before a failure are not
     * retracted, so callers should treat them as provisional until the recommendation returns.
     */
    PricingRecommendation recommendPriceStreaming(CommerceContext context, Consumer<String> onReasoningToken);
}
