package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.alerting.RollingWindowAlertEvaluator;
import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.dashboard.DashboardController;
import com.prckjw.meridianargus.metrics.sqlite.LocalStorageExporter;
import com.prckjw.meridianargus.otel.OpenTelemetryTraceExporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every optional module switched on at once. Each auto-configuration has its
 * own test; this one guards against interactions (bean-name clashes, ordering,
 * a missing import in the combined classpath).
 */
class MeridianArgusAllExportersIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void contextStartsWithEveryExporterEnabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MeridianArgusAutoConfiguration.class,
                        MeridianArgusMicrometerAutoConfiguration.class,
                        MeridianArgusSqliteAutoConfiguration.class,
                        MeridianArgusAlertingAutoConfiguration.class,
                        MeridianArgusOtelAutoConfiguration.class,
                        MeridianArgusDashboardAutoConfiguration.class))
                .withUserConfiguration(InfraConfig.class)
                .withPropertyValues(
                        "meridian-argus.metrics.sqlite.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("all.db"),
                        "meridian-argus.alerting.enabled=true",
                        "meridian-argus.alerting.thresholds[0].name=hourly-budget",
                        "meridian-argus.alerting.thresholds[0].max-cost-usd=50.00",
                        "meridian-argus.alerting.thresholds[0].window=PT1H",
                        "meridian-argus.dashboard.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalStorageExporter.class);
                    assertThat(context).hasSingleBean(RollingWindowAlertEvaluator.class);
                    assertThat(context).hasSingleBean(OpenTelemetryTraceExporter.class);
                    assertThat(context).hasSingleBean(DashboardController.class);

                    // Micrometer + SQLite + alerting + OTel are interceptors;
                    // the dashboard is a read-only controller, not one.
                    LLMObservabilityRegistry registry = context.getBean(LLMObservabilityRegistry.class);
                    assertThat(registry.getInterceptors()).hasSize(4);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class InfraConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }
}
