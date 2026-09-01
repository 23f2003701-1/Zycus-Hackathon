package com.stockpulse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.repository.ProductRepository;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockPulseApplicationTests {

    @Autowired
    private ProductRepository products;

    @Test
    void contextLoadsAndSeedDataIsPresent() {
        assertThat(products.count()).isEqualTo(8);
    }

    @Test
    void seededDemoProductsAreOnTheirIntendedTriggerPaths() {
        var tshirt = products.findById("PRD-003").orElseThrow();
        assertThat(tshirt.getStatus()).isEqualTo(ProductStatus.PRICE_REVIEW_PENDING);
        assertThat(tshirt.isBelowReorderThreshold()).isTrue();

        var hoodie = products.findById("PRD-008").orElseThrow();
        double peerAverage = products.averagePeerDemandVelocity(Category.APPAREL, "PRD-008");
        assertThat(hoodie.getDemandVelocity()).isGreaterThan((int) peerAverage);

        var lamp = products.findById("PRD-006").orElseThrow();
        assertThat(lamp.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void peerAverageExcludesTheProductBeingAssessed() {
        // ELECTRONICS velocities are PRD-001=3, PRD-002=1, PRD-007=8
        assertThat(products.averagePeerDemandVelocity(Category.ELECTRONICS, "PRD-001")).isEqualTo(4.5);
        assertThat(products.averagePeerDemandVelocity(Category.ELECTRONICS, "PRD-002")).isEqualTo(5.5);
    }

    /**
     * Guards the bug that made the demand-spike trigger unreachable: including a product in its
     * own category average means a 3x spike can never be satisfied in a small category.
     */
    @Test
    void aSpikeIsMathematicallyReachableForTheDemoProduct() {
        double peerAverage = products.averagePeerDemandVelocity(Category.APPAREL, "PRD-008");

        // APPAREL peers of PRD-008 are PRD-003=12 and PRD-004=2 -> 7.0, so the 3x bar is 21
        assertThat(peerAverage).isEqualTo(7.0);
        assertThat(peerAverage * 3).isLessThan(100);
    }
}
