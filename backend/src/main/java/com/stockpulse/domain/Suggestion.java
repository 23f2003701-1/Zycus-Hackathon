package com.stockpulse.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;

/**
 * Everything a pricing and a reorder suggestion have in common: who it is for, why it exists,
 * how confident the advisor was, and where it sits in the approval lifecycle.
 */
@MappedSuperclass
public abstract class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false, length = 2000)
    private String reasoning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionStatus status = SuggestionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason", nullable = false, length = 20)
    private TriggerReason triggerReason;

    /**
     * Which advisor produced this - the AI strategy or the rule-based baseline. Makes an
     * LLM fallback visible in the UI instead of indistinguishable from a successful AI call.
     */
    @Column(name = "generated_by", nullable = false, length = 40)
    private String generatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected Suggestion() {
        // for JPA
    }

    protected Suggestion(Product product, double confidence, String reasoning,
                         TriggerReason triggerReason, String generatedBy) {
        this.product = product;
        this.confidence = clampConfidence(confidence);
        this.reasoning = truncate(reasoning);
        this.triggerReason = triggerReason;
        this.generatedBy = generatedBy;
        this.status = SuggestionStatus.PENDING;
    }

    /**
     * Applies the merchandiser's decision. Side effects on the product live in the
     * subclasses, so accepting a suggestion is a single call the service cannot get half-right.
     */
    public void decide(SuggestionStatus decision) {
        if (!status.canTransitionTo(decision)) {
            throw new IllegalStateException(
                    "suggestion " + id + " is already " + status + ", cannot move to " + decision);
        }
        this.status = decision;
        this.decidedAt = Instant.now();
        if (decision == SuggestionStatus.ACCEPTED) {
            applyToProduct();
        }
    }

    /** What accepting this suggestion does to the product. */
    protected abstract void applyToProduct();

    private static double clampConfidence(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2000 ? value : value.substring(0, 1997) + "...";
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    public SuggestionStatus getStatus() {
        return status;
    }

    public TriggerReason getTriggerReason() {
        return triggerReason;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
