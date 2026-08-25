package com.prckjw.meridianargus.otel;

import com.prckjw.meridianargus.core.exporter.AbstractMetricsExporter;
import com.prckjw.meridianargus.core.model.CostEstimate;
import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

/**
 * Exports every observed {@link LLMCallRecord} as a single OpenTelemetry
 * span, using the current (as of implementation time) OpenTelemetry GenAI
 * semantic conventions.
 * <p>
 * <b>The GenAI semantic conventions are experimental and still changing.</b>
 * As one concrete example: the {@code gen_ai.system} attribute this module
 * would have used a year earlier was renamed to {@code gen_ai.provider.name}
 * in semantic-conventions v1.37.0 (August 2025) — the exact kind of churn
 * the spec itself warns is still ongoing (as of mid-2026 the GenAI
 * conventions remain in "Development" status with no stabilization date).
 * If spans produced by this module stop matching what your backend
 * expects, check the
 * <a href="https://github.com/open-telemetry/semantic-conventions-genai">current
 * GenAI semantic conventions repository</a> before assuming this module is
 * broken; the attribute names may simply have moved again.
 * <p>
 * This class only depends on {@code opentelemetry-api}, not the SDK — you
 * bring your own {@link OpenTelemetry} instance (however your application
 * already configures its exporter, sampler, and resource attributes) and
 * this module just calls {@link OpenTelemetry#getTracer(String)} on it and
 * creates spans. If no real SDK is configured, {@code OpenTelemetry} falls
 * back to a no-op implementation — spans are created and discarded, never
 * an error.
 * <p>
 * One span per call, named {@code "chat <model>"} (matching the
 * {@code "{gen_ai.operation.name} {gen_ai.request.model}"} span-naming
 * convention), covering the call's actual start time and measured latency
 * — not the time this exporter happens to run, which may lag slightly
 * behind the real call if other interceptors are registered ahead of this
 * one in {@code LLMObservabilityRegistry}.
 * <p>
 * <b>Provider naming</b>: {@code gen_ai.provider.name} has a registry of
 * recommended values for well-known providers. This module maps our own
 * {@code "bedrock"} to the registry's confirmed {@code "aws.bedrock"} (see
 * the GenAI semantic conventions provider docs). For {@code "openai"} our
 * name already matches the registry directly. For {@code "azure-openai"},
 * {@code "vertex-ai"}, and {@code "anthropic"}, this module passes our own
 * provider identifier straight through — <b>these three have not been
 * verified against an authoritative source for their exact registry
 * value</b> (unlike Bedrock's, which is quoted directly in the spec's
 * provider documentation) and may need adjustment. Check the
 * <a href="https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/registry/attributes/gen-ai.md">attribute
 * registry</a> if your backend does provider-specific rendering based on
 * this value and something looks off for those three.
 * <p>
 * Deliberately out of scope: prompt/response content capture. The GenAI
 * conventions explicitly treat storing prompt/completion text as span
 * *events* (which can be filtered or dropped at the collector level), not
 * span attributes (which are always indexed and have size limits) — adding
 * that support needs its own design pass (default-off, redaction options),
 * not something to bolt onto this exporter.
 */
public final class OpenTelemetryTraceExporter extends AbstractMetricsExporter {

    private static final String INSTRUMENTATION_NAME = "com.prckjw.meridianargus";

    // Standard GenAI semantic convention attributes (current as of
    // semantic-conventions-genai; see class Javadoc about churn risk).
    private static final AttributeKey<String> GEN_AI_PROVIDER_NAME = AttributeKey.stringKey("gen_ai.provider.name");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    // error.type is a stable, general-purpose attribute (not GenAI-specific).
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    // MeridianArgus-specific attributes: no standard GenAI attribute covers
    // estimated cost or an application-defined caller identity, so these
    // are namespaced under meridian_argus.* rather than gen_ai.* --
    // matching how the spec itself expects vendors to extend it.
    private static final AttributeKey<Double> COST_USD = AttributeKey.doubleKey("meridian_argus.cost_usd");
    private static final AttributeKey<Boolean> COST_KNOWN = AttributeKey.booleanKey("meridian_argus.cost_known");
    private static final AttributeKey<String> CALLER_ID = AttributeKey.stringKey("meridian_argus.caller_id");

    private static final String OPERATION_NAME = "chat";

    private final Tracer tracer;

    public OpenTelemetryTraceExporter(OpenTelemetry openTelemetry, PricingEngine pricingEngine) {
        super(pricingEngine);
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    public void export(LLMCallRecord record, CostEstimate cost) {
        String model = record.getProviderModel().getModel();

        Span span = tracer.spanBuilder(OPERATION_NAME + " " + model)
                .setSpanKind(SpanKind.CLIENT)
                .setStartTimestamp(record.getStartedAt())
                .setAttribute(GEN_AI_PROVIDER_NAME, toGenAiProviderName(record.getProviderModel().getProvider()))
                .setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
                .setAttribute(GEN_AI_REQUEST_MODEL, model)
                .setAttribute(GEN_AI_USAGE_INPUT_TOKENS, record.getTokenUsage().getInputTokens())
                .setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, record.getTokenUsage().getOutputTokens())
                .setAttribute(COST_KNOWN, cost.isPriceKnown())
                .startSpan();

        if (cost.isPriceKnown()) {
            span.setAttribute(COST_USD, cost.getTotalCost().doubleValue());
        }
        if (record.getCallerId() != null) {
            span.setAttribute(CALLER_ID, record.getCallerId());
        }
        if (!record.isSuccess()) {
            span.setStatus(StatusCode.ERROR, record.getErrorType());
            if (record.getErrorType() != null) {
                span.setAttribute(ERROR_TYPE, record.getErrorType());
            }
        }

        span.end(record.getStartedAt().plus(record.getLatency()));
    }

    /**
     * Maps our internal {@code ProviderModel#getProvider()} strings to
     * {@code gen_ai.provider.name} registry values, where confirmed. Only
     * the Bedrock mapping is backed by a direct quote from the spec's
     * provider documentation; everything else passes through unchanged —
     * see the class Javadoc.
     */
    private static String toGenAiProviderName(String meridianArgusProviderName) {
        if ("bedrock".equals(meridianArgusProviderName)) {
            return "aws.bedrock";
        }
        return meridianArgusProviderName;
    }
}
