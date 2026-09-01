package com.stockpulse.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.rule.RuleBasedPricingAdvisor;
import com.stockpulse.engine.rule.RuleBasedReorderAdvisor;

class CommerceAdvisorRegistryTest {

    /** Stands in for the AI advisor that T-3 will register. */
    private static class StubPricingAdvisor implements PricingAdvisor {
        @Override
        public String name() {
            return "aiAdvisor";
        }

        @Override
        public PricingRecommendation recommendPrice(CommerceContext context) {
            return new PricingRecommendation(new BigDecimal("1.00"), 0.9, "stub", name());
        }
    }

    private CommerceAdvisorRegistry registry(CommerceProperties properties) {
        return new CommerceAdvisorRegistry(
                List.of(new RuleBasedPricingAdvisor(properties), new StubPricingAdvisor()),
                List.of(new RuleBasedReorderAdvisor(properties)),
                properties);
    }

    @Test
    void advisorsAreDiscoveredAndIndexedByName() {
        var registry = registry(new CommerceProperties());

        assertThat(registry.availableStrategies()).containsExactly("aiAdvisor", "ruleBased");
    }

    @Test
    void defaultConfigurationPrefersTheAiAdvisorWhenOneIsRegistered() {
        var properties = new CommerceProperties();
        properties.setActiveStrategy("aiAdvisor");

        assertThat(registry(properties).activePricingAdvisor().name()).isEqualTo("aiAdvisor");
    }

    @Test
    void defaultsToTheRuleBasedAdvisor() {
        var registry = registry(new CommerceProperties());

        assertThat(registry.activePricingAdvisor().name()).isEqualTo("ruleBased");
    }

    @Test
    void switchingStrategyTakesEffectOnTheNextResolveWithoutRewiring() {
        var registry = registry(new CommerceProperties());

        registry.activate("aiAdvisor");

        assertThat(registry.activeStrategy()).isEqualTo("aiAdvisor");
        assertThat(registry.activePricingAdvisor().name()).isEqualTo("aiAdvisor");
    }

    @Test
    void anUnknownStrategyIsRejectedRatherThanSilentlyIgnored() {
        var registry = registry(new CommerceProperties());

        assertThatThrownBy(() -> registry.activate("competitorAware"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown strategy");

        assertThat(registry.activePricingAdvisor().name()).isEqualTo("ruleBased");
    }

    @Test
    void aStrategyWithNoReorderImplementationStillResolvesToTheBaseline() {
        var properties = new CommerceProperties();
        properties.setActiveStrategy("aiAdvisor");
        var registry = registry(properties);

        assertThat(registry.activePricingAdvisor().name()).isEqualTo("aiAdvisor");
        assertThat(registry.activeReorderAdvisor().name()).isEqualTo("ruleBased");
    }

    @Test
    void theFallbackAdvisorIsAlwaysTheDeterministicOne() {
        var registry = registry(new CommerceProperties());
        registry.activate("aiAdvisor");

        assertThat(registry.fallbackPricingAdvisor().name()).isEqualTo("ruleBased");
        assertThat(registry.fallbackReorderAdvisor().name()).isEqualTo("ruleBased");
    }
}
