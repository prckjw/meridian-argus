package com.prckjw.meridianargus.core.interceptor;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMObservabilityRegistryTest {

    private static LLMCallRecord sampleRecord() {
        return LLMCallRecord.builder()
                .providerModel(ProviderModel.of("openai", "gpt-4o"))
                .build();
    }

    @Test
    void publishFansOutToEveryRegisteredInterceptor() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> seenByA = new ArrayList<>();
        List<LLMCallRecord> seenByB = new ArrayList<>();

        registry.register(seenByA::add);
        registry.register(seenByB::add);

        LLMCallRecord record = sampleRecord();
        registry.publish(record);

        assertEquals(List.of(record), seenByA);
        assertEquals(List.of(record), seenByB);
    }

    @Test
    void noInterceptorsMeansPublishIsANoOp() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();

        assertDoesNotThrow(() -> registry.publish(sampleRecord()));
    }

    @Test
    void unregisterStopsFurtherDelivery() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        List<LLMCallRecord> seen = new ArrayList<>();
        LLMCallInterceptor interceptor = seen::add;

        registry.register(interceptor);
        registry.publish(sampleRecord());
        registry.unregister(interceptor);
        registry.publish(sampleRecord());

        assertEquals(1, seen.size());
    }

    @Test
    void oneInterceptorThrowingDoesNotStopTheOthers() {
        // This is the core reliability guarantee: MeridianArgus must never
        // break the caller's business logic because one exporter has a bug.
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        AtomicInteger goodInterceptorCalls = new AtomicInteger();

        registry.register(record -> {
            throw new RuntimeException("simulated exporter failure");
        });
        registry.register(record -> goodInterceptorCalls.incrementAndGet());

        assertDoesNotThrow(() -> registry.publish(sampleRecord()));
        assertEquals(1, goodInterceptorCalls.get());
    }

    @Test
    void publishItselfNeverThrowsEvenIfAllInterceptorsFail() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        registry.register(record -> {
            throw new IllegalStateException("boom");
        });
        registry.register(record -> {
            throw new RuntimeException("also boom");
        });

        assertDoesNotThrow(() -> registry.publish(sampleRecord()));
    }

    @Test
    void getInterceptorsReturnsAnImmutableSnapshot() {
        LLMObservabilityRegistry registry = new LLMObservabilityRegistry();
        registry.register(record -> {
        });

        List<LLMCallInterceptor> snapshot = registry.getInterceptors();

        assertEquals(1, snapshot.size());
        assertTrue(assertThrowsUnsupported(() -> snapshot.add(record -> {
        })));
    }

    private static boolean assertThrowsUnsupported(Runnable r) {
        try {
            r.run();
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }
}
