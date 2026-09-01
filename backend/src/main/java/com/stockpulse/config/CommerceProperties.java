package com.stockpulse.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the commerce engine and the agentic loop.
 *
 * <p>{@code activeStrategy} is deliberately mutable: the strategy registry reads it on every
 * resolve, so an admin endpoint can flip the active strategy without a restart (T-2).
 */
@ConfigurationProperties(prefix = "commerce")
public class CommerceProperties {

    /** Name of the advisor the engine should currently use. */
    private String activeStrategy = "ruleBased";

    /** Lets the agentic loop be disabled, chiefly so API tests are free of background writes. */
    private boolean agenticLoopEnabled = true;

    /** Price bump when stock has fallen below the reorder threshold. */
    private BigDecimal lowStockIncreasePct = new BigDecimal("10");

    /** Price bump when demand velocity outruns the category average. */
    private BigDecimal velocityPremiumPct = new BigDecimal("5");

    /** Velocity multiple over the category average that earns the velocity premium. */
    private double velocityPremiumMultiplier = 2.0;

    /** Velocity multiple over the category average that counts as a demand spike trigger. */
    private double demandSpikeMultiplier = 3.0;

    /** Rule-based reorder target: quantity = (threshold * multiplier) - currentStock. */
    private int reorderTargetMultiplier = 3;

    /** Suggested lead time used by the rule-based reorder baseline. */
    private int defaultLeadTimeDays = 7;

    /** Largest price move accepted from the LLM before the recommendation is rejected as absurd. */
    private BigDecimal maxAiPriceChangePct = new BigDecimal("50");

    /** Upper bound on an LLM reorder quantity, as a multiple of the reorder threshold. */
    private int maxAiReorderMultiplier = 20;

    public String getActiveStrategy() {
        return activeStrategy;
    }

    public void setActiveStrategy(String activeStrategy) {
        this.activeStrategy = activeStrategy;
    }

    public boolean isAgenticLoopEnabled() {
        return agenticLoopEnabled;
    }

    public void setAgenticLoopEnabled(boolean agenticLoopEnabled) {
        this.agenticLoopEnabled = agenticLoopEnabled;
    }

    public BigDecimal getLowStockIncreasePct() {
        return lowStockIncreasePct;
    }

    public void setLowStockIncreasePct(BigDecimal lowStockIncreasePct) {
        this.lowStockIncreasePct = lowStockIncreasePct;
    }

    public BigDecimal getVelocityPremiumPct() {
        return velocityPremiumPct;
    }

    public void setVelocityPremiumPct(BigDecimal velocityPremiumPct) {
        this.velocityPremiumPct = velocityPremiumPct;
    }

    public double getVelocityPremiumMultiplier() {
        return velocityPremiumMultiplier;
    }

    public void setVelocityPremiumMultiplier(double velocityPremiumMultiplier) {
        this.velocityPremiumMultiplier = velocityPremiumMultiplier;
    }

    public double getDemandSpikeMultiplier() {
        return demandSpikeMultiplier;
    }

    public void setDemandSpikeMultiplier(double demandSpikeMultiplier) {
        this.demandSpikeMultiplier = demandSpikeMultiplier;
    }

    public int getReorderTargetMultiplier() {
        return reorderTargetMultiplier;
    }

    public void setReorderTargetMultiplier(int reorderTargetMultiplier) {
        this.reorderTargetMultiplier = reorderTargetMultiplier;
    }

    public int getDefaultLeadTimeDays() {
        return defaultLeadTimeDays;
    }

    public void setDefaultLeadTimeDays(int defaultLeadTimeDays) {
        this.defaultLeadTimeDays = defaultLeadTimeDays;
    }

    public BigDecimal getMaxAiPriceChangePct() {
        return maxAiPriceChangePct;
    }

    public void setMaxAiPriceChangePct(BigDecimal maxAiPriceChangePct) {
        this.maxAiPriceChangePct = maxAiPriceChangePct;
    }

    public int getMaxAiReorderMultiplier() {
        return maxAiReorderMultiplier;
    }

    public void setMaxAiReorderMultiplier(int maxAiReorderMultiplier) {
        this.maxAiReorderMultiplier = maxAiReorderMultiplier;
    }
}
