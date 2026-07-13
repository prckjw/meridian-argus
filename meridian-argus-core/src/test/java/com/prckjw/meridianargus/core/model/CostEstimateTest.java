package com.prckjw.meridianargus.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostEstimateTest {

    @Test
    void ofSumsInputAndOutputForTotal() {
        CostEstimate cost = CostEstimate.of(new BigDecimal("0.0025"), new BigDecimal("0.01"));

        assertTrue(cost.isPriceKnown());
        assertEquals(0, new BigDecimal("0.0025").compareTo(cost.getInputCost()));
        assertEquals(0, new BigDecimal("0.01").compareTo(cost.getOutputCost()));
        assertEquals(0, new BigDecimal("0.0125").compareTo(cost.getTotalCost()));
    }

    @Test
    void unknownPriceIsZeroButFlaggedUnknown() {
        CostEstimate cost = CostEstimate.unknownPrice();

        assertFalse(cost.isPriceKnown());
        assertEquals(0, BigDecimal.ZERO.compareTo(cost.getTotalCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cost.getInputCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cost.getOutputCost()));
    }

    @Test
    void toStringDiffersBetweenKnownAndUnknown() {
        String known = CostEstimate.of(BigDecimal.ONE, BigDecimal.ONE).toString();
        String unknown = CostEstimate.unknownPrice().toString();

        assertTrue(known.contains("total=$"));
        assertTrue(unknown.contains("unknown"));
    }
}
