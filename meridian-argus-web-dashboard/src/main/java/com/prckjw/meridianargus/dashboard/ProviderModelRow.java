package com.prckjw.meridianargus.dashboard;

import java.math.BigDecimal;

/**
 * One row of the provider/model breakdown table. A stable API-facing shape,
 * deliberately kept separate from
 * {@code com.prckjw.meridianargus.metrics.sqlite.ProviderModelCostSummary}
 * so this module's JSON contract doesn't change just because the SQLite
 * module's internal aggregate shape does.
 */
public record ProviderModelRow(
        String provider,
        String model,
        long callCount,
        long errorCount,
        long totalTokens,
        BigDecimal totalCostUsd,
        boolean costKnown) {
}
