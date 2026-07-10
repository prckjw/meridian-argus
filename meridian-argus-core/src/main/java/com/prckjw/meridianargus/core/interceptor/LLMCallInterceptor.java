package com.prckjw.meridianargus.core.interceptor;

import com.prckjw.meridianargus.core.model.LLMCallRecord;

/**
 * Receives a completed {@link LLMCallRecord} right after a provider adapter
 * has observed a call. Implementations should be fast and non-blocking —
 * heavy work (network I/O to an exporter, etc.) should be handed off async.
 * <p>
 * Provider adapter modules (e.g. meridian-argus-provider-openai) are
 * responsible for wrapping/decorating the underlying SDK client and invoking
 * every registered interceptor once a call completes (success or failure).
 */
@FunctionalInterface
public interface LLMCallInterceptor {

    void onCallCompleted(LLMCallRecord record);
}
