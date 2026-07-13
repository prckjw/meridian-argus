package com.prckjw.meridianargus.provider.openai;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAICallWrapperTest {

    @Test
    void successfulCallPublishesRecordWithExtractedUsage() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");

        String result = wrapper.call(
                () -> "fake-response",
                response -> TokenUsage.of(120, 45));

        assertEquals("fake-response", result);
        assertEquals(1, published.size());

        LLMCallRecord record = published.get(0);
        assertEquals("openai", record.getProviderModel().getProvider());
        assertEquals("gpt-4o", record.getProviderModel().getModel());
        assertEquals(120, record.getTokenUsage().getInputTokens());
        assertEquals(45, record.getTokenUsage().getOutputTokens());
        assertTrue(record.isSuccess());
    }

    @Test
    void sdkExceptionIsRecordedAsFailureAndRethrown() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");

        RuntimeException thrown = assertThrows(IllegalStateException.class, () -> wrapper.call(
                () -> {
                    throw new IllegalStateException("rate limited");
                },
                response -> TokenUsage.empty()));

        assertEquals("rate limited", thrown.getMessage());
        assertEquals(1, published.size());
        assertFalse(published.get(0).isSuccess());
        assertEquals("IllegalStateException", published.get(0).getErrorType());
        assertEquals(0, published.get(0).getTokenUsage().getTotalTokens());
    }

    @Test
    void usageExtractorThrowingDoesNotMaskTheRealResponse() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");

        String result = wrapper.call(
                () -> "real-response",
                response -> {
                    throw new NullPointerException("malformed usage field");
                });

        // The SDK call itself succeeded; a broken usage extractor should not
        // turn that into a failure or swallow the real response.
        assertEquals("real-response", result);
        assertTrue(published.get(0).isSuccess());
        assertEquals(0, published.get(0).getTokenUsage().getTotalTokens());
    }

    @Test
    void nullUsageFromExtractorIsTreatedAsEmpty() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");
        wrapper.call(() -> "x", response -> null);

        assertEquals(0, published.get(0).getTokenUsage().getTotalTokens());
    }

    @Test
    void callerIdIsCarriedThroughWhenProvided() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o", "checkout-service");
        wrapper.call(() -> "x", response -> TokenUsage.empty());

        assertEquals("checkout-service", published.get(0).getCallerId());
    }

    @Test
    void latencyIsMeasuredAndNonNegative() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        OpenAICallWrapper wrapper = new OpenAICallWrapper(registry, "gpt-4o");
        wrapper.call(() -> "x", response -> TokenUsage.empty());

        assertFalse(published.get(0).getLatency().isNegative());
    }
}
