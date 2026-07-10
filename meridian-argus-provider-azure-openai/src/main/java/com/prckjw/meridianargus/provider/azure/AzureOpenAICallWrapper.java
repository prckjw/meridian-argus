package com.prckjw.meridianargus.provider.azure;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps a call made through the Azure OpenAI Java SDK ({@code com.azure:azure-ai-openai}).
 * <p>
 * Azure OpenAI identifies calls by <em>deployment name</em> rather than raw
 * model name — callers should pass whichever value they want to appear as
 * the pricing/metrics dimension. If your deployment name doesn't match a
 * model name in the pricing table (e.g. a custom deployment alias like
 * "prod-gpt4o"), add a matching entry to your pricing YAML or the cost will
 * be reported as unknown rather than silently wrong.
 */
public final class AzureOpenAICallWrapper {

    public static final String PROVIDER_NAME = "azure-openai";

    private final LLMObservabilityRegistry registry;
    private final String deploymentName;
    private final String callerId;

    public AzureOpenAICallWrapper(LLMObservabilityRegistry registry, String deploymentName) {
        this(registry, deploymentName, null);
    }

    public AzureOpenAICallWrapper(LLMObservabilityRegistry registry, String deploymentName, String callerId) {
        this.registry = registry;
        this.deploymentName = deploymentName;
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
            return TokenUsage.empty();
        }
    }

    private void publish(Instant start, TokenUsage usage, boolean success, String errorType) {
        LLMCallRecord.Builder builder = LLMCallRecord.builder()
                .providerModel(ProviderModel.of(PROVIDER_NAME, deploymentName))
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
