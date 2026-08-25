package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.otel.OpenTelemetryTraceExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MeridianArgusOtelAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MeridianArgusAutoConfiguration.class,
                    MeridianArgusOtelAutoConfiguration.class));

    @Test
    void doesNotActivateWithoutAnOpenTelemetryBean() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(OpenTelemetryTraceExporter.class));
    }

    @Test
    void activatesByDefaultWhenAnOpenTelemetryBeanIsPresent() {
        // Enabled-by-default, same shape as the Micrometer exporter -- no
        // meridian-argus.metrics.otel.enabled=true needed, since this
        // doesn't do anything the application's own OTel SDK config
        // doesn't already do.
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenTelemetryTraceExporter.class);
                    LLMObservabilityRegistry registry = context.getBean(LLMObservabilityRegistry.class);
                    assertThat(registry.getInterceptors()).hasSize(1);
                });
    }

    @Test
    void canBeDisabledIndependentlyOfTheMasterSwitch() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues("meridian-argus.metrics.otel.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OpenTelemetryTraceExporter.class);
                    assertThat(context).hasSingleBean(LLMObservabilityRegistry.class);
                });
    }

    @Test
    void skippedEntirelyWhenTheOptionalModuleIsAbsentFromTheClasspath() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withClassLoader(new FilteredClassLoader(OpenTelemetryTraceExporter.class))
                .run(context -> assertThat(context).doesNotHaveBean(OpenTelemetryTraceExporter.class));
    }

    @Test
    void masterSwitchDisablesItToo() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues("meridian-argus.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenTelemetryTraceExporter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OpenTelemetryConfig {
        @Bean
        OpenTelemetry openTelemetry() {
            // A no-op-backed SDK is enough to prove the auto-configuration
            // wires against whatever OpenTelemetry bean exists -- this
            // module never creates or configures the SDK itself.
            return OpenTelemetrySdk.builder().build();
        }
    }
}
