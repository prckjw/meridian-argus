package com.prckjw.meridianargus.dashboard;

import java.math.BigDecimal;
import java.util.List;

/** JSON response body for {@code GET /meridian-argus/dashboard/api/summary}. */
public record DashboardSummaryResponse(
        long windowHours,
        BigDecimal totalCostUsd,
        long totalCalls,
        long totalErrors,
        List<ProviderModelRow> byProviderModel) {
}
