package com.stockpulse.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stockpulse.api.dto.CommerceStrategyRequest;
import com.stockpulse.api.dto.CommerceStrategyResponse;
import com.stockpulse.engine.CommerceAdvisorRegistry;

import jakarta.validation.Valid;

/**
 * Runtime strategy switching. Flipping the active advisor here takes effect on the very next
 * recommendation, on both the HTTP and the async path, with no redeploy and no restart.
 */
@RestController
@RequestMapping("/admin/commerce-strategy")
public class CommerceStrategyController {

    private final CommerceAdvisorRegistry registry;

    public CommerceStrategyController(CommerceAdvisorRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public CommerceStrategyResponse current() {
        return new CommerceStrategyResponse(registry.activeStrategy(), registry.availableStrategies());
    }

    @PatchMapping
    public CommerceStrategyResponse switchTo(@Valid @RequestBody CommerceStrategyRequest request) {
        registry.activate(request.activeStrategy());
        return new CommerceStrategyResponse(registry.activeStrategy(), registry.availableStrategies());
    }
}
