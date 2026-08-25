package com.prckjw.meridianargus.otel;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryTraceExporterTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 0.0025
                  output: 0.01
            """;

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private static PricingEngine pricingEngine() {
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        return new PricingEngine(PricingConfig.loadFromStream(is));
    }

    @Test
    void producesASpanWithGenAiSemanticConventionAttributes() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 500))
                .startedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .latency(Duration.ofMillis(250))
                .build();

        exporter.onCallCompleted(record);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        SpanData span = spans.get(0);
        assertEquals("chat gpt-4o", span.getName());
        assertEquals("openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
        assertEquals("chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals("gpt-4o", span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(1000L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(500L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    }

    @Test
    void mapsBedrockProviderNameToRegistryValue() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "anthropic.claude-3-5-sonnet"))
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals("aws.bedrock", span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
    }

    @Test
    void includesCostWhenPriceIsKnown() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 1000))
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(true, span.getAttributes().get(AttributeKey.booleanKey("meridian_argus.cost_known")));
        // 1000 input @ 0.0025/1k + 1000 output @ 0.01/1k = 0.0125
        assertEquals(0.0125, span.getAttributes().get(AttributeKey.doubleKey("meridian_argus.cost_usd")), 1e-9);
    }

    @Test
    void omitsCostAttributeEntirelyWhenPriceIsUnknown() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "some-unlisted-model"))
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(false, span.getAttributes().get(AttributeKey.booleanKey("meridian_argus.cost_known")));
        // Must be entirely absent, not present-and-zero -- a $0.00 cost
        // attribute would misleadingly look like a known, free call.
        assertNull(span.getAttributes().get(AttributeKey.doubleKey("meridian_argus.cost_usd")));
    }

    @Test
    void failedCallSetsErrorStatusAndErrorTypeAttribute() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .errorType("RateLimitException")
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("RateLimitException", span.getAttributes().get(AttributeKey.stringKey("error.type")));
    }

    @Test
    void successfulCallDoesNotSetErrorStatus() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertFalse(span.getStatus().getStatusCode() == StatusCode.ERROR);
    }

    @Test
    void callerIdIsIncludedWhenPresent() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .callerId("checkout-service")
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals("checkout-service", span.getAttributes().get(AttributeKey.stringKey("meridian_argus.caller_id")));
    }

    @Test
    void spanCoversTheRecordsActualStartAndLatencyNotExporterRunTime() {
        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine());

        Instant started = Instant.parse("2026-06-15T12:00:00Z");
        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .startedAt(started)
                .latency(Duration.ofSeconds(3))
                .build());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(started.getEpochSecond(), span.getStartEpochNanos() / 1_000_000_000);
        assertEquals(started.plusSeconds(3).getEpochSecond(), span.getEndEpochNanos() / 1_000_000_000);
    }
}
