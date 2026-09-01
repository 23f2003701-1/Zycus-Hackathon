package com.stockpulse.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ProductStatus;

public record ProductResponse(
        String id,
        String sku,
        String name,
        Category category,
        BigDecimal currentPrice,
        int stockLevel,
        int reorderThreshold,
        int demandVelocity,
        ProductStatus status,
        boolean belowReorderThreshold,
        BigDecimal costPrice,
        BigDecimal marginFloor,
        String supplierId,
        Instant updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getCategory(),
                product.getCurrentPrice(),
                product.getStockLevel(),
                product.getReorderThreshold(),
                product.getDemandVelocity(),
                product.getStatus(),
                product.isBelowReorderThreshold(),
                product.getCostPrice(),
                product.getMarginFloor(),
                product.getSupplierId(),
                product.getUpdatedAt());
    }
}
