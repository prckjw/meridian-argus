package com.prckjw.meridianargus.provider.bedrock;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps a call made through the AWS SDK for Java v2 Bedrock Runtime client
 * ({@code software.amazon.awssdk:bedrockruntime}) and publishes an
 * {@link LLMCallRecord} to the registry once it completes.
 * <p>
 * Written against the unified {@code Converse} API (recommended by AWS over
 * the older, model-family-specific {@code InvokeModel} bodies), whose
 * response exposes token usage uniformly via {@code response.usage()}
 * regardless of which underlying foundation model served the request. If you
 * are still on {@code InvokeModel} with a raw JSON body, extract usage from
 * the parsed response body in your own {@code usageExtractor} function — the
 * wrapper itself does not assume a response shape.
 * <p>
 * {@code modelId} should be the Bedrock model ID you invoke with (e.g.
 * {@code "anthropic.claude-3-5-sonnet-20240620-v1:0"}). Pricing lookups key
 * off this value, so either use the short form matching
 * {@code pricing-config/default-pricing.yaml} (e.g.
 * {@code "anthropic.claude-3-5-sonnet"}) or supply your own pricing config
 * with the exact versioned ID.
 * <p>
 * Typical usage:
 * <pre>{@code
 * BedrockCallWrapper wrapper = new BedrockCallWrapper(registry, "anthropic.claude-3-5-sonnet");
 *
 * ConverseResponse result = wrapper.call(
 *     () -> bedrockClient.converse(request),
 *     response -> TokenUsage.of(
 *         response.usage().inputTokens(),
 *         response.usage().outputTokens())
 * );
 * }</pre>
 */
public final class BedrockCallWrapper {

    public static final String PROVIDER_NAME = "bedrock";

    private final LLMObservabilityRegistry registry;
    private final String modelId;
    private final String callerId;

    public BedrockCallWrapper(LLMObservabilityRegistry registry, String modelId) {
        this(registry, modelId, null);
    }

    public BedrockCallWrapper(LLMObservabilityRegistry registry, String modelId, String callerId) {
        this.registry = registry;
        this.modelId = modelId;
        this.callerId = callerId;
    }

    /**
     * Executes {@code sdkCall}, extracts token usage from its response via
     * {@code usageExtractor}, and publishes the resulting record. Bedrock's
     * SDK throws unchecked {@code SdkException} (and subtypes such as
     * {@code ThrottlingException}) on failure, which is recorded as an
     * error and rethrown.
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
                .providerModel(ProviderModel.of(PROVIDER_NAME, modelId))
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
