package com.stockpulse.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.engine.CommerceAdvisorRegistry;

/**
 * The streaming path has to honour the same promise the async path does: whatever happens to the
 * model, a decision reaches the merchandiser. These tests pin the event sequence for each way that
 * can play out, because the sequence is the contract the console is written against.
 *
 * <p>No API key is configured here, so selecting the AI advisor is a reliable way to make the
 * model leg fail - which is exactly the case worth asserting.
 */
@SpringBootTest(properties = {
        "commerce.agentic-loop-enabled=false",
        "llm.api-key="
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PricingStreamServiceTest {

    @Autowired
    private PricingStreamService streamService;

    @Autowired
    private CommerceAdvisorRegistry registry;

    @Autowired
    private SuggestionService suggestions;

    /** Records the conversation instead of writing it to a socket. */
    private static final class RecordingSink implements StreamSink {

        private final List<String> events = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private boolean completed;
        private Throwable error;

        @Override
        public void send(String event, Object payload) {
            events.add(event);
            payloads.add(payload);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable cause) {
            completed = true;
            error = cause;
        }

        @SuppressWarnings("unchecked")
        <T> T payloadOf(String event) {
            return (T) payloads.get(events.indexOf(event));
        }
    }

    private RecordingSink streamFor(String productId, String strategy) {
        registry.activate(strategy);
        RecordingSink sink = new RecordingSink();
        streamService.streamPricing(productId, sink);
        return sink;
    }

    @Test
    void aNonStreamingAdvisorSaysSoRatherThanFakingATokenStream() {
        RecordingSink sink = streamFor("PRD-001", "ruleBased");

        assertThat(sink.events).containsExactly("status", "suggestion");
        assertThat(sink.<Map<String, Object>>payloadOf("status"))
                .containsEntry("phase", "computing")
                .containsEntry("advisor", "ruleBased")
                .containsEntry("streaming", false);
        assertThat(sink.completed).isTrue();
        assertThat(sink.error).isNull();
    }

    @Test
    void aStreamAlwaysEndsWithAPersistedPendingSuggestion() {
        RecordingSink sink = streamFor("PRD-002", "ruleBased");

        PricingSuggestionResponse suggestion = sink.payloadOf("suggestion");
        assertThat(suggestion.status()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(suggestion.triggerReason()).isEqualTo(TriggerReason.MANUAL);
        assertThat(suggestion.id()).isNotNull();

        // The event is not a preview: it is readable through the ordinary query path.
        assertThat(suggestions.pricingForProduct("PRD-002"))
                .extracting(PricingSuggestionResponse::id)
                .contains(suggestion.id());
    }

    @Test
    void aFailingModelIsAnnouncedAsAFallbackAndStillProducesASuggestion() {
        RecordingSink sink = streamFor("PRD-003", "aiAdvisor");

        assertThat(sink.events).containsExactly("status", "fallback", "suggestion");

        assertThat(sink.<Map<String, Object>>payloadOf("status"))
                .containsEntry("advisor", "aiAdvisor")
                .containsEntry("streaming", true);

        Map<String, Object> fallback = sink.payloadOf("fallback");
        assertThat(fallback).containsEntry("advisor", "ruleBased");
        assertThat((String) fallback.get("reason")).contains("no API key configured");

        // Attribution survives the fallback: nobody should read this as the model's argument.
        PricingSuggestionResponse suggestion = sink.payloadOf("suggestion");
        assertThat(suggestion.generatedBy()).isEqualTo("ruleBased");
        assertThat(sink.error).isNull();
    }

    @Test
    void theStreamedSuggestionPutsTheProductIntoPriceReviewLikeAnyOther() {
        streamFor("PRD-004", "ruleBased");

        assertThat(suggestions.listPricing(SuggestionStatus.PENDING))
                .anyMatch(suggestion -> suggestion.productId().equals("PRD-004"));
    }
}
