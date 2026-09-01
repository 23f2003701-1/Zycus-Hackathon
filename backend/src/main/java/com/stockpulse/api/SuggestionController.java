package com.stockpulse.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.api.dto.SuggestionDecisionRequest;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.service.SuggestionService;

import jakarta.validation.Valid;

/**
 * The human checkpoint. Accepting a pricing suggestion is the only way a live price changes;
 * accepting a reorder suggestion is the only way stock arrives.
 */
@RestController
public class SuggestionController {

    private final SuggestionService suggestionService;

    public SuggestionController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/pricing-suggestions")
    public List<PricingSuggestionResponse> listPricing(
            @RequestParam(required = false) SuggestionStatus status) {
        return suggestionService.listPricing(status);
    }

    @GetMapping("/reorder-suggestions")
    public List<ReorderSuggestionResponse> listReorder(
            @RequestParam(required = false) SuggestionStatus status) {
        return suggestionService.listReorder(status);
    }

    @PatchMapping("/pricing-suggestions/{id}")
    public PricingSuggestionResponse decidePricing(@PathVariable Long id,
                                                   @Valid @RequestBody SuggestionDecisionRequest request) {
        return suggestionService.decidePricing(id, request.requireDecision());
    }

    @PatchMapping("/reorder-suggestions/{id}")
    public ReorderSuggestionResponse decideReorder(@PathVariable Long id,
                                                   @Valid @RequestBody SuggestionDecisionRequest request) {
        return suggestionService.decideReorder(id, request.requireDecision());
    }
}
