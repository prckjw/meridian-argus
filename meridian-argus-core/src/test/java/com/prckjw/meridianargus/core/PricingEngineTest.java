package com.prckjw.meridianargus.core;

import com.prckjw.meridianargus.core.model.CostEstimate;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingEngineTest {

    private static final String YAML = """
            providers:
              azure-openai:
                gpt-4o:
                  input: 0.0025
                  output: 0.01
            """;

    @Test
    void computesCostForKnownProviderModel() {
        PricingConfig config = loadConfig(YAML);
        PricingEngine engine = new PricingEngine(config);

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("azure-openai", "gpt-4o"))
                .tokenUsage(TokenUsage.of(1000, 500))
                .startedAt(Instant.now())
                .latency(Duration.ofMillis(250))
                .build();

        CostEstimate cost = engine.estimate(record);

        assertTrue(cost.isPriceKnown());
        assertEquals(0, new BigDecimal("0.00250000").compareTo(cost.getInputCost()));
        assertEquals(0, new BigDecimal("0.00500000").compareTo(cost.getOutputCost()));
        assertEquals(0, new BigDecimal("0.00750000").compareTo(cost.getTotalCost()));
    }

    @Test
    void returnsUnknownPriceForUnlistedProviderModel() {
        PricingConfig config = loadConfig(YAML);
        PricingEngine engine = new PricingEngine(config);

        LLMCallRecord record = LLMCallRecord.builder()
                .providerModel(ProviderModel.of("bedrock", "claude-3-5-sonnet"))
                .tokenUsage(TokenUsage.of(100, 50))
                .startedAt(Instant.now())
                .latency(Duration.ofMillis(100))
                .build();

        CostEstimate cost = engine.estimate(record);

        assertFalse(cost.isPriceKnown());
        assertEquals(0, BigDecimal.ZERO.compareTo(cost.getTotalCost()));
    }

    private static PricingConfig loadConfig(String yaml) {
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return PricingConfig.loadFromStream(is);
    }
}
