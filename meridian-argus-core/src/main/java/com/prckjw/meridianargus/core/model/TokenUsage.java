package com.prckjw.meridianargus.core.model;

/**
 * Normalized token usage across providers. Different SDKs name these fields
 * differently (prompt/completion, input/output, etc.) — adapters are responsible
 * for mapping into this shape.
 */
public final class TokenUsage {

    private final long inputTokens;
    private final long outputTokens;

    public TokenUsage(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens);
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0);
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return inputTokens + outputTokens;
    }

    @Override
    public String toString() {
        return "TokenUsage{input=" + inputTokens + ", output=" + outputTokens + ", total=" + getTotalTokens() + "}";
    }
}
