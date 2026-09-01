package com.stockpulse.engine;

/**
 * The replenishment half of the commerce engine.
 *
 * <p>Kept separate from {@link PricingAdvisor} rather than folded into one unified contract so the
 * two can fail independently: an LLM pricing call can time out and fall back to rules while the
 * reorder recommendation still succeeds. See ADR entry 2.
 */
public interface ReorderAdvisor {

    String name();

    ReorderRecommendation recommendReorder(CommerceContext context);
}
