package com.prckjw.meridianargus.alerting;

import com.prckjw.meridianargus.core.alert.AlertThreshold;
import com.prckjw.meridianargus.core.exporter.AbstractMetricsExporter;
import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Watches every observed {@link LLMCallRecord}'s cost against one or more
 * {@link AlertThreshold}s using a purely in-memory sliding window — no
 * database, no scheduler. Each threshold gets its own independent window;
 * a call's cost is added to every threshold's window simultaneously (e.g.
 * one "$50/hour" threshold and one "$500/day" threshold both see the same
 * calls, just aggregated over different lookback periods).
 * <p>
 * <b>Notification semantics</b>: notifiers are called only on a state
 * <i>transition</i> — the moment the rolling sum first crosses above the
 * threshold ({@link AlertEvent.State#TRIGGERED}), and the moment it drops
 * back under it ({@link AlertEvent.State#RECOVERED}). Calls made while
 * steadily above or below the line don't re-trigger a notification each
 * time — this is a deliberate choice to avoid paging someone once per LLM
 * call during a sustained cost spike. If you need periodic re-reminders
 * while a threshold stays breached, implement that in your own
 * {@link AlertNotifier} (e.g. track the last-notified time yourself).
 * <p>
 * <b>Reliability</b>: matching every other component in this project, a
 * failing notifier is caught, logged, and does not stop the remaining
 * notifiers from running or propagate back to the caller's business logic.
 * <p>
 * <b>Memory</b>: each threshold's window holds one entry per priced call
 * observed within that window's duration. For a very high-throughput
 * application with a long window (e.g. a 30-day threshold), this can grow
 * large; there is currently no entry cap beyond time-based pruning.
 * <p>
 * Calls with an unknown price ({@link CostEstimate#isPriceKnown()}
 * {@code == false}) don't contribute to any threshold's sum — there's
 * nothing to add.
 */
public final class RollingWindowAlertEvaluator extends AbstractMetricsExporter {

    private static final Logger LOG = LoggerFactory.getLogger(RollingWindowAlertEvaluator.class);

    private final List<ThresholdWindow> windows;
    private final List<AlertNotifier> notifiers;

    public RollingWindowAlertEvaluator(PricingEngine pricingEngine, List<AlertThreshold> thresholds, List<AlertNotifier> notifiers) {
        super(pricingEngine);
        Objects.requireNonNull(thresholds, "thresholds must not be null");
        Objects.requireNonNull(notifiers, "notifiers must not be null");
        this.windows = thresholds.stream().map(ThresholdWindow::new).toList();
        this.notifiers = List.copyOf(notifiers);
    }

    @Override
    public void export(LLMCallRecord record, CostEstimate cost) {
        if (!cost.isPriceKnown()) {
            return;
        }

        Instant now = Instant.now();
        for (ThresholdWindow window : windows) {
            AlertEvent event = window.recordAndEvaluate(now, cost.getTotalCost());
            if (event != null) {
                fireNotifications(event);
            }
        }
    }

    /** Current rolling-window spend for the named threshold, or {@code null} if no threshold has that name. */
    public BigDecimal currentSpend(String thresholdName) {
        return windows.stream()
                .filter(w -> w.threshold.getName().equals(thresholdName))
                .findFirst()
                .map(ThresholdWindow::currentSum)
                .orElse(null);
    }

    private void fireNotifications(AlertEvent event) {
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.notify(event);
            } catch (RuntimeException e) {
                LOG.warn("Alert notifier {} failed to deliver event for threshold '{}'",
                        notifier.getClass().getSimpleName(), event.threshold().getName(), e);
            }
        }
    }

    /**
     * Independent sliding window state for one threshold. Not a static
     * nested record precisely because it's mutable (the window contents
     * change on every call) and needs synchronization.
     */
    private static final class ThresholdWindow {
        private final AlertThreshold threshold;
        private final Deque<CostEntry> entries = new ArrayDeque<>();
        private BigDecimal sum = BigDecimal.ZERO;
        private boolean alerting = false;

        ThresholdWindow(AlertThreshold threshold) {
            this.threshold = threshold;
        }

        synchronized AlertEvent recordAndEvaluate(Instant now, BigDecimal cost) {
            entries.addLast(new CostEntry(now, cost));
            sum = sum.add(cost);
            pruneOlderThan(now.minus(threshold.getWindow()));

            boolean breached = sum.compareTo(threshold.getMaxCostUsd()) > 0;
            if (breached && !alerting) {
                alerting = true;
                return AlertEvent.triggered(threshold, sum, now);
            }
            if (!breached && alerting) {
                alerting = false;
                return AlertEvent.recovered(threshold, sum, now);
            }
            return null;
        }

        synchronized BigDecimal currentSum() {
            return sum;
        }

        private void pruneOlderThan(Instant cutoff) {
            while (!entries.isEmpty() && entries.peekFirst().timestamp().isBefore(cutoff)) {
                sum = sum.subtract(entries.pollFirst().cost());
            }
        }
    }

    private record CostEntry(Instant timestamp, BigDecimal cost) {
    }
}
