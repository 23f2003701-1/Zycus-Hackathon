package com.stockpulse.domain;

/**
 * Suggestion lifecycle: {@code PENDING -> ACCEPTED | REJECTED}. Terminal states are final -
 * a decided suggestion can never be re-decided, which is what keeps the human checkpoint honest.
 */
public enum SuggestionStatus {

    PENDING,
    ACCEPTED,
    REJECTED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canTransitionTo(SuggestionStatus target) {
        return this == PENDING && target.isTerminal();
    }
}
