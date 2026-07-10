package com.prckjw.meridianargus.core.pricing;

import java.math.BigDecimal;

/**
 * Price per 1,000 tokens, in USD, for one provider/model combination.
 */
public final class PriceEntry {

    private final BigDecimal inputPricePer1k;
    private final BigDecimal outputPricePer1k;

    public PriceEntry(BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {
        this.inputPricePer1k = inputPricePer1k;
        this.outputPricePer1k = outputPricePer1k;
    }

    public BigDecimal getInputPricePer1k() {
        return inputPricePer1k;
    }

    public BigDecimal getOutputPricePer1k() {
        return outputPricePer1k;
    }
}
