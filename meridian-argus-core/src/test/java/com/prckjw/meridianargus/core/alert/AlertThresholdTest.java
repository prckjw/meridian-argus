package com.prckjw.meridianargus.core.alert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AlertThreshold is currently just a data holder -- no AlertEvaluator
 * consumes it yet (see docs/architecture.md's roadmap, v0.3). These tests
 * only cover the constructor/getter contract; expand this once a rolling
 * window evaluator lands.
 */
class AlertThresholdTest {

    @Test
    void gettersReturnConstructorArguments() {
        AlertThreshold threshold = new AlertThreshold("hourly-budget", new BigDecimal("50.00"), Duration.ofHours(1));

        assertEquals("hourly-budget", threshold.getName());
        assertEquals(0, new BigDecimal("50.00").compareTo(threshold.getMaxCostUsd()));
        assertEquals(Duration.ofHours(1), threshold.getWindow());
    }
}
