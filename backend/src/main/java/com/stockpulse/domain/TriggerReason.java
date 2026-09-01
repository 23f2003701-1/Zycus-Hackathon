package com.stockpulse.domain;

/**
 * Why a suggestion exists. Shared by both suggestion types so the agentic loop and the
 * UI badges read from one vocabulary.
 */
public enum TriggerReason {

    INITIAL,
    INVENTORY_LOW,
    DEMAND_SPIKE,
    MANUAL;

    /** True for the reasons the agentic loop raises on its own, with no human asking. */
    public boolean isAutoTriggered() {
        return this == INVENTORY_LOW || this == DEMAND_SPIKE;
    }
}
