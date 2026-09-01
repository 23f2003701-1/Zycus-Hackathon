package com.stockpulse.engine.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a stream of raw model tokens into a stream of readable reasoning.
 *
 * <p>The pricing prompt asks for JSON, so the tokens arriving from the provider spell out
 * {@code {"recommendedPrice": 27.49, "reasoning": "Stock is ...}} one fragment at a time. Piping
 * that straight to the console would show a merchandiser punctuation assembling itself. This
 * filter watches for the {@code reasoning} value and emits only the characters inside it, decoding
 * JSON escapes as they complete.
 *
 * <p>It is deliberately a hand-rolled scanner rather than a JSON parser: a parser needs a complete
 * document, and the entire point here is to have something to show before the document exists.
 *
 * <p>Chunk boundaries are arbitrary - a provider may split mid-escape or mid-key - so the scanner
 * stops at any incomplete sequence and resumes on the next chunk. Not thread-safe; one instance
 * belongs to one stream.
 */
public final class ReasoningTokenFilter {

    private static final Pattern REASONING_KEY = Pattern.compile("\"reasoning\"\\s*:\\s*\"");

    private final StringBuilder raw = new StringBuilder();

    private int scanned = -1;
    private boolean valueClosed;

    /**
     * Accepts the next raw fragment and returns whatever reasoning text became readable because of
     * it - often empty, which callers should treat as "nothing to show yet" rather than an end.
     */
    public String accept(String rawChunk) {
        if (rawChunk == null || rawChunk.isEmpty()) {
            return "";
        }
        raw.append(rawChunk);

        if (valueClosed) {
            return "";
        }
        if (scanned < 0 && !locateValueStart()) {
            return "";
        }
        return scanForward();
    }

    /** The complete raw text seen so far, for callers that still need to parse the real document. */
    public String raw() {
        return raw.toString();
    }

    /** True once the closing quote of the reasoning value has been consumed. */
    public boolean isComplete() {
        return valueClosed;
    }

    private boolean locateValueStart() {
        Matcher matcher = REASONING_KEY.matcher(raw);
        if (!matcher.find()) {
            return false;
        }
        scanned = matcher.end();
        return true;
    }

    private String scanForward() {
        StringBuilder readable = new StringBuilder();

        while (scanned < raw.length()) {
            char current = raw.charAt(scanned);

            if (current == '"') {
                valueClosed = true;
                scanned++;
                break;
            }

            if (current != '\\') {
                readable.append(current);
                scanned++;
                continue;
            }

            // An escape split across chunks: leave it unconsumed and pick it up next time.
            if (scanned + 1 >= raw.length()) {
                break;
            }
            char escaped = raw.charAt(scanned + 1);
            if (escaped == 'u') {
                if (scanned + 6 > raw.length()) {
                    break;
                }
                readable.append(decodeUnicode(raw.substring(scanned + 2, scanned + 6)));
                scanned += 6;
                continue;
            }
            readable.append(decodeSimple(escaped));
            scanned += 2;
        }

        return readable.toString();
    }

    private static char decodeSimple(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'b' -> '\b';
            case 'f' -> '\f';
            // Covers \" \\ \/ and anything non-standard the model invents, which is safer to pass
            // through verbatim than to drop from text a human is about to read.
            default -> escaped;
        };
    }

    private static char decodeUnicode(String hex) {
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException ex) {
            return '?';
        }
    }
}
