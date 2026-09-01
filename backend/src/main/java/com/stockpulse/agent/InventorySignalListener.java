package com.stockpulse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.stockpulse.config.AsyncConfig;
import com.stockpulse.event.InventorySignalEvent;

/**
 * The trigger half of the agentic loop.
 *
 * <p>Three annotations carry the design:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} - the loop only ever reasons about stock
 *       that actually persisted. Reacting inside the transaction risks recommending against a state
 *       that is about to roll back.</li>
 *   <li>{@code @Async} on a dedicated executor - the stock and order endpoints have already
 *       returned by the time this runs, so an LLM call taking several seconds never shows up in a
 *       caller's response time.</li>
 *   <li>{@code @ConditionalOnProperty} - lets the loop be switched off, which keeps the deterministic
 *       API tests free of background writes.</li>
 * </ul>
 *
 * <p>A scheduled poller would be the wrong shape here: the loop should fire because inventory
 * changed, not because a timer elapsed.
 */
@Component
@ConditionalOnProperty(name = "commerce.agentic-loop-enabled", havingValue = "true", matchIfMissing = true)
public class InventorySignalListener {

    private static final Logger log = LoggerFactory.getLogger(InventorySignalListener.class);

    private final AgenticRecommendationService recommendations;

    public InventorySignalListener(AgenticRecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @Async(AsyncConfig.COMMERCE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInventorySignal(InventorySignalEvent event) {
        log.debug("Agentic loop observed {} on {}", event.origin(), event.productId());
        try {
            recommendations.evaluate(event.productId());
        } catch (RuntimeException ex) {
            // Nothing is waiting on this thread, so an escape here would vanish silently.
            log.error("Agentic loop failed for {} after {}", event.productId(), event.origin(), ex);
        }
    }
}
