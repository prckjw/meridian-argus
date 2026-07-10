package com.prckjw.meridianargus.core.exporter;

import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.pricing.PricingEngine;

import java.util.Objects;

/**
 * Convenience base class that wires a {@link PricingEngine} into the export
 * flow so concrete exporters (Micrometer, local storage, webhook, ...) only
 * need to implement {@link #export(LLMCallRecord, CostEstimate)}.
 */
public abstract class AbstractMetricsExporter implements MetricsExporter {

    private final PricingEngine pricingEngine;

    protected AbstractMetricsExporter(PricingEngine pricingEngine) {
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine must not be null");
    }

    @Override
    public final CostEstimate computeCost(LLMCallRecord record) {
        return pricingEngine.estimate(record);
    }
}
