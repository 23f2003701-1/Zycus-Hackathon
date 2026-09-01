package com.stockpulse.agent;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stockpulse.config.CommerceProperties;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.service.SuggestionService;

/**
 * The reasoning half of the agentic loop: given that something changed, decide what it means and
 * queue recommendations.
 *
 * <p>Observe (an inventory signal arrived) then reason (which triggers does this satisfy?) then act
 * (queue pricing and reorder suggestions) then checkpoint (a merchandiser approves). The system
 * proposes; it never publishes a price.
 *
 * <p>One handler covers both triggers rather than two independent listeners, because a single order
 * can legitimately satisfy both at once - draining stock below threshold while pushing velocity
 * past its peers. Two listeners would race on the same product and each generate a suggestion
 * unaware of the other; one pass evaluates both conditions against one consistent read.
 */
@Service
public class AgenticRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(AgenticRecommendationService.class);

    private final ProductRepository products;
    private final SuggestionService suggestions;
    private final CommerceProperties properties;

    public AgenticRecommendationService(ProductRepository products, SuggestionService suggestions,
                                        CommerceProperties properties) {
        this.products = products;
        this.suggestions = suggestions;
        this.properties = properties;
    }

    @Transactional
    public void evaluate(String productId) {
        Product product = products.findById(productId).orElse(null);
        if (product == null) {
            log.warn("Inventory signal for unknown product {}; nothing to evaluate", productId);
            return;
        }

        List<TriggerReason> triggers = classify(product);
        if (triggers.isEmpty()) {
            log.debug("No trigger fired for {} (stock {}, threshold {}, velocity {})",
                    productId, product.getStockLevel(), product.getReorderThreshold(),
                    product.getDemandVelocity());
            return;
        }

        // A trigger is always a pricing question, but not always a replenishment one: a spike on a
        // deeply stocked product needs a price opinion and nothing else. Queueing a reorder anyway
        // would put a one-unit suggestion in front of a merchandiser for no reason.
        boolean replenishmentWarranted = product.needsReplenishment(properties.getDefaultLeadTimeDays());

        for (TriggerReason trigger : triggers) {
            // Each type is generated independently so one failure cannot suppress the other, and
            // each is skipped if an undecided suggestion already covers this product and trigger.
            suggestions.generatePricingIfAbsent(product, trigger)
                    .ifPresent(s -> log.info("Agentic loop queued pricing suggestion {} for {} [{}]",
                            s.getId(), productId, trigger));

            if (replenishmentWarranted) {
                suggestions.generateReorderIfAbsent(product, trigger)
                        .ifPresent(s -> log.info("Agentic loop queued reorder suggestion {} for {} [{}]",
                                s.getId(), productId, trigger));
            }
        }
    }

    private List<TriggerReason> classify(Product product) {
        List<TriggerReason> triggers = new ArrayList<>(2);

        if (product.isBelowReorderThreshold()) {
            triggers.add(TriggerReason.INVENTORY_LOW);
        }

        double peerAverage = products.averagePeerDemandVelocity(product.getCategory(), product.getId());
        if (peerAverage > 0
                && product.getDemandVelocity() > peerAverage * properties.getDemandSpikeMultiplier()) {
            triggers.add(TriggerReason.DEMAND_SPIKE);
        }

        if (!triggers.isEmpty()) {
            log.info("Signal on {} classified as {} (stock {}/{}, velocity {} vs peer average {})",
                    product.getId(), triggers, product.getStockLevel(), product.getReorderThreshold(),
                    product.getDemandVelocity(), String.format("%.1f", peerAverage));
        }
        return triggers;
    }
}
