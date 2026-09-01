package com.stockpulse.engine;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.engine.rule.RuleBasedPricingAdvisor;

/**
 * Resolves the advisor to use, right now, for whoever is asking.
 *
 * <p>Two properties make this the runtime-switching mechanism rather than a lookup table:
 * every implementation is discovered by Spring and indexed by {@link PricingAdvisor#name()}, and
 * the active name is re-read from {@link CommerceProperties} on <em>every</em> resolve. Switching
 * strategies is therefore a field write, with no bean rewiring and no restart. Because the HTTP
 * endpoints and the async agentic loop both resolve through here, a switch takes effect on both
 * paths at once.
 */
@Component
public class CommerceAdvisorRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommerceAdvisorRegistry.class);

    private final Map<String, PricingAdvisor> pricingAdvisors;
    private final Map<String, ReorderAdvisor> reorderAdvisors;
    private final CommerceProperties properties;

    public CommerceAdvisorRegistry(List<PricingAdvisor> pricingAdvisors,
                                   List<ReorderAdvisor> reorderAdvisors,
                                   CommerceProperties properties) {
        this.pricingAdvisors = index(pricingAdvisors, PricingAdvisor::name);
        this.reorderAdvisors = index(reorderAdvisors, ReorderAdvisor::name);
        this.properties = properties;
        log.info("Commerce engine registered pricing advisors {} and reorder advisors {}; active strategy is '{}'",
                this.pricingAdvisors.keySet(), this.reorderAdvisors.keySet(), properties.getActiveStrategy());
    }

    public PricingAdvisor activePricingAdvisor() {
        return resolve(pricingAdvisors, properties.getActiveStrategy(), "pricing");
    }

    public ReorderAdvisor activeReorderAdvisor() {
        return resolve(reorderAdvisors, properties.getActiveStrategy(), "reorder");
    }

    /** The always-available deterministic advisor used when an AI call cannot be trusted. */
    public PricingAdvisor fallbackPricingAdvisor() {
        return pricingAdvisors.get(RuleBasedPricingAdvisor.NAME);
    }

    public ReorderAdvisor fallbackReorderAdvisor() {
        return reorderAdvisors.get(RuleBasedPricingAdvisor.NAME);
    }

    public String activeStrategy() {
        return properties.getActiveStrategy();
    }

    public Set<String> availableStrategies() {
        Set<String> all = new TreeSet<>(pricingAdvisors.keySet());
        all.addAll(reorderAdvisors.keySet());
        return all;
    }

    /** Runtime switch. Rejects unknown names so a typo cannot silently disable the AI advisor. */
    public void activate(String strategy) {
        if (!availableStrategies().contains(strategy)) {
            throw new IllegalArgumentException(
                    "unknown strategy '" + strategy + "'; available: " + availableStrategies());
        }
        String previous = properties.getActiveStrategy();
        properties.setActiveStrategy(strategy);
        log.info("Active commerce strategy switched from '{}' to '{}'", previous, strategy);
    }

    private static <T> Map<String, T> index(List<T> advisors, Function<T, String> nameOf) {
        return advisors.stream()
                .sorted(Comparator.comparing(nameOf))
                .collect(Collectors.toMap(nameOf, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("duplicate advisor name: " + nameOf.apply(a));
                }, LinkedHashMap::new));
    }

    private static <T> T resolve(Map<String, T> advisors, String requested, String kind) {
        T advisor = advisors.get(requested);
        if (advisor != null) {
            return advisor;
        }
        T fallback = advisors.get(RuleBasedPricingAdvisor.NAME);
        if (fallback == null) {
            throw new IllegalStateException("no " + kind + " advisor available for '" + requested + "'");
        }
        log.warn("No {} advisor named '{}'; falling back to '{}'", kind, requested, RuleBasedPricingAdvisor.NAME);
        return fallback;
    }
}
