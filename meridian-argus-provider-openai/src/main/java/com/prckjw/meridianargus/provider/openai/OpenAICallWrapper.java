package com.prckjw.meridianargus.provider.openai;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps a call made through the official OpenAI Java SDK and publishes a
 * {@link LLMCallRecord} to the registry once it completes.
 * <p>
 * Deliberately generic over the SDK's response type ({@code <R>}) instead of
 * hard-depending on a specific `openai-java` artifact version: callers supply
 * a small function that pulls token usage out of whatever response object
 * their SDK version returns. This keeps this module compiling against any
 * OpenAI SDK version the consumer already has on their classpath.
 * <p>
 * Typical usage:
 * <pre>{@code
 * OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");
 *
 * ChatCompletion result = wrapper.call(
 *     () -> openAiClient.chat().completions().create(request),
 *     response -> TokenUsage.of(response.usage().promptTokens(), response.usage().completionTokens())
 * );
 * }</pre>
 */
public final class OpenAICallWrapper {

    public static final String PROVIDER_NAME = "openai";

    private final LLMObservabilityRegistry registry;
    private final String model;
    private final String callerId;

    public OpenAICallWrapper(LLMObservabilityRegistry registry, String model) {
        this(registry, model, null);
    }

    public OpenAICallWrapper(LLMObservabilityRegistry registry, String model, String callerId) {
        this.registry = registry;
        this.model = model;
        this.callerId = callerId;
    }

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
                .providerModel(ProviderModel.of(PROVIDER_NAME, model))
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
