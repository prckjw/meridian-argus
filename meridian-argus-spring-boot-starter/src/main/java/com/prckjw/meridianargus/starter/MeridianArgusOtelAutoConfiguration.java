package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import com.prckjw.meridianargus.otel.OpenTelemetryTraceExporter;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Kept as its own {@code @AutoConfiguration} class for the same reason as
 * the other optional-dependency exporters — see {@code CONTRIBUTING.md}'s
 * "New exporters" section. The class-level string-based
 * {@code @ConditionalOnClass} below deliberately lists {@code OpenTelemetry}
 * itself, even though it's also referenced via class-literal in
 * {@code @ConditionalOnBean(OpenTelemetry.class)} on the {@code @Bean}
 * method — the class-level check runs first (at
 * {@code ConfigurationPhase.PARSE_CONFIGURATION}) and, when it fails, stops
 * Spring from ever reaching the point where it would need to resolve that
 * class-literal annotation argument.
 * <p>
 * Unlike the SQLite exporter and alerting, this follows the Micrometer
 * exporter's shape: defaults to enabled, and only actually activates when
 * an {@link OpenTelemetry} bean already exists in the context (typically
 * from {@code opentelemetry-spring-boot-starter} or an equivalent manual
 * SDK configuration). This module never creates or configures an
 * OpenTelemetry SDK itself — it only adds spans to whatever the
 * application already set up for its own tracing.
 */
@AutoConfiguration(after = MeridianArgusAutoConfiguration.class)
@ConditionalOnClass(name = {
        "com.prckjw.meridianargus.otel.OpenTelemetryTraceExporter",
        "io.opentelemetry.api.OpenTelemetry"
})
@ConditionalOnBean(OpenTelemetry.class)
@ConditionalOnProperty(prefix = "meridian-argus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeridianArgusOtelAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "meridian-argus.metrics.otel", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public OpenTelemetryTraceExporter openTelemetryTraceExporter(
            OpenTelemetry openTelemetry,
            PricingEngine pricingEngine,
            LLMObservabilityRegistry registry) {

        OpenTelemetryTraceExporter exporter = new OpenTelemetryTraceExporter(openTelemetry, pricingEngine);
        registry.register(exporter);
        return exporter;
    }
}
