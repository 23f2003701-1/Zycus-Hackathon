package com.stockpulse.ai;

/**
 * Every way an LLM call can let us down - transport failure, timeout, quota exhaustion, blank
 * body, unparseable JSON, or a recommendation outside sane bounds - surfaces as this one type,
 * so the caller has exactly one thing to catch before falling back to the rule-based baseline.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
