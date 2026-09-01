package com.stockpulse.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;

/**
 * Specifications rather than a fixed query method: sprint 2 filters (margin floor breached,
 * supplier, competitor-undercut) compose in without changing this interface.
 */
public interface ProductRepository extends JpaRepository<Product, String>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    /**
     * Category peer baseline, excluding the product being assessed.
     *
     * <p>The exclusion is load-bearing, not tidiness. If a product is counted in its own category
     * average, the spike test {@code velocity > multiplier * average} becomes unsatisfiable in a
     * small category: for a three-product category it reduces to {@code v > peers + v}, which is
     * never true. Comparing against peers also matches what the signal is supposed to mean - this
     * product is outrunning comparable ones.
     *
     * @return 0 when the product has no peers, so a category of one never reads as a spike
     */
    @Query("""
            select coalesce(avg(p.demandVelocity), 0) from Product p
            where p.category = :category and p.id <> :excludedProductId
            """)
    double averagePeerDemandVelocity(@Param("category") Category category,
                                     @Param("excludedProductId") String excludedProductId);
}
