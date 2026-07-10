package com.prckjw.meridianargus.core.alert;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Defines a rolling-window cost threshold, e.g. "alert if spend exceeds
 * $50 within any 1-hour window". Evaluation of the rolling window itself is
 * left to {@code AlertEvaluator} implementations (kept out of core to avoid
 * pulling in a scheduling dependency here).
 */
public final class AlertThreshold {

    private final String name;
    private final BigDecimal maxCostUsd;
    private final Duration window;

    public AlertThreshold(String name, BigDecimal maxCostUsd, Duration window) {
        this.name = name;
        this.maxCostUsd = maxCostUsd;
        this.window = window;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMaxCostUsd() {
        return maxCostUsd;
    }

    public Duration getWindow() {
        return window;
    }
}
