package com.prckjw.meridianargus.core.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderModelTest {

    @Test
    void equalProviderAndModelAreEqual() {
        ProviderModel a = ProviderModel.of("openai", "gpt-4o");
        ProviderModel b = ProviderModel.of("openai", "gpt-4o");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentProviderIsNotEqual() {
        assertNotEquals(ProviderModel.of("openai", "gpt-4o"), ProviderModel.of("azure-openai", "gpt-4o"));
    }

    @Test
    void differentModelIsNotEqual() {
        assertNotEquals(ProviderModel.of("openai", "gpt-4o"), ProviderModel.of("openai", "gpt-4o-mini"));
    }

    @Test
    void isCaseSensitive() {
        // Deliberate: pricing table lookups are case-sensitive. If this ever
        // needs to change, it should be a conscious decision, not an accident.
        assertNotEquals(ProviderModel.of("openai", "gpt-4o"), ProviderModel.of("openai", "GPT-4O"));
    }

    @Test
    void worksAsHashMapKey() {
        // This is exactly how PricingConfig stores its lookup table, so this
        // test exists to pin down that equals()/hashCode() actually behave
        // correctly when used as a Map key, not just in isolation.
        Map<ProviderModel, String> map = new HashMap<>();
        map.put(ProviderModel.of("azure-openai", "gpt-4o"), "found-it");

        assertEquals("found-it", map.get(ProviderModel.of("azure-openai", "gpt-4o")));
        assertNull(map.get(ProviderModel.of("azure-openai", "gpt-4o-mini")));
    }

    @Test
    void rejectsNullProvider() {
        assertThrows(NullPointerException.class, () -> new ProviderModel(null, "gpt-4o"));
    }

    @Test
    void rejectsNullModel() {
        assertThrows(NullPointerException.class, () -> new ProviderModel("openai", null));
    }

    @Test
    void toStringIsProviderColonModel() {
        assertEquals("openai:gpt-4o", ProviderModel.of("openai", "gpt-4o").toString());
    }
}
