package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import com.prckjw.meridianargus.metrics.micrometer.MicrometerMetricsExporter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

// @ConditionalOnBean(MeterRegistry) is only meaningful if Boot's own metrics
// auto-configurations have already created the registry, so order after them.
// afterName (strings) is used so a name that doesn't exist in the running
// Boot version is simply ignored.
@AutoConfiguration(
        after = MeridianArgusAutoConfiguration.class,
        afterName = {
                // Spring Boot 4 (modularised)
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration",
                // Spring Boot 3.x
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration"
        })
@ConditionalOnClass(name = {
        "com.prckjw.meridianargus.metrics.micrometer.MicrometerMetricsExporter",
        "io.micrometer.core.instrument.MeterRegistry"
})
@ConditionalOnProperty(prefix = "meridian-argus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeridianArgusMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "meridian-argus.metrics.micrometer", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public MicrometerMetricsExporter micrometerMetricsExporter(
            MeterRegistry meterRegistry,
            PricingEngine pricingEngine,
            LLMObservabilityRegistry registry) {
        MicrometerMetricsExporter exporter = new MicrometerMetricsExporter(meterRegistry, pricingEngine);
        registry.register(exporter);
        return exporter;
    }
}
