package com.stockpulse.repository;

import org.springframework.data.jpa.domain.Specification;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ProductStatus;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    /** Null-tolerant so {@code GET /products?status=&category=} can omit either filter. */
    public static Specification<Product> matching(ProductStatus status, Category category) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (status != null) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            if (category != null) {
                predicate = cb.and(predicate, cb.equal(root.get("category"), category));
            }
            return predicate;
        };
    }

    public static Specification<Product> belowReorderThreshold() {
        return (root, query, cb) -> cb.lessThan(root.get("stockLevel"), root.get("reorderThreshold"));
    }
}
