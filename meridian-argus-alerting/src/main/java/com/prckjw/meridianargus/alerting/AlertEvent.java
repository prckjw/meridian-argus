package com.prckjw.meridianargus.alerting;

import com.prckjw.meridianargus.core.alert.AlertThreshold;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Emitted by {@link RollingWindowAlertEvaluator} on a state transition —
 * either the rolling-window spend just crossed above
 * {@link AlertThreshold#getMaxCostUsd()} ({@link State#TRIGGERED}), or it
 * just dropped back under it after having been triggered
 * ({@link State#RECOVERED}). The evaluator only emits on the transition
 * itself, never once per call while steadily above or below the line —
 * see {@link RollingWindowAlertEvaluator} for why.
 */
public record AlertEvent(AlertThreshold threshold, State state, BigDecimal currentSpendUsd, Instant occurredAt) {

    public enum State {
        TRIGGERED,
        RECOVERED
    }

    public static AlertEvent triggered(AlertThreshold threshold, BigDecimal currentSpendUsd, Instant occurredAt) {
        return new AlertEvent(threshold, State.TRIGGERED, currentSpendUsd, occurredAt);
    }

    public static AlertEvent recovered(AlertThreshold threshold, BigDecimal currentSpendUsd, Instant occurredAt) {
        return new AlertEvent(threshold, State.RECOVERED, currentSpendUsd, occurredAt);
    }
}
