package com.prckjw.meridianargus.metrics.micrometer;

import com.prckjw.meridianargus.core.exporter.AbstractMetricsExporter;
import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Publishes the following meters, all tagged with {@code provider} and {@code model}:
 * <ul>
 *   <li>{@code meridian_argus.calls} (counter) — tagged additionally with {@code success}</li>
 *   <li>{@code meridian_argus.tokens.input} / {@code .output} (counters)</li>
 *   <li>{@code meridian_argus.cost.usd} (counter) — cumulative estimated spend</li>
 *   <li>{@code meridian_argus.latency} (timer)</li>
 * </ul>
 * A ready-made Grafana dashboard for these meters lives in {@code docs/grafana-dashboard.json}.
 */
public final class MicrometerMetricsExporter extends AbstractMetricsExporter {

    private final MeterRegistry meterRegistry;

    public MicrometerMetricsExporter(MeterRegistry meterRegistry, PricingEngine pricingEngine) {
        super(pricingEngine);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void export(LLMCallRecord record, CostEstimate cost) {
        Tags tags = Tags.of(
                "provider", record.getProviderModel().getProvider(),
                "model", record.getProviderModel().getModel()
        );

        Counter.builder("meridian_argus.calls")
                .tags(tags.and("success", String.valueOf(record.isSuccess())))
                .register(meterRegistry)
                .increment();

        Counter.builder("meridian_argus.tokens.input")
                .tags(tags)
                .register(meterRegistry)
                .increment(record.getTokenUsage().getInputTokens());

        Counter.builder("meridian_argus.tokens.output")
                .tags(tags)
                .register(meterRegistry)
                .increment(record.getTokenUsage().getOutputTokens());

        if (cost.isPriceKnown()) {
            Counter.builder("meridian_argus.cost.usd")
                    .tags(tags)
                    .register(meterRegistry)
                    .increment(cost.getTotalCost().doubleValue());
        }

        Timer.builder("meridian_argus.latency")
                .tags(tags)
                // Needed so histogram_quantile() queries (e.g. the p95 panel in
                // docs/grafana-dashboard.json) have buckets to work with. Without
                // this, Prometheus only gets _count/_sum/_max, no _bucket series.
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(record.getLatency().toNanos(), TimeUnit.NANOSECONDS);
    }
}
