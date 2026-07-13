package com.prckjw.meridianargus.provider.vertex;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps a call made through the Google Cloud Vertex AI Java SDK
 * ({@code com.google.cloud:google-cloud-vertexai}), typically
 * {@code GenerativeModel#generateContent}, and publishes an
 * {@link LLMCallRecord} to the registry once it completes.
 * <p>
 * Vertex's {@code GenerateContentResponse} exposes usage via
 * {@code response.getUsageMetadata()}, with {@code getPromptTokenCount()}
 * and {@code getCandidatesTokenCount()} — map these into a {@link TokenUsage}
 * in your {@code usageExtractor}.
 * <p>
 * {@code modelName} should match a key in your pricing table (e.g.
 * {@code "gemini-1.5-pro"} — see {@code pricing-config/default-pricing.yaml}).
 * <p>
 * Typical usage:
 * <pre>{@code
 * VertexAICallWrapper wrapper = new VertexAICallWrapper(registry, "gemini-1.5-pro");
 *
 * GenerateContentResponse result = wrapper.call(
 *     () -> generativeModel.generateContent(prompt),
 *     response -> TokenUsage.of(
 *         response.getUsageMetadata().getPromptTokenCount(),
 *         response.getUsageMetadata().getCandidatesTokenCount())
 * );
 * }</pre>
 */
public final class VertexAICallWrapper {

    public static final String PROVIDER_NAME = "vertex-ai";

    private final LLMObservabilityRegistry registry;
    private final String modelName;
    private final String callerId;

    public VertexAICallWrapper(LLMObservabilityRegistry registry, String modelName) {
        this(registry, modelName, null);
    }

    public VertexAICallWrapper(LLMObservabilityRegistry registry, String modelName, String callerId) {
        this.registry = registry;
        this.modelName = modelName;
        this.callerId = callerId;
    }

    /**
     * Executes {@code sdkCall}, extracts token usage from its response via
     * {@code usageExtractor}, and publishes the resulting record. The Vertex
     * SDK throws checked {@code java.io.IOException} in some overloads and
     * unchecked runtime exceptions in others (e.g. via the async surface) —
     * this wrapper only sees unchecked failures; wrap checked-exception call
     * sites in your own try/catch and pass a {@code Supplier} that rethrows
     * as unchecked if you need failures recorded uniformly.
     */
    public <R> R call(Supplier<R> sdkCall, Function<R, TokenUsage> usageExtractor) {
        Instant start = Instant.now();
        try {
            R response = sdkCall.get();
            publish(start, extractUsageSafely(response, usageExtractor), true, null);
            return response;
        } catch (RuntimeException ex) {
            publish(start, TokenUsage.empty(), false, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private <R> TokenUsage extractUsageSafely(R response, Function<R, TokenUsage> extractor) {
        try {
            TokenUsage usage = extractor.apply(response);
            return usage == null ? TokenUsage.empty() : usage;
        } catch (RuntimeException ex) {
            // Usage extraction failing must never mask the real SDK response.
            return TokenUsage.empty();
        }
    }

    private void publish(Instant start, TokenUsage usage, boolean success, String errorType) {
        LLMCallRecord.Builder builder = LLMCallRecord.builder()
                .providerModel(ProviderModel.of(PROVIDER_NAME, modelName))
                .tokenUsage(usage)
                .startedAt(start)
                .latency(Duration.between(start, Instant.now()))
                .callerId(callerId);

        if (!success) {
            builder.errorType(errorType);
        }

        registry.publish(builder.build());
    }
}
