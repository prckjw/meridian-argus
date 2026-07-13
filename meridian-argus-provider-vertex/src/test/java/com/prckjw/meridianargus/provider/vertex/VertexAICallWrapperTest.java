package com.prckjw.meridianargus.provider.vertex;

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

class VertexAICallWrapperTest {

    @Test
    void successfulCallPublishesRecordWithExtractedUsage() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        VertexAICallWrapper wrapper = new VertexAICallWrapper(registry, "gemini-1.5-pro");

        String result = wrapper.call(() -> "generate-content-response", r -> TokenUsage.of(80, 40));

        assertEquals("generate-content-response", result);
        LLMCallRecord record = published.get(0);
        assertEquals("vertex-ai", record.getProviderModel().getProvider());
        assertEquals("gemini-1.5-pro", record.getProviderModel().getModel());
        assertEquals(80, record.getTokenUsage().getInputTokens());
        assertEquals(40, record.getTokenUsage().getOutputTokens());
        assertTrue(record.isSuccess());
    }

    @Test
    void failureIsRecordedAndRethrown() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        VertexAICallWrapper wrapper = new VertexAICallWrapper(registry, "gemini-1.5-flash");

        assertThrows(RuntimeException.class, () -> wrapper.call(
                () -> {
                    throw new RuntimeException("quota exceeded");
                },
                r -> TokenUsage.empty()));

        assertFalse(published.get(0).isSuccess());
        assertEquals("RuntimeException", published.get(0).getErrorType());
    }

    @Test
    void callerIdOverloadCarriesThrough() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        VertexAICallWrapper wrapper = new VertexAICallWrapper(registry, "gemini-1.5-pro", "recommendation-service");
        wrapper.call(() -> "x", r -> TokenUsage.empty());

        assertEquals("recommendation-service", published.get(0).getCallerId());
    }
}
