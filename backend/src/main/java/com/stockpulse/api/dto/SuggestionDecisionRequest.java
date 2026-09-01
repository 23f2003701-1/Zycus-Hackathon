package com.stockpulse.api.dto;

import com.stockpulse.domain.SuggestionStatus;

import jakarta.validation.constraints.NotNull;

/**
 * The human checkpoint. A price only ever moves because this arrived with ACCEPTED.
 */
public record SuggestionDecisionRequest(@NotNull SuggestionStatus status) {

    public SuggestionStatus requireDecision() {
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
        }
        return status;
    }
}
