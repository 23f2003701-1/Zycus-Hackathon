package com.stockpulse.event;

/**
 * Published whenever something changes a product's stock or demand. Carries only the identity of
 * what changed, not a decision - classifying the signal into INVENTORY_LOW or DEMAND_SPIKE is the
 * agentic loop's job, on its own thread, after the HTTP response has already gone out.
 *
 * <p>This record is the decoupling seam for T-4: the service layer announces facts and never
 * learns who reacts to them.
 *
 * @param origin how the signal arose, for log traceability during the walkthrough
 */
public record InventorySignalEvent(String productId, Origin origin) {

    public enum Origin {
        STOCK_ADJUSTMENT,
        ORDER_PLACED
    }
}
