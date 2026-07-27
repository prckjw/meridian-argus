package com.prckjw.meridianargus.alerting;

import com.prckjw.meridianargus.core.alert.AlertThreshold;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingWindowAlertEvaluatorTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 1.0
                  output: 1.0
            """;

    private static PricingEngine pricingEngine() {
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        return new PricingEngine(PricingConfig.loadFromStream(is));
    }

    /** 1000 input tokens @ $1.0/1k = $1.00 per call with this pricing table, for easy round numbers. */
    private static LLMCallRecord callCosting1Dollar() {
        return LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 0))
                .build();
    }

    @Test
    void firesTriggeredEventOnceWhenSumCrossesTheLine() {
        List<AlertEvent> captured = new ArrayList<>();
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("2.50"), Duration.ofHours(1));
        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of(captured::add));

        // $1 x 2 = $2, still under $2.50
        evaluator.onCallCompleted(callCosting1Dollar());
        evaluator.onCallCompleted(callCosting1Dollar());
        assertTrue(captured.isEmpty());

        // $1 x 3 = $3, now over $2.50 -- exactly one TRIGGERED event
        evaluator.onCallCompleted(callCosting1Dollar());
        assertEquals(1, captured.size());
        assertEquals(AlertEvent.State.TRIGGERED, captured.get(0).state());
        assertEquals(0, new BigDecimal("3.00").compareTo(captured.get(0).currentSpendUsd()));
    }

    @Test
    void doesNotReTriggerOnSubsequentCallsWhileStillOverThreshold() {
        List<AlertEvent> captured = new ArrayList<>();
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("1.50"), Duration.ofHours(1));
        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of(captured::add));

        evaluator.onCallCompleted(callCosting1Dollar());
        evaluator.onCallCompleted(callCosting1Dollar()); // crosses $1.50 here -> 1 event
        evaluator.onCallCompleted(callCosting1Dollar());
        evaluator.onCallCompleted(callCosting1Dollar());

        long triggeredCount = captured.stream().filter(e -> e.state() == AlertEvent.State.TRIGGERED).count();
        assertEquals(1, triggeredCount);
    }

    @Test
    void firesRecoveredEventWhenOldEntriesAgeOutOfTheWindow() throws InterruptedException {
        List<AlertEvent> captured = new ArrayList<>();
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("1.50"), Duration.ofMillis(100));
        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of(captured::add));

        evaluator.onCallCompleted(callCosting1Dollar()); // sum=1, not breached
        evaluator.onCallCompleted(callCosting1Dollar()); // sum=2, breached -> TRIGGERED

        assertEquals(1, captured.size());
        assertEquals(AlertEvent.State.TRIGGERED, captured.get(0).state());

        Thread.sleep(150); // both prior entries are now outside the 100ms window

        evaluator.onCallCompleted(callCosting1Dollar()); // pruning drops both old entries; sum=1, not breached -> RECOVERED

        assertEquals(2, captured.size());
        assertEquals(AlertEvent.State.RECOVERED, captured.get(1).state());
        assertEquals(0, new BigDecimal("1.00").compareTo(captured.get(1).currentSpendUsd()));
    }

    @Test
    void independentThresholdsTrackSeparately() {
        List<AlertEvent> captured = new ArrayList<>();
        AlertThreshold hourly = new AlertThreshold("hourly", new BigDecimal("1.50"), Duration.ofHours(1));
        AlertThreshold daily = new AlertThreshold("daily", new BigDecimal("10.00"), Duration.ofDays(1));
        RollingWindowAlertEvaluator evaluator = new RollingWindowAlertEvaluator(
                pricingEngine(), List.of(hourly, daily), List.of(captured::add));

        evaluator.onCallCompleted(callCosting1Dollar());
        evaluator.onCallCompleted(callCosting1Dollar()); // hourly crosses $1.50

        assertEquals(1, captured.size());
        assertEquals("hourly", captured.get(0).threshold().getName());
    }

    @Test
    void currentSpendReflectsTheRunningSum() {
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("100.00"), Duration.ofHours(1));
        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of());

        evaluator.onCallCompleted(callCosting1Dollar());
        evaluator.onCallCompleted(callCosting1Dollar());

        assertEquals(0, new BigDecimal("2.00").compareTo(evaluator.currentSpend("test")));
    }

    @Test
    void currentSpendReturnsNullForUnknownThresholdName() {
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("100.00"), Duration.ofHours(1));
        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of());

        assertEquals(null, evaluator.currentSpend("does-not-exist"));
    }

    @Test
    void unpricedCallsDoNotContributeToTheSum() {
        List<AlertEvent> captured = new ArrayList<>();
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("0.01"), Duration.ofHours(1));
        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of(captured::add));

        LLMCallRecord unpriced = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "unlisted-model"))
                .tokenUsage(TokenUsage.of(999999, 999999))
                .build();

        evaluator.onCallCompleted(unpriced);

        assertTrue(captured.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(evaluator.currentSpend("test")));
    }

    @Test
    void oneFailingNotifierDoesNotStopTheOthersOrThrow() {
        List<AlertEvent> captured = new ArrayList<>();
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("0.50"), Duration.ofHours(1));

        AlertNotifier broken = event -> {
            throw new RuntimeException("simulated notifier failure");
        };
        AlertNotifier good = captured::add;

        RollingWindowAlertEvaluator evaluator =
                new RollingWindowAlertEvaluator(pricingEngine(), List.of(threshold), List.of(broken, good));

        evaluator.onCallCompleted(callCosting1Dollar());

        assertEquals(1, captured.size());
    }
}
