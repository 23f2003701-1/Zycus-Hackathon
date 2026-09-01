package com.stockpulse.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.api.error.ResourceNotFoundException;
import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.CommerceAdvisorRegistry;
import com.stockpulse.engine.CommerceContext;
import com.stockpulse.engine.PricingAdvisor;
import com.stockpulse.engine.PricingRecommendation;
import com.stockpulse.engine.ReorderAdvisor;
import com.stockpulse.engine.ReorderRecommendation;
import com.stockpulse.repository.PricingSuggestionRepository;
import com.stockpulse.repository.ReorderSuggestionRepository;

/**
 * Turns advisor recommendations into persisted suggestions, and applies merchandising decisions.
 *
 * <p>The generate methods are the single entry point shared by both callers the brief calls out:
 * the on-demand HTTP endpoints pass {@code MANUAL}, and the agentic loop passes
 * {@code INVENTORY_LOW} or {@code DEMAND_SPIKE}. Neither caller knows which advisor ran.
 */
@Service
@Transactional
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final PricingSuggestionRepository pricingSuggestions;
    private final ReorderSuggestionRepository reorderSuggestions;
    private final CommerceAdvisorRegistry registry;
    private final CommerceContextFactory contextFactory;
    private final ProductService productService;

    public SuggestionService(PricingSuggestionRepository pricingSuggestions,
                             ReorderSuggestionRepository reorderSuggestions,
                             CommerceAdvisorRegistry registry,
                             CommerceContextFactory contextFactory,
                             ProductService productService) {
        this.pricingSuggestions = pricingSuggestions;
        this.reorderSuggestions = reorderSuggestions;
        this.registry = registry;
        this.contextFactory = contextFactory;
        this.productService = productService;
    }

    // --- Generation -------------------------------------------------------------------------

    public PricingSuggestionResponse suggestPricing(String productId, TriggerReason trigger) {
        return PricingSuggestionResponse.from(generatePricing(productService.require(productId), trigger));
    }

    public ReorderSuggestionResponse suggestReorder(String productId, TriggerReason trigger) {
        return ReorderSuggestionResponse.from(generateReorder(productService.require(productId), trigger));
    }

    /**
     * Skips generation when an undecided suggestion for the same product and trigger already
     * exists, so a burst of orders cannot bury a merchandiser in duplicates.
     */
    public Optional<PricingSuggestion> generatePricingIfAbsent(Product product, TriggerReason trigger) {
        if (pricingSuggestions.existsByProductIdAndTriggerReasonAndStatus(
                product.getId(), trigger, SuggestionStatus.PENDING)) {
            log.debug("Skipping duplicate pending {} pricing suggestion for {}", trigger, product.getId());
            return Optional.empty();
        }
        return Optional.of(generatePricing(product, trigger));
    }

    public Optional<ReorderSuggestion> generateReorderIfAbsent(Product product, TriggerReason trigger) {
        if (reorderSuggestions.existsByProductIdAndTriggerReasonAndStatus(
                product.getId(), trigger, SuggestionStatus.PENDING)) {
            log.debug("Skipping duplicate pending {} reorder suggestion for {}", trigger, product.getId());
            return Optional.empty();
        }
        return Optional.of(generateReorder(product, trigger));
    }

    /**
     * Builds an advisor context without persisting anything, for callers that must run the advisor
     * outside a transaction - see {@link PricingStreamService}. The returned product is detached;
     * advisors only read it, and holding a database transaction open across a multi-second model
     * stream would be a far worse trade than working with a snapshot.
     */
    @Transactional(readOnly = true)
    public CommerceContext contextFor(String productId, TriggerReason trigger) {
        return contextFactory.create(productService.require(productId), trigger);
    }

    /**
     * Persists a recommendation an external caller already obtained. The only way to record a
     * suggestion without generating it, so every write still lands through the same
     * {@link #persist} path with the same state transition.
     */
    public PricingSuggestionResponse persistPricing(String productId, TriggerReason trigger,
                                                    PricingRecommendation recommendation) {
        return PricingSuggestionResponse.from(
                persist(productService.require(productId), trigger, recommendation));
    }

    private PricingSuggestion generatePricing(Product product, TriggerReason trigger) {
        CommerceContext context = contextFactory.create(product, trigger);
        return persist(product, trigger, recommendPriceWithFallback(context));
    }

    private PricingSuggestion persist(Product product, TriggerReason trigger,
                                      PricingRecommendation recommendation) {
        PricingSuggestion suggestion = new PricingSuggestion(
                product,
                recommendation.recommendedPrice(),
                recommendation.confidence(),
                recommendation.reasoning(),
                trigger,
                recommendation.source());

        // A price question is now outstanding, so the product enters review.
        product.markPriceReviewPending();

        PricingSuggestion saved = pricingSuggestions.save(suggestion);
        log.info("Pricing suggestion {} for {} [{}]: {} -> {} ({}), confidence {}, by {}",
                saved.getId(), product.getId(), trigger, saved.getCurrentPrice(),
                saved.getRecommendedPrice(), saved.getDirection(), saved.getConfidence(),
                saved.getGeneratedBy());

        // The seed leaves an INITIAL card on PRD-003 so the console is not empty at boot. When the
        // real inventory-low / spike path later queues its own pricing suggestion, that INITIAL
        // card is stale - and if the seed call fell back to rules while the live call used AI,
        // the queue showed both engines for the same product. Auto-triggered answers supersede it.
        if (trigger.isAutoTriggered()) {
            dismissPendingInitialPricing(product);
        }

        return saved;
    }

    private void dismissPendingInitialPricing(Product product) {
        List<PricingSuggestion> stale = pricingSuggestions.findByProductIdAndTriggerReasonAndStatus(
                product.getId(), TriggerReason.INITIAL, SuggestionStatus.PENDING);
        for (PricingSuggestion initial : stale) {
            initial.decide(SuggestionStatus.REJECTED);
            log.info("Superseded seeded INITIAL pricing suggestion {} for {} after an auto-triggered recommendation",
                    initial.getId(), product.getId());
        }
        settlePriceReview(product);
    }

    private ReorderSuggestion generateReorder(Product product, TriggerReason trigger) {
        CommerceContext context = contextFactory.create(product, trigger);
        ReorderRecommendation recommendation = recommendReorderWithFallback(context);

        ReorderSuggestion saved = reorderSuggestions.save(new ReorderSuggestion(
                product,
                recommendation.recommendedQuantity(),
                recommendation.suggestedLeadTimeDays(),
                recommendation.confidence(),
                recommendation.reasoning(),
                trigger,
                recommendation.source()));

        log.info("Reorder suggestion {} for {} [{}]: {} units, lead time {}d, confidence {}, by {}",
                saved.getId(), product.getId(), trigger, saved.getRecommendedQuantity(),
                saved.getSuggestedLeadTimeDays(), saved.getConfidence(), saved.getGeneratedBy());
        return saved;
    }

    /**
     * Resilience boundary. Any failure inside the active advisor - timeout, quota, unparseable
     * JSON, an out-of-bounds price - degrades to the deterministic baseline instead of dropping
     * the recommendation. This is the guarantee the async path depends on.
     */
    private PricingRecommendation recommendPriceWithFallback(CommerceContext context) {
        PricingAdvisor active = registry.activePricingAdvisor();
        try {
            return active.recommendPrice(context);
        } catch (RuntimeException ex) {
            PricingAdvisor fallback = registry.fallbackPricingAdvisor();
            log.warn("Pricing advisor '{}' failed for {} ({}); falling back to '{}'",
                    active.name(), context.product().getId(), ex.getMessage(), fallback.name());
            return fallback.recommendPrice(context);
        }
    }

    private ReorderRecommendation recommendReorderWithFallback(CommerceContext context) {
        ReorderAdvisor active = registry.activeReorderAdvisor();
        try {
            return active.recommendReorder(context);
        } catch (RuntimeException ex) {
            ReorderAdvisor fallback = registry.fallbackReorderAdvisor();
            log.warn("Reorder advisor '{}' failed for {} ({}); falling back to '{}'",
                    active.name(), context.product().getId(), ex.getMessage(), fallback.name());
            return fallback.recommendReorder(context);
        }
    }

    // --- Decisions --------------------------------------------------------------------------

    public PricingSuggestionResponse decidePricing(Long id, SuggestionStatus decision) {
        PricingSuggestion suggestion = pricingSuggestions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("pricing suggestion", id));

        suggestion.decide(decision);
        settlePriceReview(suggestion.getProduct());

        log.info("Pricing suggestion {} for {} was {} - price is now {}",
                id, suggestion.getProduct().getId(), decision, suggestion.getProduct().getCurrentPrice());
        return PricingSuggestionResponse.from(suggestion);
    }

    public ReorderSuggestionResponse decideReorder(Long id, SuggestionStatus decision) {
        ReorderSuggestion suggestion = reorderSuggestions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("reorder suggestion", id));

        suggestion.decide(decision);

        log.info("Reorder suggestion {} for {} was {} - stock is now {}",
                id, suggestion.getProduct().getId(), decision, suggestion.getProduct().getStockLevel());
        return ReorderSuggestionResponse.from(suggestion);
    }

    /**
     * A product leaves PRICE_REVIEW_PENDING only once no pricing question is still outstanding,
     * which handles the case where two triggers each queued a suggestion and only one was decided.
     */
    private void settlePriceReview(Product product) {
        boolean stillPending = pricingSuggestions.existsByProductIdAndStatus(
                product.getId(), SuggestionStatus.PENDING);
        if (!stillPending) {
            product.clearPriceReview();
        }
    }

    // --- Queries ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PricingSuggestionResponse> listPricing(SuggestionStatus status) {
        // Ordering matters even unfiltered: the console polls this list, and an unordered result
        // would let rows reshuffle underneath a merchandiser mid-click.
        List<PricingSuggestion> found = status == null
                ? pricingSuggestions.findAll(NEWEST_FIRST)
                : pricingSuggestions.findByStatusOrderByCreatedAtDesc(status);
        return found.stream().map(PricingSuggestionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestionResponse> listReorder(SuggestionStatus status) {
        List<ReorderSuggestion> found = status == null
                ? reorderSuggestions.findAll(NEWEST_FIRST)
                : reorderSuggestions.findByStatusOrderByCreatedAtDesc(status);
        return found.stream().map(ReorderSuggestionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PricingSuggestionResponse> pricingForProduct(String productId) {
        return pricingSuggestions.findByProductIdOrderByCreatedAtDesc(productId).stream()
                .map(PricingSuggestionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestionResponse> reorderForProduct(String productId) {
        return reorderSuggestions.findByProductIdOrderByCreatedAtDesc(productId).stream()
                .map(ReorderSuggestionResponse::from)
                .toList();
    }
}
