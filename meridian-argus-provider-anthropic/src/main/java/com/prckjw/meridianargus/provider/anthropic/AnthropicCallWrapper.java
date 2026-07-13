package com.prckjw.meridianargus.provider.anthropic;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps a call made through the official Anthropic Java SDK
 * ({@code com.anthropic:anthropic-java}) Messages API and publishes an
 * {@link LLMCallRecord} to the registry once it completes.
 * <p>
 * The Messages API response exposes usage via {@code message.usage()}, with
 * {@code inputTokens()} and {@code outputTokens()} — note that unlike some
 * other providers, Anthropic additionally distinguishes cache-read and
 * cache-creation tokens for prompt caching; fold those into
 * {@code inputTokens} in your extractor if you want cache usage reflected in
 * cost estimates, or track it separately if you need cache-hit visibility.
 * <p>
 * This adapter is for direct {@code api.anthropic.com} access. If you serve
 * Claude models through AWS Bedrock or Google Vertex AI, use
 * {@code meridian-argus-provider-bedrock} or
 * {@code meridian-argus-provider-vertex} instead, so cost is attributed to
 * the hosting platform's pricing rather than Anthropic's direct pricing.
 * <p>
 * {@code model} should match a key in your pricing table (e.g.
 * {@code "claude-3-5-sonnet"} — see {@code pricing-config/default-pricing.yaml}).
 * <p>
 * Typical usage:
 * <pre>{@code
 * AnthropicCallWrapper wrapper = new AnthropicCallWrapper(registry, "claude-3-5-sonnet");
 *
 * Message result = wrapper.call(
 *     () -> client.messages().create(params),
 *     message -> TokenUsage.of(
 *         message.usage().inputTokens(),
 *         message.usage().outputTokens())
 * );
 * }</pre>
 */
public final class AnthropicCallWrapper {

    public static final String PROVIDER_NAME = "anthropic";

    private final LLMObservabilityRegistry registry;
    private final String model;
    private final String callerId;

    public AnthropicCallWrapper(LLMObservabilityRegistry registry, String model) {
        this(registry, model, null);
    }

    public AnthropicCallWrapper(LLMObservabilityRegistry registry, String model, String callerId) {
        this.registry = registry;
        this.model = model;
        this.callerId = callerId;
    }

    /**
     * Executes {@code sdkCall}, extracts token usage from its response via
     * {@code usageExtractor}, and publishes the resulting record. The
     * Anthropic SDK throws unchecked {@code AnthropicException} subtypes
     * (e.g. {@code RateLimitException}, {@code OverloadedException}) on
     * failure, which are recorded as errors and rethrown.
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
