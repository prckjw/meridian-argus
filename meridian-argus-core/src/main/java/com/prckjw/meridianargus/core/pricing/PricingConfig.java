package com.prckjw.meridianargus.core.pricing;

import com.prckjw.meridianargus.core.model.ProviderModel;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Loads a pricing table from YAML shaped like:
 *
 * <pre>
 * providers:
 *   azure-openai:
 *     gpt-4o:
 *       input: 0.0025
 *       output: 0.01
 * </pre>
 *
 * Prices are USD per 1,000 tokens. Community contributions to
 * {@code pricing-config/default-pricing.yaml} are the primary way this stays
 * current — see CONTRIBUTING.md.
 */
public final class PricingConfig {

    private final Map<ProviderModel, PriceEntry> prices;

    private PricingConfig(Map<ProviderModel, PriceEntry> prices) {
        this.prices = prices;
    }

    @SuppressWarnings("unchecked")
    public static PricingConfig loadFromStream(InputStream input) {
        Objects.requireNonNull(input, "input stream must not be null");
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(input);
        Map<ProviderModel, PriceEntry> result = new HashMap<>();

        if (root == null || !root.containsKey("providers")) {
            return new PricingConfig(result);
        }

        Map<String, Object> providers = (Map<String, Object>) root.get("providers");
        for (Map.Entry<String, Object> providerEntry : providers.entrySet()) {
            String providerName = providerEntry.getKey();
            Map<String, Object> models = (Map<String, Object>) providerEntry.getValue();

            for (Map.Entry<String, Object> modelEntry : models.entrySet()) {
                String modelName = modelEntry.getKey();
                Map<String, Object> priceFields = (Map<String, Object>) modelEntry.getValue();

                BigDecimal input = toBigDecimal(priceFields.get("input"));
                BigDecimal output = toBigDecimal(priceFields.get("output"));

                result.put(ProviderModel.of(providerName, modelName), new PriceEntry(input, output));
            }
        }

        return new PricingConfig(result);
    }

    public static PricingConfig loadDefault() {
        InputStream is = PricingConfig.class.getClassLoader()
                .getResourceAsStream("default-pricing.yaml");
        if (is == null) {
            return new PricingConfig(new HashMap<>());
        }
        try (is) {
            return loadFromStream(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load default-pricing.yaml", e);
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }

    public PriceEntry get(ProviderModel providerModel) {
        return prices.get(providerModel);
    }

    public boolean has(ProviderModel providerModel) {
        return prices.containsKey(providerModel);
    }

    public int size() {
        return prices.size();
    }
}
