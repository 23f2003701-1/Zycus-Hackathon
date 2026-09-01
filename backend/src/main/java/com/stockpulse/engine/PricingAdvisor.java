package com.stockpulse.engine;

/**
 * The pricing half of the commerce engine. Both callers - the on-demand HTTP endpoints and the
 * async agentic loop - depend on this interface and nothing else, which is what lets the active
 * implementation change underneath them without either caller being modified.
 *
 * <p>Sprint 2's {@code CompetitorAwareStrategy} means implementing this and registering a bean.
 */
public interface PricingAdvisor {

    /**
     * Stable identifier used to select this advisor at runtime and recorded on every suggestion
     * it produces. Deliberately independent of the Spring bean name so renaming a class does not
     * silently change configuration.
     */
    String name();

    PricingRecommendation recommendPrice(CommerceContext context);
}
