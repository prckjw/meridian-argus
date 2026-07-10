package com.prckjw.meridianargus.core.interceptor;

import com.prckjw.meridianargus.core.model.LLMCallRecord;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central fan-out point: provider adapters publish {@link LLMCallRecord}s here,
 * and every registered {@link LLMCallInterceptor} (exporters, alerting, etc.)
 * gets notified. A single registry instance is expected to be shared across
 * an application — the Spring Boot starter wires this up as a singleton bean.
 */
public final class LLMObservabilityRegistry {

    private final List<LLMCallInterceptor> interceptors = new CopyOnWriteArrayList<>();

    public void register(LLMCallInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    public void unregister(LLMCallInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    /**
     * Called by provider adapters after every LLM call completes.
     * Exceptions thrown by individual interceptors are swallowed and logged
     * elsewhere — observability must never break the caller's business logic.
     */
    public void publish(LLMCallRecord record) {
        for (LLMCallInterceptor interceptor : interceptors) {
            try {
                interceptor.onCallCompleted(record);
            } catch (RuntimeException ex) {
                // Deliberately isolated: one bad exporter must not affect others
                // or the calling application. See core logging guidance in README.
                System.err.println("[MeridianArgus] interceptor failed: " + ex.getMessage());
            }
        }
    }

    public List<LLMCallInterceptor> getInterceptors() {
        return List.copyOf(interceptors);
    }
}
