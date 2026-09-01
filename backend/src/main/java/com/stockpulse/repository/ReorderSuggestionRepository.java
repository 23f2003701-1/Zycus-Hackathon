package com.stockpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;

public interface ReorderSuggestionRepository extends JpaRepository<ReorderSuggestion, Long> {

    boolean existsByProductIdAndTriggerReasonAndStatus(String productId,
                                                       TriggerReason triggerReason,
                                                       SuggestionStatus status);

    List<ReorderSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus status);

    List<ReorderSuggestion> findByProductIdOrderByCreatedAtDesc(String productId);

    boolean existsByProductIdAndStatus(String productId, SuggestionStatus status);
}
