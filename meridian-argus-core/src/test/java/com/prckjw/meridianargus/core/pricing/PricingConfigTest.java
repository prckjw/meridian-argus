package com.prckjw.meridianargus.core.pricing;

import com.prckjw.meridianargus.core.model.ProviderModel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingConfigTest {

    @Test
    void parsesMultipleProvidersAndModels() {
        String yaml = """
                providers:
                  openai:
                    gpt-4o:
                      input: 0.0025
                      output: 0.01
                    gpt-4o-mini:
                      input: 0.00015
                      output: 0.0006
                  anthropic:
                    claude-3-5-sonnet:
                      input: 0.003
                      output: 0.015
                """;

        PricingConfig config = load(yaml);

        assertEquals(3, config.size());
        assertTrue(config.has(ProviderModel.of("openai", "gpt-4o")));
        assertTrue(config.has(ProviderModel.of("openai", "gpt-4o-mini")));
        assertTrue(config.has(ProviderModel.of("anthropic", "claude-3-5-sonnet")));
    }

    @Test
    void getReturnsCorrectPriceEntry() {
        String yaml = """
                providers:
                  azure-openai:
                    gpt-4o:
                      input: 0.0025
                      output: 0.01
                """;

        PriceEntry entry = load(yaml).get(ProviderModel.of("azure-openai", "gpt-4o"));

        assertEquals(0, new BigDecimal("0.0025").compareTo(entry.getInputPricePer1k()));
        assertEquals(0, new BigDecimal("0.01").compareTo(entry.getOutputPricePer1k()));
    }

    @Test
    void unknownProviderModelReturnsNullNotException() {
        PricingConfig config = load("providers:\n  openai:\n    gpt-4o:\n      input: 0.0025\n      output: 0.01\n");

        assertNull(config.get(ProviderModel.of("openai", "does-not-exist")));
        assertFalse(config.has(ProviderModel.of("openai", "does-not-exist")));
    }

    @Test
    void emptyDocumentProducesEmptyConfig() {
        PricingConfig config = load("");

        assertEquals(0, config.size());
    }

    @Test
    void documentWithoutProvidersKeyProducesEmptyConfig() {
        PricingConfig config = load("some_other_key: value\n");

        assertEquals(0, config.size());
    }

    @Test
    void loadDefaultLoadsTheBundledPricingTable() {
        // This is the resource copied from pricing-config/default-pricing.yaml
        // into src/main/resources at build time — this test doubles as a
        // guard against that file going missing or becoming unparsable.
        PricingConfig config = PricingConfig.loadDefault();

        assertTrue(config.size() > 0, "expected the bundled default-pricing.yaml to contain at least one entry");
        assertTrue(config.has(ProviderModel.of("openai", "gpt-4o")));
        assertTrue(config.has(ProviderModel.of("azure-openai", "gpt-4o")));
        assertTrue(config.has(ProviderModel.of("bedrock", "anthropic.claude-3-5-sonnet")));
        assertTrue(config.has(ProviderModel.of("vertex-ai", "gemini-1.5-pro")));
        assertTrue(config.has(ProviderModel.of("anthropic", "claude-3-5-sonnet")));
    }

    private static PricingConfig load(String yaml) {
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return PricingConfig.loadFromStream(is);
    }
}
