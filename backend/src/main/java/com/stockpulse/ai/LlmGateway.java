package com.stockpulse.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.config.LlmProperties;

/**
 * Provider-specific HTTP for Gemini, Groq, and Ollama (Addendum B). Returns raw model text -
 * parsing, validation, and fallback belong to the advisors that call it.
 *
 * <p>Configured entirely from {@code application.properties}, with the API key resolved from the
 * {@code LLM_API_KEY} environment variable so it never enters the repository.
 *
 * <p>Two transports live here for a reason. The blocking calls use {@link RestClient}, which wants
 * a fully-buffered body. The streaming calls use the JDK client with a line-by-line body handler,
 * because buffering is precisely what a token stream must not do. All three providers happen to
 * frame their streams as SSE, so the two OpenAI-compatible ones and Gemini differ only in where
 * the delta sits in each chunk.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    private final LlmProperties properties;
    private final RestClient http;
    private final HttpClient streamingHttp;
    private final ObjectMapper json = new ObjectMapper();

    public LlmGateway(LlmProperties properties) {
        this.properties = properties;
        this.http = RestClient.builder()
                .requestFactory(timeoutFactory(properties))
                .build();
        this.streamingHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public String describe() {
        return properties.getProvider() + "/" + properties.getModel();
    }

    /**
     * Sends a prompt and returns the model's raw text.
     *
     * @throws LlmException on any transport failure, timeout, error status, or empty completion
     */
    public String callLlm(String prompt) {
        if (!isConfigured()) {
            throw new LlmException("no API key configured for provider " + properties.getProvider()
                    + "; set the LLM_API_KEY environment variable");
        }

        long startedAt = System.currentTimeMillis();
        try {
            String provider = properties.getProvider().toLowerCase();
            String completion = switch (provider) {
                case "gemini" -> callGemini(prompt);
                case "groq" -> callOpenAiCompatible(
                        prompt, properties.getBaseUrl() + "/openai/v1/chat/completions", true);
                case "ollama" -> callOpenAiCompatible(
                        prompt, properties.getBaseUrl() + "/v1/chat/completions", false);
                default -> throw new LlmException("unknown provider: " + properties.getProvider());
            };

            if (completion == null || completion.isBlank()) {
                throw new LlmException("provider " + provider + " returned an empty completion");
            }
            log.debug("LLM call to {} completed in {}ms", describe(), System.currentTimeMillis() - startedAt);
            return completion;

        } catch (LlmException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Covers connect/read timeouts, 429 quota errors, and 5xx provider outages.
            throw new LlmException("LLM call to " + describe() + " failed after "
                    + (System.currentTimeMillis() - startedAt) + "ms: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new LlmException("unexpected failure calling " + describe() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Sends a prompt and hands each token to {@code onToken} as the provider emits it, returning
     * the same complete text {@link #callLlm} would have returned.
     *
     * <p>Same failure contract as the blocking call: everything becomes an {@link LlmException}.
     * A stream that dies halfway is a failure and not a partial success, because a truncated JSON
     * document cannot be validated - callers must be free to discard the tokens they have shown.
     *
     * @throws LlmException on any transport failure, timeout, error status, or empty completion
     */
    public String streamLlm(String prompt, Consumer<String> onToken) {
        if (!isConfigured()) {
            throw new LlmException("no API key configured for provider " + properties.getProvider()
                    + "; set the LLM_API_KEY environment variable");
        }

        long startedAt = System.currentTimeMillis();
        try {
            String provider = properties.getProvider().toLowerCase();
            String completion = switch (provider) {
                case "gemini" -> streamGemini(prompt, onToken);
                case "groq" -> streamOpenAiCompatible(
                        prompt, properties.getBaseUrl() + "/openai/v1/chat/completions", true, true, onToken);
                case "ollama" -> streamOpenAiCompatible(
                        prompt, properties.getBaseUrl() + "/v1/chat/completions", false, false, onToken);
                default -> throw new LlmException("unknown provider: " + properties.getProvider());
            };

            if (completion.isBlank()) {
                throw new LlmException("provider " + provider + " streamed an empty completion");
            }
            log.debug("LLM stream from {} completed in {}ms", describe(), System.currentTimeMillis() - startedAt);
            return completion;

        } catch (LlmException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new LlmException("LLM stream from " + describe() + " failed after "
                    + (System.currentTimeMillis() - startedAt) + "ms: " + ex.getMessage(), ex);
        }
    }

    private String streamOpenAiCompatible(String prompt, String url, boolean requestJsonMode,
                                          boolean authenticate, Consumer<String> onToken) {
        Map<String, Object> body = openAiBody(prompt, requestJsonMode);
        body.put("stream", true);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(serialise(body)));
        if (authenticate) {
            request.header("Authorization", "Bearer " + properties.getApiKey());
        }

        return consumeSse(request.build(),
                chunk -> chunk.path("choices").path(0).path("delta").path("content"),
                onToken);
    }

    private String streamGemini(String prompt, Consumer<String> onToken) {
        String url = "%s/v1beta/models/%s:streamGenerateContent?alt=sse&key=%s"
                .formatted(properties.getBaseUrl(), properties.getModel(), properties.getApiKey());

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(serialise(geminiBody(prompt))))
                .build();

        return consumeSse(request,
                chunk -> chunk.path("candidates").path(0).path("content").path("parts").path(0).path("text"),
                onToken);
    }

    /**
     * Reads an SSE body line by line, forwarding each delta onward the moment it is decoded.
     *
     * <p>Unparseable chunks are skipped rather than fatal: providers interleave keepalives and
     * usage-only frames, and losing the whole recommendation over one cosmetic frame would be a
     * worse trade than showing slightly fewer tokens.
     */
    private String consumeSse(HttpRequest request, Function<JsonNode, JsonNode> deltaOf,
                              Consumer<String> onToken) {
        StringBuilder complete = new StringBuilder();
        try {
            HttpResponse<Stream<String>> response =
                    streamingHttp.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() >= 400) {
                throw new LlmException("provider " + describe() + " returned HTTP "
                        + response.statusCode() + ": " + firstLines(response.body()));
            }

            try (Stream<String> lines = response.body()) {
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String payload = ssePayload(iterator.next());
                    if (payload == null) {
                        continue;
                    }
                    String token = decodeDelta(payload, deltaOf);
                    if (!token.isEmpty()) {
                        complete.append(token);
                        onToken.accept(token);
                    }
                }
            }
            return complete.toString();

        } catch (IOException | UncheckedIOException ex) {
            throw new LlmException("stream from " + describe() + " broke: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException("stream from " + describe() + " was interrupted", ex);
        }
    }

    /** @return the JSON payload of a {@code data:} frame, or null for anything not worth parsing */
    private static String ssePayload(String line) {
        if (line == null || line.isBlank() || !line.startsWith(SSE_DATA_PREFIX)) {
            return null;
        }
        String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
        return payload.isEmpty() || SSE_DONE.equals(payload) ? null : payload;
    }

    private String decodeDelta(String payload, Function<JsonNode, JsonNode> deltaOf) {
        try {
            JsonNode delta = deltaOf.apply(json.readTree(payload));
            return delta.isTextual() ? delta.asText() : "";
        } catch (JsonProcessingException ex) {
            log.debug("Skipping unparseable stream frame from {}: {}", describe(), payload);
            return "";
        }
    }

    private static String firstLines(Stream<String> body) {
        try (Stream<String> lines = body) {
            return lines.limit(3).reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        }
    }

    private String serialise(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new LlmException("could not serialise request for " + describe(), ex);
        }
    }

    private Map<String, Object> openAiBody(String prompt, boolean requestJsonMode) {
        var body = new HashMap<String, Object>();
        body.put("model", properties.getModel());
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", properties.getTemperature());

        // Reasoning models (gpt-oss on Groq) treat the completion budget as thinking + speaking.
        // The old max_tokens field is ignored or under-applied for them; max_completion_tokens is
        // what actually reserves room for the JSON answer after chain-of-thought.
        boolean groq = "groq".equalsIgnoreCase(properties.getProvider());
        if (groq) {
            body.put("max_completion_tokens", properties.getMaxTokens());
        } else {
            body.put("max_tokens", properties.getMaxTokens());
        }

        if (isGptOssModel(properties.getModel())) {
            // Keep thinking cheap and out of the payload. Our UI streams the JSON "reasoning"
            // field, not the model's private chain-of-thought, so paying tokens to return that
            // field would only starve the answer we actually need.
            body.put("reasoning_effort", "low");
            body.put("include_reasoning", false);
        }

        if (requestJsonMode) {
            // Groq honours this for compatible chat models and it removes most prose-around-JSON failures.
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private static boolean isGptOssModel(String model) {
        return model != null && model.toLowerCase().contains("gpt-oss");
    }

    private Map<String, Object> geminiBody(String prompt) {
        return Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", properties.getTemperature(),
                        "maxOutputTokens", properties.getMaxTokens(),
                        "responseMimeType", "application/json"));
    }

    private String callOpenAiCompatible(String prompt, String url, boolean requestJsonMode) {
        JsonNode response = http.post()
                .uri(url)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .body(openAiBody(prompt, requestJsonMode))
                .retrieve()
                .body(JsonNode.class);

        return text(response, node -> node.path("choices").path(0).path("message").path("content"));
    }

    private String callGemini(String prompt) {
        String url = "%s/v1beta/models/%s:generateContent?key=%s"
                .formatted(properties.getBaseUrl(), properties.getModel(), properties.getApiKey());

        JsonNode response = http.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(geminiBody(prompt))
                .retrieve()
                .body(JsonNode.class);

        return text(response, node ->
                node.path("candidates").path(0).path("content").path("parts").path(0).path("text"));
    }

    private static String text(JsonNode response,
                               java.util.function.Function<JsonNode, JsonNode> extractor) {
        if (response == null) {
            throw new LlmException("provider returned no body");
        }
        JsonNode extracted = extractor.apply(response);
        if (extracted.isMissingNode() || extracted.isNull()) {
            throw new LlmException("could not locate completion text in provider response: " + response);
        }
        return extracted.asText();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(LlmProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        return factory;
    }
}
