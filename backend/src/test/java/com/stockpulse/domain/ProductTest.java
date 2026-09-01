package com.stockpulse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ProductTest {

    private static Product product(int stock, int threshold) {
        return new Product("PRD-T", "SKU-T", "Test Widget", Category.HOME,
                new BigDecimal("20.00"), stock, threshold);
    }

    @Test
    void replenishmentIsWarrantedBelowThreshold() {
        assertThat(product(8, 15).needsReplenishment(7)).isTrue();
    }

    @Test
    void aSpikeMakesComfortableStockInadequate() {
        // 60 units is five times the threshold, but at 31 a day it cannot survive a 7 day resupply
        Product p = product(60, 12);
        p.setDemandVelocity(31);

        assertThat(p.isBelowReorderThreshold()).isFalse();
        assertThat(p.needsReplenishment(7)).isTrue();
    }

    @Test
    void aWellStockedSlowMoverNeedsNothing() {
        Product p = product(60, 12);
        p.setDemandVelocity(2);

        assertThat(p.needsReplenishment(7)).isFalse();
    }

    @Test
    void aProductAboveThresholdWithNoDemandNeedsNothing() {
        Product p = product(60, 12);
        p.setDemandVelocity(0);

        assertThat(p.needsReplenishment(7)).isFalse();
    }

    @Test
    void saleDrainsStockAndRaisesVelocity() {
        Product p = product(10, 5);

        p.recordSale(3);

        assertThat(p.getStockLevel()).isEqualTo(7);
        assertThat(p.getDemandVelocity()).isEqualTo(3);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void stockReachingZeroFlipsStatusToOutOfStock() {
        Product p = product(2, 5);

        p.recordSale(2);

        assertThat(p.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void inboundShipmentRevivesAnOutOfStockProduct() {
        Product p = product(0, 5);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);

        p.receiveShipment(20);

        assertThat(p.getStockLevel()).isEqualTo(20);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void oversellIsRejected() {
        Product p = product(1, 5);

        assertThatThrownBy(() -> p.recordSale(5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient stock");
    }

    @Test
    void belowThresholdIsStrictlyLessThan() {
        assertThat(product(5, 5).isBelowReorderThreshold()).isFalse();
        assertThat(product(4, 5).isBelowReorderThreshold()).isTrue();
    }

    @Test
    void priceReviewReturnsToActiveOnceCleared() {
        Product p = product(10, 5);

        p.markPriceReviewPending();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.PRICE_REVIEW_PENDING);

        p.clearPriceReview();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void clearingPriceReviewOnAnEmptyProductLandsOnOutOfStock() {
        Product p = product(1, 5);
        p.markPriceReviewPending();
        p.recordSale(1);

        p.clearPriceReview();

        assertThat(p.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void approvedPriceMustBePositive() {
        Product p = product(10, 5);

        assertThatThrownBy(() -> p.applyApprovedPrice(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedPriceIsScaledToCurrency() {
        Product p = product(10, 5);

        p.applyApprovedPrice(new BigDecimal("21.999"));

        assertThat(p.getCurrentPrice()).isEqualByComparingTo("22.00");
    }
}
