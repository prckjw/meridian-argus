package com.prckjw.meridianargus.core.model;

import java.util.Objects;

/**
 * Identifies a specific model on a specific provider, e.g. ("azure-openai", "gpt-4o").
 * Used as the lookup key for pricing and as a dimension for metrics/labels.
 */
public final class ProviderModel {

    private final String provider;
    private final String model;

    public ProviderModel(String provider, String model) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    public static ProviderModel of(String provider, String model) {
        return new ProviderModel(provider, model);
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderModel)) return false;
        ProviderModel that = (ProviderModel) o;
        return provider.equals(that.provider) && model.equals(that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model);
    }

    @Override
    public String toString() {
        return provider + ":" + model;
    }
}
