package com.prckjw.meridianargus.metrics.micrometer;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerMetricsExporterTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 0.0025
                  output: 0.01
            """;

    private static PricingEngine pricingEngine() {
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        return new PricingEngine(PricingConfig.loadFromStream(is));
    }

    @Test
    void recordsCallsTokensCostAndLatencyWithProviderModelTags() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerMetricsExporter exporter = new MicrometerMetricsExporter(meterRegistry, pricingEngine());

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 500))
                .latency(Duration.ofMillis(120))
                .build();

        exporter.onCallCompleted(record);

        Counter calls = meterRegistry.find("meridian_argus.calls")
                .tag("provider", "openai").tag("model", "gpt-4o").tag("success", "true").counter();
        assertEquals(1.0, calls.count());

        Counter inputTokens = meterRegistry.find("meridian_argus.tokens.input")
                .tag("provider", "openai").tag("model", "gpt-4o").counter();
        assertEquals(1000.0, inputTokens.count());

        Counter outputTokens = meterRegistry.find("meridian_argus.tokens.output")
                .tag("provider", "openai").tag("model", "gpt-4o").counter();
        assertEquals(500.0, outputTokens.count());

        Counter cost = meterRegistry.find("meridian_argus.cost.usd")
                .tag("provider", "openai").tag("model", "gpt-4o").counter();
        // 1000 input @ 0.0025/1k + 500 output @ 0.01/1k = 0.0025 + 0.005 = 0.0075
        assertEquals(0.0075, cost.count(), 1e-9);

        Timer latency = meterRegistry.find("meridian_argus.latency")
                .tag("provider", "openai").tag("model", "gpt-4o").timer();
        assertEquals(1, latency.count());
        assertEquals(120.0, latency.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void failedCallIsTaggedSuccessFalse() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerMetricsExporter exporter = new MicrometerMetricsExporter(meterRegistry, pricingEngine());

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .errorType("RateLimitException")
                .build();

        exporter.onCallCompleted(record);

        Counter failedCalls = meterRegistry.find("meridian_argus.calls")
                .tag("provider", "openai").tag("model", "gpt-4o").tag("success", "false").counter();
        assertEquals(1.0, failedCalls.count());
    }

    @Test
    void unknownPricingSkipsCostMeterButStillRecordsTokensAndLatency() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerMetricsExporter exporter = new MicrometerMetricsExporter(meterRegistry, pricingEngine());

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "some-unlisted-model"))
                .tokenUsage(TokenUsage.of(10, 5))
                .build();

        exporter.onCallCompleted(record);

        Counter cost = meterRegistry.find("meridian_argus.cost.usd")
                .tag("provider", "bedrock").tag("model", "some-unlisted-model").counter();
        // Must never silently report an incorrect $0.00 — the meter simply
        // shouldn't exist for a provider/model combination with no known price.
        assertNull(cost);

        Counter inputTokens = meterRegistry.find("meridian_argus.tokens.input")
                .tag("provider", "bedrock").tag("model", "some-unlisted-model").counter();
        assertEquals(10.0, inputTokens.count());
    }

    @Test
    void latencyPublishesPercentileHistogramForQuantileQueries() {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerMetricsExporter exporter = new MicrometerMetricsExporter(meterRegistry, pricingEngine());

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .latency(Duration.ofMillis(50))
                .build();

        exporter.onCallCompleted(record);

        String scrape = meterRegistry.scrape();
        assertTrue(scrape.contains("meridian_argus_latency_seconds_bucket"));
    }

    @Test
    void differentModelsProduceIndependentCounters() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerMetricsExporter exporter = new MicrometerMetricsExporter(meterRegistry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .build());
        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o-mini"))
                .build());

        Counter gpt4o = meterRegistry.find("meridian_argus.calls")
                .tag("model", "gpt-4o").counter();
        Counter gpt4oMini = meterRegistry.find("meridian_argus.calls")
                .tag("model", "gpt-4o-mini").counter();

        assertEquals(1.0, gpt4o.count());
        assertEquals(1.0, gpt4oMini.count());
    }
}
