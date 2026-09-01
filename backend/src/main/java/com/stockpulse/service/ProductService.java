package com.stockpulse.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stockpulse.api.dto.CreateProductRequest;
import com.stockpulse.api.dto.ProductResponse;
import com.stockpulse.api.error.ResourceNotFoundException;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.event.InventorySignalEvent;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.repository.ProductSpecifications;

/**
 * Catalog and inventory orchestration: load, mutate through domain behaviour, announce the change.
 *
 * <p>Notice what is absent - no pricing arithmetic, no reorder maths, no prompt building. Those
 * live in the engine, which is what stops this class turning into the catch-all the brief warns
 * about. It also does not decide what a stock change means; it publishes the signal and the
 * agentic loop interprets it.
 */
@Service
@Transactional
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public ProductService(ProductRepository products, ApplicationEventPublisher events) {
        this.products = products;
        this.events = events;
    }

    public ProductResponse create(CreateProductRequest request) {
        if (products.existsBySku(request.sku())) {
            throw new IllegalArgumentException("sku already exists: " + request.sku());
        }
        Product product = new Product(
                generateId(),
                request.sku(),
                request.name(),
                request.category(),
                request.currentPrice(),
                request.stockLevel(),
                request.reorderThreshold());
        product.setCostPrice(request.costPrice());
        product.setMarginFloor(request.marginFloor());
        product.setSupplierId(request.supplierId());

        Product saved = products.save(product);
        log.info("Created product {} ({}) at {} with stock {}",
                saved.getId(), saved.getSku(), saved.getCurrentPrice(), saved.getStockLevel());
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list(ProductStatus status, Category category) {
        return products.findAll(ProductSpecifications.matching(status, category), Sort.by("sku")).stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String id) {
        return ProductResponse.from(require(id));
    }

    /** Absolute stock correction. Returns immediately; any recommendation happens off-thread. */
    public ProductResponse adjustStock(String id, int newStockLevel) {
        Product product = require(id);
        int previous = product.getStockLevel();
        product.adjustStockTo(newStockLevel);
        log.info("Stock for {} adjusted {} -> {} (threshold {})",
                id, previous, product.getStockLevel(), product.getReorderThreshold());

        publishSignal(product, InventorySignalEvent.Origin.STOCK_ADJUSTMENT);
        return ProductResponse.from(product);
    }

    /** Simulated sale. Returns immediately; any recommendation happens off-thread. */
    public ProductResponse recordOrder(String id, int quantity) {
        Product product = require(id);
        product.recordSale(quantity);
        log.info("Order of {} on {} leaves stock {} (threshold {}), demand velocity {}",
                quantity, id, product.getStockLevel(), product.getReorderThreshold(),
                product.getDemandVelocity());

        publishSignal(product, InventorySignalEvent.Origin.ORDER_PLACED);
        return ProductResponse.from(product);
    }

    /**
     * Loads a managed entity for callers that intend to mutate it, so it deliberately does not
     * declare a read-only transaction.
     */
    public Product require(String id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("product", id));
    }

    /**
     * Announces the change rather than acting on it. The event is published inside the transaction
     * so the agentic loop can be bound to after-commit delivery in T-4 and never observe stock
     * that was subsequently rolled back.
     */
    private void publishSignal(Product product, InventorySignalEvent.Origin origin) {
        events.publishEvent(new InventorySignalEvent(product.getId(), origin));
    }

    private static String generateId() {
        return "PRD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
