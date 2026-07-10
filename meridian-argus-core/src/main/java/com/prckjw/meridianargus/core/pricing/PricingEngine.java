package com.prckjw.meridianargus.core.pricing;

import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PricingEngine {

    private static final BigDecimal PER_THOUSAND = BigDecimal.valueOf(1000);

    private final PricingConfig config;

    public PricingEngine(PricingConfig config) {
        this.config = config;
    }

    public CostEstimate estimate(LLMCallRecord record) {
        ProviderModel key = record.getProviderModel();
        PriceEntry price = config.get(key);
        if (price == null) {
            return CostEstimate.unknownPrice();
        }

        TokenUsage usage = record.getTokenUsage();
        BigDecimal inputCost = price.getInputPricePer1k()
                .multiply(BigDecimal.valueOf(usage.getInputTokens()))
                .divide(PER_THOUSAND, 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = price.getOutputPricePer1k()
                .multiply(BigDecimal.valueOf(usage.getOutputTokens()))
                .divide(PER_THOUSAND, 8, RoundingMode.HALF_UP);

        return CostEstimate.of(inputCost, outputCost);
    }
}
