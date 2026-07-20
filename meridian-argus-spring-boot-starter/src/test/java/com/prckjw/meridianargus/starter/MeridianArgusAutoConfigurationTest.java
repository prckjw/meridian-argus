package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import com.prckjw.meridianargus.metrics.micrometer.MicrometerMetricsExporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MeridianArgusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MeridianArgusAutoConfiguration.class,
                    MeridianArgusMicrometerAutoConfiguration.class,
                    MeridianArgusSqliteAutoConfiguration.class));

    @Test
    void registersCoreBeansByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LLMObservabilityRegistry.class);
            assertThat(context).hasSingleBean(PricingConfig.class);
            assertThat(context).hasSingleBean(PricingEngine.class);
        });
    }

    @Test
    void disablingMeridianArgusRemovesAllBeans() {
        contextRunner
                .withPropertyValues("meridian-argus.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LLMObservabilityRegistry.class);
                    assertThat(context).doesNotHaveBean(PricingEngine.class);
                });
    }

    @Test
    void micrometerExporterIsRegisteredWhenMeterRegistryIsPresent() {
        contextRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MicrometerMetricsExporter.class);
                    // The exporter must also actually be registered as an
                    // interceptor, not just exist as a standalone bean.
                    LLMObservabilityRegistry registry = context.getBean(LLMObservabilityRegistry.class);
                    assertThat(registry.getInterceptors()).hasSize(1);
                });
    }

    @Test
    void micrometerExporterIsSkippedWhenNoMeterRegistryBeanExists() {
        // No MeterRegistry in the context at all -- the nested
        // MeridianArgusMicrometerAutoConfiguration should simply not activate.
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(MicrometerMetricsExporter.class));
    }

    @Test
    void contextStartsWhenMicrometerExporterClassIsAbsent() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(MicrometerMetricsExporter.class))
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(LLMObservabilityRegistry.class);
                    assertThat(context).doesNotHaveBean("micrometerMetricsExporter");
                });
    }

    @Test
    void micrometerExporterCanBeDisabledIndependentlyOfTheMasterSwitch() {
        contextRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .withPropertyValues("meridian-argus.metrics.micrometer.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MicrometerMetricsExporter.class);
                    // Core beans should still be present -- disabling the
                    // metrics path must not disable the whole library.
                    assertThat(context).hasSingleBean(LLMObservabilityRegistry.class);
                });
    }

    @Test
    void userSuppliedRegistryBeanTakesPrecedence() {
        contextRunner
                .withUserConfiguration(CustomRegistryConfig.class)
                .run(context -> {
                    LLMObservabilityRegistry registry = context.getBean(LLMObservabilityRegistry.class);
                    assertThat(registry).isSameAs(CustomRegistryConfig.CUSTOM_INSTANCE);
                });
    }

    @Test
    void userSuppliedPricingConfigIsUsedByPricingEngine() {
        contextRunner
                .withUserConfiguration(CustomPricingConfig.class)
                .run(context -> {
                    PricingConfig pricingConfig = context.getBean(PricingConfig.class);
                    PricingEngine pricingEngine = context.getBean(PricingEngine.class);

                    assertThat(pricingConfig).isSameAs(CustomPricingConfig.CUSTOM_INSTANCE);
                    assertThat(pricingEngine).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRegistryConfig {
        static final LLMObservabilityRegistry CUSTOM_INSTANCE = new LLMObservabilityRegistry();

        @Bean
        LLMObservabilityRegistry llmObservabilityRegistry() {
            return CUSTOM_INSTANCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPricingConfig {
        static final PricingConfig CUSTOM_INSTANCE = PricingConfig.loadDefault();

        @Bean
        PricingConfig pricingConfig() {
            return CUSTOM_INSTANCE;
        }
    }
}
