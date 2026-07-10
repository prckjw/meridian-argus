package com.prckjw.meridianargus.core.exporter;

import com.prckjw.meridianargus.core.interceptor.LLMCallInterceptor;
import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;

/**
 * An exporter is just an {@link LLMCallInterceptor} that also knows how to
 * price a call. Concrete implementations (Micrometer/Prometheus, local
 * SQLite, custom webhook, ...) live in their own modules so consumers only
 * pull in the dependencies they actually need.
 */
public interface MetricsExporter extends LLMCallInterceptor {

    /**
     * Default implementation wires pricing in automatically; override
     * {@link #export(LLMCallRecord, CostEstimate)} to do the real work.
     */
    @Override
    default void onCallCompleted(LLMCallRecord record) {
        export(record, computeCost(record));
    }

    CostEstimate computeCost(LLMCallRecord record);

    void export(LLMCallRecord record, CostEstimate cost);
}
