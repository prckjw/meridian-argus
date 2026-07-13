package com.prckjw.meridianargus.provider.azure;

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

class AzureOpenAICallWrapperTest {

    @Test
    void successfulCallPublishesRecordWithExtractedUsage() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AzureOpenAICallWrapper wrapper = new AzureOpenAICallWrapper(registry, "gpt-4o");

        String result = wrapper.call(() -> "fake-response", r -> TokenUsage.of(200, 80));

        assertEquals("fake-response", result);
        LLMCallRecord record = published.get(0);
        assertEquals("azure-openai", record.getProviderModel().getProvider());
        assertEquals("gpt-4o", record.getProviderModel().getModel());
        assertEquals(200, record.getTokenUsage().getInputTokens());
        assertEquals(80, record.getTokenUsage().getOutputTokens());
        assertTrue(record.isSuccess());
    }

    @Test
    void sdkExceptionIsRecordedAsFailureAndRethrown() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AzureOpenAICallWrapper wrapper = new AzureOpenAICallWrapper(registry, "gpt-4o");

        assertThrows(RuntimeException.class, () -> wrapper.call(
                () -> {
                    throw new RuntimeException("throttled");
                },
                r -> TokenUsage.empty()));

        assertFalse(published.get(0).isSuccess());
        assertEquals("RuntimeException", published.get(0).getErrorType());
    }

    @Test
    void usageExtractorFailureDoesNotMaskSuccessfulResponse() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AzureOpenAICallWrapper wrapper = new AzureOpenAICallWrapper(registry, "gpt-4o");
        String result = wrapper.call(() -> "ok", r -> {
            throw new RuntimeException("bad usage field");
        });

        assertEquals("ok", result);
        assertTrue(published.get(0).isSuccess());
    }

    @Test
    void callerIdOverloadCarriesThrough() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AzureOpenAICallWrapper wrapper = new AzureOpenAICallWrapper(registry, "gpt-4o", "billing-service");
        wrapper.call(() -> "x", r -> TokenUsage.empty());

        assertEquals("billing-service", published.get(0).getCallerId());
    }
}
