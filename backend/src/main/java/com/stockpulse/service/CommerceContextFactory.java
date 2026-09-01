package com.stockpulse.service;

import org.springframework.stereotype.Component;

import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.repository.ProductRepository;

/**
 * Assembles the advisor input. Exists so that advisors - including the AI one - never reach for a
 * repository, which is what keeps them unit-testable without a database or a Spring context.
 */
@Component
public class CommerceContextFactory {

    private final ProductRepository products;

    public CommerceContextFactory(ProductRepository products) {
        this.products = products;
    }

    public CommerceContext create(Product product, TriggerReason trigger) {
        double peerAverage = products.averagePeerDemandVelocity(product.getCategory(), product.getId());
        return new CommerceContext(product, peerAverage, trigger);
    }
}
