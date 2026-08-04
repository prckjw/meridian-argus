package com.prckjw.meridianargus.dashboard;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import com.prckjw.meridianargus.metrics.sqlite.LocalStorageExporter;
import com.prckjw.meridianargus.metrics.sqlite.UsageQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardControllerTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 1.0
                  output: 1.0
            """;

    @TempDir
    Path tempDir;

    private DashboardController buildControllerWithSampleData() {
        Path dbFile = tempDir.resolve("test.db");
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        PricingEngine pricingEngine = new PricingEngine(PricingConfig.loadFromStream(is));

        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine, dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .tokenUsage(TokenUsage.of(1000, 0))
                    .build());
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .tokenUsage(TokenUsage.of(1000, 0))
                    .errorType("RateLimitException")
                    .build());
        }

        return new DashboardController(new UsageQuery(dbFile));
    }

    @Test
    void dashboardPageServesTheBundledHtml() {
        DashboardController controller = buildControllerWithSampleData();

        ResponseEntity<String> response = controller.dashboardPage();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("MeridianArgus Dashboard"));
        assertTrue(response.getBody().contains("<html"));
    }

    @Test
    void summaryAggregatesCorrectly() {
        DashboardController controller = buildControllerWithSampleData();

        ResponseEntity<DashboardSummaryResponse> response = controller.summary(24);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DashboardSummaryResponse body = response.getBody();
        assertEquals(24, body.windowHours());
        assertEquals(2, body.totalCalls());
        assertEquals(1, body.totalErrors());
        assertEquals(0, new BigDecimal("2.00").compareTo(body.totalCostUsd()));
        assertEquals(1, body.byProviderModel().size());

        ProviderModelRow row = body.byProviderModel().get(0);
        assertEquals("openai", row.provider());
        assertEquals("gpt-4o", row.model());
        assertEquals(2, row.callCount());
        assertEquals(1, row.errorCount());
        assertEquals(2000, row.totalTokens());
        assertTrue(row.costKnown());
    }

    @Test
    void summaryRejectsNonPositiveWindow() {
        DashboardController controller = buildControllerWithSampleData();

        assertEquals(HttpStatus.BAD_REQUEST, controller.summary(0).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.summary(-5).getStatusCode());
    }

    @Test
    void summaryHandlesEmptyDatabaseGracefully() {
        Path dbFile = tempDir.resolve("empty.db");
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        PricingEngine pricingEngine = new PricingEngine(PricingConfig.loadFromStream(is));
        // Create the file (and schema) without writing any rows.
        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine, dbFile)) {
            // no-op: just ensures the schema exists
        }

        DashboardController controller = new DashboardController(new UsageQuery(dbFile));
        ResponseEntity<DashboardSummaryResponse> response = controller.summary(24);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().totalCalls());
        assertTrue(response.getBody().byProviderModel().isEmpty());
    }

    @Test
    void unpricedModelIsFlaggedNotZeroed() {
        Path dbFile = tempDir.resolve("unpriced.db");
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        PricingEngine pricingEngine = new PricingEngine(PricingConfig.loadFromStream(is));

        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine, dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("bedrock", "unlisted-model"))
                    .tokenUsage(TokenUsage.of(500, 500))
                    .build());
        }

        DashboardController controller = new DashboardController(new UsageQuery(dbFile));
        ProviderModelRow row = controller.summary(24).getBody().byProviderModel().get(0);

        assertEquals(false, row.costKnown());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.totalCostUsd()));
    }
}
