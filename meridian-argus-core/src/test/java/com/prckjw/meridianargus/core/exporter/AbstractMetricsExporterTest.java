package com.prckjw.meridianargus.core.exporter;

import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractMetricsExporterTest {

    private static final String YAML = """
            providers:
              openai:
                gpt-4o:
                  input: 0.0025
                  output: 0.01
            """;

    @Test
    void onCallCompletedComputesCostAndDelegatesToExport() {
        PricingEngine engine = new PricingEngine(PricingConfig.loadFromStream(
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))));

        RecordingExporter exporter = new RecordingExporter(engine);

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 1000))
                .build();

        exporter.onCallCompleted(record);

        assertEquals(1, exporter.exportCalls);
        assertTrue(exporter.lastCost.isPriceKnown());
        assertEquals(record, exporter.lastRecord);
    }

    @Test
    void unknownPricingStillInvokesExportWithUnknownCost() {
        PricingEngine engine = new PricingEngine(PricingConfig.loadFromStream(
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8))));

        RecordingExporter exporter = new RecordingExporter(engine);

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "some-model-not-in-table"))
                .build();

        exporter.onCallCompleted(record);

        assertEquals(1, exporter.exportCalls);
        assertTrue(exporter.lastCost.isPriceKnown() == false);
    }

    @Test
    void constructorRejectsNullPricingEngine() {
        assertThrows(NullPointerException.class, () -> new RecordingExporter(null));
    }

    private static final class RecordingExporter extends AbstractMetricsExporter {
        int exportCalls = 0;
        LLMCallRecord lastRecord;
        CostEstimate lastCost;

        RecordingExporter(PricingEngine pricingEngine) {
            super(pricingEngine);
        }

        @Override
        public void export(LLMCallRecord record, CostEstimate cost) {
            exportCalls++;
            lastRecord = record;
            lastCost = cost;
        }
    }
}
