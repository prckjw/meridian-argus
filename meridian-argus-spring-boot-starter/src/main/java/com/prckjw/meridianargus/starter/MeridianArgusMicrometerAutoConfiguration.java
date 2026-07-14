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

@AutoConfiguration(after = MeridianArgusAutoConfiguration.class)
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
