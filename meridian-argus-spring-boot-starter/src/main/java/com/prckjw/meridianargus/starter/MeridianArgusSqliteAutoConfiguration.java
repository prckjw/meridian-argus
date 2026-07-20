package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import com.prckjw.meridianargus.metrics.sqlite.LocalStorageExporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Kept as its own {@code @AutoConfiguration} class (rather than a
 * {@code @Bean} method inside {@link MeridianArgusAutoConfiguration}) for
 * the same reason as {@link MeridianArgusMicrometerAutoConfiguration}: it
 * references types from an optional dependency
 * ({@code meridian-argus-metrics-sqlite}, and transitively {@code org.sqlite.JDBC}),
 * and a class-literal {@code @ConditionalOnClass} on an always-active
 * configuration class doesn't protect against a *different* optional type
 * showing up in a {@code @Bean} method signature. See
 * {@code CONTRIBUTING.md}'s "New exporters" section for the full
 * explanation.
 * <p>
 * Unlike the Micrometer exporter, this one is opt-in: it only activates
 * when {@code meridian-argus.metrics.sqlite.enabled=true} is set explicitly
 * (writing to a local file is a deliberate side effect the consumer should
 * choose, not something that turns on just because a dependency is present).
 */
@AutoConfiguration(after = MeridianArgusAutoConfiguration.class)
@ConditionalOnClass(name = {
        "com.prckjw.meridianargus.metrics.sqlite.LocalStorageExporter",
        "org.sqlite.JDBC"
})
@ConditionalOnProperty(prefix = "meridian-argus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeridianArgusSqliteAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "meridian-argus.metrics.sqlite", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public LocalStorageExporter localStorageExporter(
            PricingEngine pricingEngine,
            LLMObservabilityRegistry registry,
            MeridianArgusProperties properties) {

        Path databaseFile = Path.of(properties.getMetrics().getSqlite().getPath());
        LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine, databaseFile);
        registry.register(exporter);
        return exporter;
    }
}
