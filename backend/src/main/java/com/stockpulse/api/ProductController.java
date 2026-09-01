package com.stockpulse.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockpulse.api.dto.CreateOrderRequest;
import com.stockpulse.api.dto.CreateProductRequest;
import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ProductResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.api.dto.UpdateStockRequest;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.service.ProductService;
import com.stockpulse.service.SuggestionService;

import jakarta.validation.Valid;

/**
 * HTTP edge for the catalog. Deserialise, delegate, serialise - no business rules here, which is
 * what allows the agentic loop to reach the same behaviour without going through a controller.
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final SuggestionService suggestionService;

    public ProductController(ProductService productService, SuggestionService suggestionService) {
        this.productService = productService;
        this.suggestionService = suggestionService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.created(URI.create("/products/" + created.id())).body(created);
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) ProductStatus status,
                                      @RequestParam(required = false) Category category) {
        return productService.list(status, category);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable String id) {
        return productService.get(id);
    }

    /** Returns as soon as stock is written; recommendation generation is asynchronous. */
    @PatchMapping("/{id}/stock")
    public ProductResponse updateStock(@PathVariable String id,
                                       @Valid @RequestBody UpdateStockRequest request) {
        return productService.adjustStock(id, request.stockLevel());
    }

    /** Simulated sale. Also returns immediately. */
    @PostMapping("/{id}/orders")
    public ProductResponse placeOrder(@PathVariable String id,
                                      @Valid @RequestBody(required = false) CreateOrderRequest request) {
        int quantity = request == null ? 1 : request.quantityOrDefault();
        return productService.recordOrder(id, quantity);
    }

    @PostMapping("/{id}/suggest-pricing")
    public PricingSuggestionResponse suggestPricing(@PathVariable String id) {
        return suggestionService.suggestPricing(id, TriggerReason.MANUAL);
    }

    @PostMapping("/{id}/suggest-reorder")
    public ReorderSuggestionResponse suggestReorder(@PathVariable String id) {
        return suggestionService.suggestReorder(id, TriggerReason.MANUAL);
    }

    @GetMapping("/{id}/pricing-suggestions")
    public List<PricingSuggestionResponse> pricingHistory(@PathVariable String id) {
        return suggestionService.pricingForProduct(id);
    }

    @GetMapping("/{id}/reorder-suggestions")
    public List<ReorderSuggestionResponse> reorderHistory(@PathVariable String id) {
        return suggestionService.reorderForProduct(id);
    }
}
