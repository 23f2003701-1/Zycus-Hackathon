package com.stockpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;

public interface PricingSuggestionRepository extends JpaRepository<PricingSuggestion, Long> {

    /**
     * Idempotency guard for the agentic loop: a repeated trigger on the same product must not
     * pile up duplicate pending recommendations.
     */
    boolean existsByProductIdAndTriggerReasonAndStatus(String productId,
                                                       TriggerReason triggerReason,
                                                       SuggestionStatus status);

    List<PricingSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus status);

    List<PricingSuggestion> findByProductIdOrderByCreatedAtDesc(String productId);

    List<PricingSuggestion> findByProductIdAndTriggerReasonAndStatus(String productId,
                                                                     TriggerReason triggerReason,
                                                                     SuggestionStatus status);

    boolean existsByProductIdAndStatus(String productId, SuggestionStatus status);
}
