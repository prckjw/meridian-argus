package com.prckjw.meridianargus.metrics.sqlite;

import java.math.BigDecimal;

/**
 * Aggregated usage/cost for one provider+model combination over some lookback
 * window, as returned by {@link UsageQuery#costByProviderModel(java.time.Duration)}.
 * <p>
 * {@code totalCostUsd} is {@code null} rather than zero when none of the
 * aggregated calls had a known price — matching {@code CostEstimate}'s
 * "unknown is not the same as zero" convention elsewhere in this project.
 */
public record ProviderModelCostSummary(
        String provider,
        String model,
        long callCount,
        long errorCount,
        long inputTokens,
        long outputTokens,
        BigDecimal totalCostUsd) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean hasKnownCost() {
        return totalCostUsd != null;
    }
}
