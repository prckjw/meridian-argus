package com.prckjw.meridianargus.metrics.sqlite;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageExporterTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 0.0025
                  output: 0.01
            """;

    @TempDir
    Path tempDir;

    private LocalStorageExporter exporter;
    private Path dbFile;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("test.db");
        InputStream is = new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8));
        PricingEngine pricingEngine = new PricingEngine(PricingConfig.loadFromStream(is));
        exporter = new LocalStorageExporter(pricingEngine, dbFile);
    }

    @AfterEach
    void tearDown() {
        exporter.close();
    }

    @Test
    void createsFileAndSchemaOnConstruction() throws Exception {
        assertTrue(dbFile.toFile().exists());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='llm_calls'")) {
            assertTrue(rs.next(), "expected llm_calls table to exist");
        }
    }

    @Test
    void createsParentDirectoriesIfMissing() {
        Path nested = tempDir.resolve("a/b/c/nested.db");
        assertDoesNotThrow(() -> {
            PricingEngine pricingEngine = new PricingEngine(
                    PricingConfig.loadFromStream(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))));
            LocalStorageExporter nestedExporter = new LocalStorageExporter(pricingEngine, nested);
            nestedExporter.close();
        });
        assertTrue(nested.toFile().exists());
    }

    @Test
    void writesRowThatCanBeReadBackViaJdbc() throws Exception {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 500))
                .latency(Duration.ofMillis(250))
                .callerId("checkout-service")
                .build();

        exporter.onCallCompleted(record);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM llm_calls")) {
            assertTrue(rs.next());
            assertEquals("openai", rs.getString("provider"));
            assertEquals("gpt-4o", rs.getString("model"));
            assertEquals(1000, rs.getInt("input_tokens"));
            assertEquals(500, rs.getInt("output_tokens"));
            assertEquals(250, rs.getLong("latency_ms"));
            assertEquals(1, rs.getInt("success"));
            assertEquals("checkout-service", rs.getString("caller_id"));
            assertEquals(1, rs.getInt("price_known"));
            assertEquals("0.0075", rs.getString("total_cost_usd"));
            assertTrue(!rs.next(), "expected exactly one row");
        }
    }

    @Test
    void unknownPricingStoresNullCostButPriceKnownFalse() throws Exception {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "some-unlisted-model"))
                .build();

        exporter.onCallCompleted(record);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM llm_calls")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("price_known"));
            assertNull(rs.getString("total_cost_usd"));
        }
    }

    @Test
    void failedCallIsStoredWithSuccessFalseAndErrorType() throws Exception {
        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .errorType("RateLimitException")
                .build();

        exporter.onCallCompleted(record);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM llm_calls")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("success"));
            assertEquals("RateLimitException", rs.getString("error_type"));
        }
    }

    @Test
    void multipleWritesAppendRatherThanOverwrite() throws Exception {
        for (int i = 0; i < 5; i++) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .build());
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) AS n FROM llm_calls")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("n"));
        }
    }

    @Test
    void reopeningTheSameFilePreservesExistingRows() {
        exporter.onCallCompleted(LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .build());
        exporter.close();

        PricingEngine pricingEngine = new PricingEngine(
                PricingConfig.loadFromStream(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))));
        LocalStorageExporter reopened = new LocalStorageExporter(pricingEngine, dbFile);
        try {
            UsageQuery query = new UsageQuery(dbFile);
            assertEquals(1, query.callCount(Duration.ofDays(1)));
        } finally {
            reopened.close();
        }
    }
}
