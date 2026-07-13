package com.prckjw.meridianargus.provider.anthropic;

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

class AnthropicCallWrapperTest {

    @Test
    void successfulCallPublishesRecordWithExtractedUsage() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AnthropicCallWrapper wrapper = new AnthropicCallWrapper(registry, "claude-3-5-sonnet");

        String result = wrapper.call(() -> "message-response", m -> TokenUsage.of(500, 200));

        assertEquals("message-response", result);
        LLMCallRecord record = published.get(0);
        assertEquals("anthropic", record.getProviderModel().getProvider());
        assertEquals("claude-3-5-sonnet", record.getProviderModel().getModel());
        assertEquals(500, record.getTokenUsage().getInputTokens());
        assertEquals(200, record.getTokenUsage().getOutputTokens());
        assertTrue(record.isSuccess());
    }

    @Test
    void rateLimitFailureIsRecordedAndRethrown() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AnthropicCallWrapper wrapper = new AnthropicCallWrapper(registry, "claude-3-5-sonnet");

        assertThrows(RuntimeException.class, () -> wrapper.call(
                () -> {
                    throw new RuntimeException("rate limit exceeded");
                },
                m -> TokenUsage.empty()));

        assertFalse(published.get(0).isSuccess());
        assertEquals("RuntimeException", published.get(0).getErrorType());
    }

    @Test
    void callerIdOverloadCarriesThrough() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> published = new ArrayList<>();
        registry.register(published::add);

        AnthropicCallWrapper wrapper = new AnthropicCallWrapper(registry, "claude-3-opus", "research-agent");
        wrapper.call(() -> "x", m -> TokenUsage.empty());

        assertEquals("research-agent", published.get(0).getCallerId());
        assertEquals("claude-3-opus", published.get(0).getProviderModel().getModel());
    }
}
