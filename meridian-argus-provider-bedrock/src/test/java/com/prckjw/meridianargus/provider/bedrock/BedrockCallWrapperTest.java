package com.prckjw.meridianargus.provider.bedrock;

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

class BedrockCallWrapperTest {

    @Test
    void successfulCallPublishesRecordWithExtractedUsage() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        BedrockCallWrapper wrapper = new BedrockCallWrapper(registry, "anthropic.claude-3-5-sonnet");

        String result = wrapper.call(() -> "converse-response", r -> TokenUsage.of(300, 150));

        assertEquals("converse-response", result);
        LLMCallRecord record = published.get(0);
        assertEquals("bedrock", record.getProviderModel().getProvider());
        assertEquals("anthropic.claude-3-5-sonnet", record.getProviderModel().getModel());
        assertEquals(300, record.getTokenUsage().getInputTokens());
        assertEquals(150, record.getTokenUsage().getOutputTokens());
        assertTrue(record.isSuccess());
    }

    @Test
    void throttlingExceptionIsRecordedAsFailureAndRethrown() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        BedrockCallWrapper wrapper = new BedrockCallWrapper(registry, "anthropic.claude-3-5-sonnet");

        assertThrows(RuntimeException.class, () -> wrapper.call(
                () -> {
                    throw new RuntimeException("ThrottlingException");
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

        BedrockCallWrapper wrapper = new BedrockCallWrapper(registry, "amazon.titan-text-express", "batch-job");
        wrapper.call(() -> "x", r -> TokenUsage.empty());

        assertEquals("batch-job", published.get(0).getCallerId());
        assertEquals("amazon.titan-text-express", published.get(0).getProviderModel().getModel());
    }
}
