package com.prckjw.meridianargus.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenUsageTest {

    @Test
    void ofComputesTotal() {
        TokenUsage usage = TokenUsage.of(1000, 250);

        assertEquals(1000, usage.getInputTokens());
        assertEquals(250, usage.getOutputTokens());
        assertEquals(1250, usage.getTotalTokens());
    }

    @Test
    void emptyIsAllZeros() {
        TokenUsage usage = TokenUsage.empty();

        assertEquals(0, usage.getInputTokens());
        assertEquals(0, usage.getOutputTokens());
        assertEquals(0, usage.getTotalTokens());
    }

    @Test
    void rejectsNegativeInputTokens() {
        assertThrows(IllegalArgumentException.class, () -> TokenUsage.of(-1, 10));
    }

    @Test
    void rejectsNegativeOutputTokens() {
        assertThrows(IllegalArgumentException.class, () -> TokenUsage.of(10, -1));
    }

    @Test
    void toStringIncludesAllThreeCounts() {
        String s = TokenUsage.of(10, 20).toString();

        assertEquals(true, s.contains("10"));
        assertEquals(true, s.contains("20"));
        assertEquals(true, s.contains("30"));
    }
}
