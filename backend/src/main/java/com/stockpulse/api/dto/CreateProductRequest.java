package com.stockpulse.api.dto;

import java.math.BigDecimal;

import com.stockpulse.domain.Category;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(

        @NotBlank @Size(max = 60) String sku,

        @NotBlank @Size(max = 160) String name,

        @NotNull Category category,

        @NotNull @DecimalMin(value = "0.01") BigDecimal currentPrice,

        @NotNull @Min(0) Integer stockLevel,

        @NotNull @Min(1) Integer reorderThreshold,

        // Sprint 2 fields, accepted now so the API contract does not have to change later.
        BigDecimal costPrice,
        BigDecimal marginFloor,
        @Size(max = 40) String supplierId) {
}
