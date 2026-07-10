package com.prckjw.meridianargus.core.model;

import java.math.BigDecimal;

/**
 * Result of running a {@link TokenUsage} through the pricing engine for a given
 * {@link ProviderModel}. Currency is always USD in v0.1 — multi-currency is a
 * candidate for a later release.
 */
public final class CostEstimate {

    private final BigDecimal inputCost;
    private final BigDecimal outputCost;
    private final boolean priceKnown;

    private CostEstimate(BigDecimal inputCost, BigDecimal outputCost, boolean priceKnown) {
        this.inputCost = inputCost;
        this.outputCost = outputCost;
        this.priceKnown = priceKnown;
    }

    public static CostEstimate of(BigDecimal inputCost, BigDecimal outputCost) {
        return new CostEstimate(inputCost, outputCost, true);
    }

    /**
     * Used when the provider/model combination has no entry in the pricing table.
     * We still want to record the call (tokens, latency) without silently
     * pretending it cost nothing — callers should treat totalCost as advisory.
     */
    public static CostEstimate unknownPrice() {
        return new CostEstimate(BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    public BigDecimal getInputCost() {
        return inputCost;
    }

    public BigDecimal getOutputCost() {
        return outputCost;
    }

    public BigDecimal getTotalCost() {
        return inputCost.add(outputCost);
    }

    public boolean isPriceKnown() {
        return priceKnown;
    }

    @Override
    public String toString() {
        return priceKnown
                ? "CostEstimate{total=$" + getTotalCost() + "}"
                : "CostEstimate{unknown pricing}";
    }
}
