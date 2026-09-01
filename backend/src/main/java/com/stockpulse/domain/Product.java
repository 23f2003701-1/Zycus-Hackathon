package com.stockpulse.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(length = 40, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, unique = true, length = 60)
    private String sku;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(name = "current_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "stock_level", nullable = false)
    private int stockLevel;

    @Column(name = "reorder_threshold", nullable = false)
    private int reorderThreshold;

    /** Orders in the last 24h. Maintained incrementally as sales are simulated. */
    @Column(name = "demand_velocity", nullable = false)
    private int demandVelocity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductStatus status = ProductStatus.ACTIVE;

    // --- Sprint 2 extension points. Nullable today, no migration needed later. ---

    /** Unit cost. Sprint 2 uses this for margin display and margin-floor enforcement. */
    @Column(name = "cost_price", precision = 12, scale = 2)
    private BigDecimal costPrice;

    /** Lowest price the engine may ever recommend. Sprint 2 enforces this against the LLM. */
    @Column(name = "margin_floor", precision = 12, scale = 2)
    private BigDecimal marginFloor;

    /** Preferred supplier for reorder suggestions once the supplier catalog exists. */
    @Column(name = "supplier_id", length = 40)
    private String supplierId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected Product() {
        // for JPA
    }

    public Product(String id, String sku, String name, Category category, BigDecimal currentPrice,
                   int stockLevel, int reorderThreshold) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.currentPrice = scale(currentPrice);
        this.stockLevel = stockLevel;
        this.reorderThreshold = reorderThreshold;
        this.demandVelocity = 0;
        this.status = stockLevel == 0 ? ProductStatus.OUT_OF_STOCK : ProductStatus.ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- Behaviour: every stock or price mutation goes through these, so the
    // --- OUT_OF_STOCK invariant can never drift out of sync with stockLevel.

    /** Absolute stock correction, as used by {@code PATCH /products/{id}/stock}. */
    public void adjustStockTo(int newStockLevel) {
        if (newStockLevel < 0) {
            throw new IllegalArgumentException("stockLevel cannot be negative");
        }
        this.stockLevel = newStockLevel;
        reconcileStockStatus();
    }

    /** Simulated sale: drains stock and raises the 24h velocity counter. */
    public void recordSale(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("order quantity must be positive");
        }
        if (quantity > stockLevel) {
            throw new IllegalStateException(
                    "insufficient stock for " + sku + ": requested " + quantity + ", available " + stockLevel);
        }
        this.stockLevel -= quantity;
        this.demandVelocity += quantity;
        reconcileStockStatus();
    }

    /** Simulated inbound shipment, applied when a reorder suggestion is accepted. */
    public void receiveShipment(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("shipment quantity must be positive");
        }
        this.stockLevel += quantity;
        reconcileStockStatus();
    }

    /** Publishes an approved price. Only ever called from an accepted pricing suggestion. */
    public void applyApprovedPrice(BigDecimal newPrice) {
        if (newPrice == null || newPrice.signum() <= 0) {
            throw new IllegalArgumentException("approved price must be positive");
        }
        this.currentPrice = scale(newPrice);
    }

    public void markPriceReviewPending() {
        transitionTo(ProductStatus.PRICE_REVIEW_PENDING);
    }

    /** Returns to ACTIVE once no pricing decision is outstanding, unless stock ran dry. */
    public void clearPriceReview() {
        if (stockLevel == 0) {
            transitionTo(ProductStatus.OUT_OF_STOCK);
        } else {
            transitionTo(ProductStatus.ACTIVE);
        }
    }

    public boolean isBelowReorderThreshold() {
        return stockLevel < reorderThreshold;
    }

    /**
     * Whether replenishment is genuinely warranted, which is a broader question than being below
     * the reorder threshold.
     *
     * <p>A demand spike can make comfortable-looking stock inadequate: 60 units is far above a
     * threshold of 12, but at 31 sales a day it runs out four days before a 7-day resupply lands.
     * Conversely a slow mover sitting above its threshold needs nothing, and asking for a
     * recommendation anyway produces a suggestion for a unit or two that only wastes a
     * merchandiser's attention.
     */
    public boolean needsReplenishment(int leadTimeDays) {
        if (isBelowReorderThreshold()) {
            return true;
        }
        if (demandVelocity <= 0) {
            return false;
        }
        return stockLevel < demandVelocity * leadTimeDays;
    }

    private void reconcileStockStatus() {
        if (stockLevel == 0) {
            transitionTo(ProductStatus.OUT_OF_STOCK);
        } else if (status == ProductStatus.OUT_OF_STOCK) {
            transitionTo(ProductStatus.ACTIVE);
        }
    }

    private void transitionTo(ProductStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "illegal product transition " + status + " -> " + target + " for " + sku);
        }
        this.status = target;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public String getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public int getStockLevel() {
        return stockLevel;
    }

    public int getReorderThreshold() {
        return reorderThreshold;
    }

    public void setReorderThreshold(int reorderThreshold) {
        this.reorderThreshold = reorderThreshold;
    }

    public int getDemandVelocity() {
        return demandVelocity;
    }

    public void setDemandVelocity(int demandVelocity) {
        this.demandVelocity = demandVelocity;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice == null ? null : scale(costPrice);
    }

    public BigDecimal getMarginFloor() {
        return marginFloor;
    }

    public void setMarginFloor(BigDecimal marginFloor) {
        this.marginFloor = marginFloor == null ? null : scale(marginFloor);
    }

    public String getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(String supplierId) {
        this.supplierId = supplierId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
