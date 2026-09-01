package com.stockpulse.engine;

import java.math.BigDecimal;

import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;

/**
 * Everything an advisor needs to reason, assembled by the service layer before the call.
 *
 * <p>This is the boundary that keeps the engine testable: no advisor touches a repository, so a
 * pricing rule or an LLM prompt can be exercised with a plain constructed context and no Spring
 * container. It is also the extension seam - sprint 2 adds competitor prices here, and existing
 * advisors keep compiling.
 *
 * @param peerAverageDemandVelocity mean 24h velocity of other products in the same category
 */
public record CommerceContext(
        Product product,
        double peerAverageDemandVelocity,
        TriggerReason trigger) {

    public CommerceContext {
        if (product == null) {
            throw new IllegalArgumentException("context requires a product");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("context requires a trigger reason");
        }
    }

    public BigDecimal currentPrice() {
        return product.getCurrentPrice();
    }

    public boolean stockBelowThreshold() {
        return product.isBelowReorderThreshold();
    }

    /**
     * How far demand outruns comparable products in the same category.
     * Returns 0 when there are no peers with measurable demand, so a quiet category never
     * looks like a spike.
     */
    public double velocityRatio() {
        if (peerAverageDemandVelocity <= 0) {
            return 0;
        }
        return product.getDemandVelocity() / peerAverageDemandVelocity;
    }

    /**
     * Days of stock remaining at the current sales rate - the number a merchandiser actually
     * reaches for when deciding between protecting inventory and clearing it.
     *
     * @return -1 when there is no measurable demand, meaning cover is effectively unbounded
     */
    public double daysOfCover() {
        int velocity = product.getDemandVelocity();
        if (velocity <= 0) {
            return -1;
        }
        return (double) product.getStockLevel() / velocity;
    }
}
