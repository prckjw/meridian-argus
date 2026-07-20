package com.prckjw.meridianargus.metrics.sqlite;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageQueryTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 0.0025
                  output: 0.01
                gpt-4o-mini:
                  input: 0.00015
                  output: 0.0006
            """;

    @TempDir
    Path tempDir;

    private PricingEngine pricingEngine() {
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        return new PricingEngine(PricingConfig.loadFromStream(is));
    }

    @Test
    void groupsAndSumsByProviderAndModel() {
        Path dbFile = tempDir.resolve("test.db");
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine(), dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .tokenUsage(TokenUsage.of(1000, 1000))
                    .build());
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .tokenUsage(TokenUsage.of(1000, 1000))
                    .build());
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o-mini"))
                    .tokenUsage(TokenUsage.of(2000, 2000))
                    .build());
        }

        UsageQuery query = new UsageQuery(dbFile);
        List<ProviderModelCostSummary> summaries = query.costByProviderModel(Duration.ofDays(1));

        assertEquals(2, summaries.size());

        ProviderModelCostSummary gpt4o = summaries.stream()
                .filter(s -> s.model().equals("gpt-4o")).findFirst().orElseThrow();
        assertEquals(2, gpt4o.callCount());
        assertEquals(0, gpt4o.errorCount());
        assertEquals(2000, gpt4o.inputTokens());
        assertEquals(2000, gpt4o.outputTokens());
        assertTrue(gpt4o.hasKnownCost());
        // 2 calls * (1000 in @ 0.0025/1k + 1000 out @ 0.01/1k) = 2 * 0.0125 = 0.025
        assertEquals(0, new BigDecimal("0.025").compareTo(gpt4o.totalCostUsd()));
    }

    @Test
    void excludesCallsOutsideTheLookbackWindow() {
        Path dbFile = tempDir.resolve("test.db");
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine(), dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .build());
        }

        UsageQuery query = new UsageQuery(dbFile);

        // A negative-duration "lookback" moves the cutoff into the future,
        // which nothing recorded "now" can satisfy -- a simple way to
        // exercise the window filter without manipulating stored timestamps.
        List<ProviderModelCostSummary> summaries = query.costByProviderModel(Duration.ofSeconds(-60));

        assertTrue(summaries.isEmpty());
    }

    @Test
    void errorCountReflectsFailedCallsOnly() {
        Path dbFile = tempDir.resolve("test.db");
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine(), dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .build());
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .errorType("RateLimitException")
                    .build());
        }

        UsageQuery query = new UsageQuery(dbFile);
        ProviderModelCostSummary summary = query.costByProviderModel(Duration.ofDays(1)).get(0);

        assertEquals(2, summary.callCount());
        assertEquals(1, summary.errorCount());
    }

    @Test
    void unpricedModelsHaveNullTotalCostNotZero() {
        Path dbFile = tempDir.resolve("test.db");
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine(), dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("bedrock", "unlisted-model"))
                    .build());
        }

        UsageQuery query = new UsageQuery(dbFile);
        ProviderModelCostSummary summary = query.costByProviderModel(Duration.ofDays(1)).get(0);

        assertFalse(summary.hasKnownCost());
        // totalCost() at the aggregate level still treats unknown as 0 for
        // the convenience sum -- documented in UsageQuery's Javadoc.
        assertEquals(0, BigDecimal.ZERO.compareTo(query.totalCost(Duration.ofDays(1))));
    }

    @Test
    void totalCostSumsAcrossAllProvidersAndModels() {
        Path dbFile = tempDir.resolve("test.db");
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine(), dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .tokenUsage(TokenUsage.of(1000, 0))
                    .build());
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o-mini"))
                    .tokenUsage(TokenUsage.of(1000, 0))
                    .build());
        }

        UsageQuery query = new UsageQuery(dbFile);
        // gpt-4o: 1000 in @ 0.0025/1k = 0.0025; gpt-4o-mini: 1000 in @ 0.00015/1k = 0.00015
        assertEquals(0, new BigDecimal("0.00265").compareTo(query.totalCost(Duration.ofDays(1))));
    }

    @Test
    void callCountIncludesFailedCalls() {
        Path dbFile = tempDir.resolve("test.db");
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine(), dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .build());
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .errorType("Boom")
                    .build());
        }

        UsageQuery query = new UsageQuery(dbFile);
        assertEquals(2, query.callCount(Duration.ofDays(1)));
    }
}
