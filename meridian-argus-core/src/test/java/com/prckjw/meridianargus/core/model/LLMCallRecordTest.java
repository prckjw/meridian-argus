package com.prckjw.meridianargus.core.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMCallRecordTest {

    @Test
    void buildsSuccessfulRecordWithDefaults() {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .build();

        assertTrue(record.isSuccess());
        assertNull(record.getErrorType());
        // tokenUsage defaults to empty() rather than null, so callers never
        // have to null-check it.
        assertEquals(0, record.getTokenUsage().getTotalTokens());
        assertEquals(Duration.ZERO, record.getLatency());
    }

    @Test
    void errorTypeImplicitlyFlipsSuccessToFalse() {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .errorType("RateLimitException")
                .build();

        assertFalse(record.isSuccess());
        assertEquals("RateLimitException", record.getErrorType());
    }

    @Test
    void explicitSuccessTrueCanBeOverriddenByErrorType() {
        // success(true) followed by errorType(...) should still end up false,
        // since errorType() is the more specific/later call in this ordering.
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .success(true)
                .errorType("Boom")
                .build();

        assertFalse(record.isSuccess());
    }

    @Test
    void carriesCallerIdThrough() {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .callerId("checkout-service")
                .build();

        assertEquals("checkout-service", record.getCallerId());
    }

    @Test
    void callerIdDefaultsToNull() {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .build();

        assertNull(record.getCallerId());
    }

    @Test
    void requiresProviderModel() {
        assertThrows(NullPointerException.class, () -> LLMCallRecord.builder().build());
    }

    @Test
    void preservesExplicitTokenUsageLatencyAndStartedAt() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Duration latency = Duration.ofMillis(842);
        TokenUsage usage = TokenUsage.of(120, 45);

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("anthropic", "claude-3-5-sonnet"))
                .tokenUsage(usage)
                .startedAt(start)
                .latency(latency)
                .build();

        assertEquals(usage, record.getTokenUsage());
        assertEquals(start, record.getStartedAt());
        assertEquals(latency, record.getLatency());
    }
}
