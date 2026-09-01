package com.stockpulse.domain;

import java.util.Set;

/**
 * Product lifecycle: {@code ACTIVE -> PRICE_REVIEW_PENDING -> ACTIVE},
 * with {@code OUT_OF_STOCK} reachable from either whenever stock hits zero.
 */
public enum ProductStatus {

    ACTIVE,
    PRICE_REVIEW_PENDING,
    OUT_OF_STOCK;

    private static final Set<ProductStatus> FROM_ACTIVE = Set.of(PRICE_REVIEW_PENDING, OUT_OF_STOCK);
    private static final Set<ProductStatus> FROM_REVIEW = Set.of(ACTIVE, OUT_OF_STOCK);
    private static final Set<ProductStatus> FROM_OUT_OF_STOCK = Set.of(ACTIVE, PRICE_REVIEW_PENDING);

    public boolean canTransitionTo(ProductStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case ACTIVE -> FROM_ACTIVE.contains(target);
            case PRICE_REVIEW_PENDING -> FROM_REVIEW.contains(target);
            case OUT_OF_STOCK -> FROM_OUT_OF_STOCK.contains(target);
        };
    }
}
