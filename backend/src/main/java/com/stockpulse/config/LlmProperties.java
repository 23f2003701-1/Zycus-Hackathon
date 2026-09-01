package com.stockpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM gateway settings. The API key is never committed - it resolves from the
 * {@code LLM_API_KEY} environment variable in application.properties.
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** gemini | groq | ollama */
    private String provider = "groq";

    private String apiKey = "";

    private String model = "openai/gpt-oss-20b";

    private String baseUrl = "https://api.groq.com";

    private int timeoutSeconds = 30;

    private double temperature = 0.5;

    private int maxTokens = 2048;

    public boolean isConfigured() {
        return "ollama".equalsIgnoreCase(provider) || (apiKey != null && !apiKey.isBlank());
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }
}
