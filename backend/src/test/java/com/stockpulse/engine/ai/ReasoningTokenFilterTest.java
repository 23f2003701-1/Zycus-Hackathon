package com.stockpulse.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The filter is what stands between a merchandiser and watching raw JSON assemble itself, so these
 * tests are mostly about the boundaries a provider is free to split a stream on. Chunk sizes here
 * are deliberately hostile: real providers do split mid-word, mid-escape, and mid-key.
 */
class ReasoningTokenFilterTest {

    /** Feeds a document one character at a time - the worst case for any incremental scanner. */
    private static String characterByCharacter(String document) {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();
        StringBuilder emitted = new StringBuilder();
        for (char c : document.toCharArray()) {
            emitted.append(filter.accept(String.valueOf(c)));
        }
        return emitted.toString();
    }

    @Test
    void emitsOnlyTheReasoningProseAndNoneOfTheSurroundingJson() {
        String document = """
                {"recommendedPrice": 27.49, "confidence": 0.8, \
                "reasoning": "Stock is below threshold with 1.2 days of cover."}""";

        assertThat(characterByCharacter(document))
                .isEqualTo("Stock is below threshold with 1.2 days of cover.");
    }

    @Test
    void emitsNothingUntilTheReasoningKeyHasActuallyArrived() {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        assertThat(filter.accept("{\"recommendedPrice\": 27.49, ")).isEmpty();
        assertThat(filter.accept("\"confidence\": 0.8, \"reas")).isEmpty();
        assertThat(filter.accept("oning\": \"Raise ")).isEqualTo("Raise ");
        assertThat(filter.accept("the price.\"}")).isEqualTo("the price.");
    }

    @Test
    void survivesAnEscapeSequenceSplitAcrossChunks() {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        filter.accept("{\"reasoning\": \"She said ");
        // The backslash arrives with nothing after it: the scanner must wait rather than emit it.
        assertThat(filter.accept("\\")).isEmpty();
        assertThat(filter.accept("\"stock out\\\" and left.\"}")).isEqualTo("\"stock out\" and left.");
    }

    @Test
    void decodesNewlinesAndUnicodeEscapes() {
        String document = "{\"reasoning\": \"Line one.\\nLine two \\u2014 dashed.\"}";

        assertThat(characterByCharacter(document)).isEqualTo("Line one.\nLine two \u2014 dashed.");
    }

    @Test
    void waitsForAllFourHexDigitsOfAUnicodeEscape() {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        filter.accept("{\"reasoning\": \"cost ");
        assertThat(filter.accept("\\u20")).isEmpty();
        assertThat(filter.accept("AC5\"}")).isEqualTo("\u20AC5");
    }

    @Test
    void stopsAtTheClosingQuoteAndIgnoresEverythingAfterIt() {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        assertThat(filter.accept("{\"reasoning\": \"Hold.\", \"recommendedPrice\": 20}"))
                .isEqualTo("Hold.");
        assertThat(filter.isComplete()).isTrue();
        assertThat(filter.accept(", \"trailing\": \"noise\"")).isEmpty();
    }

    @Test
    void aDocumentWithoutReasoningEmitsNothingButStillCapturesTheRaw() {
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        assertThat(filter.accept("{\"recommendedPrice\": 27.49}")).isEmpty();
        assertThat(filter.isComplete()).isFalse();
        assertThat(filter.raw()).isEqualTo("{\"recommendedPrice\": 27.49}");
    }

    @Test
    void theRawTextIsPreservedIntactForTheRealParserThatRunsAfterwards() {
        String document = "{\"reasoning\": \"Escaped \\\"quotes\\\" kept.\", \"recommendedPrice\": 27.49}";
        ReasoningTokenFilter filter = new ReasoningTokenFilter();

        for (char c : document.toCharArray()) {
            filter.accept(String.valueOf(c));
        }

        assertThat(filter.raw()).isEqualTo(document);
    }

    @Test
    void toleratesWhitespaceVariantsAroundTheKey() {
        List<String> variants = List.of(
                "{\"reasoning\":\"Tight.\"}",
                "{\"reasoning\" : \"Tight.\"}",
                "{\"reasoning\"  :   \"Tight.\"}");

        List<String> emitted = new ArrayList<>();
        variants.forEach(variant -> emitted.add(characterByCharacter(variant)));

        assertThat(emitted).containsExactly("Tight.", "Tight.", "Tight.");
    }
}
