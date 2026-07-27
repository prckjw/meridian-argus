package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.alerting.AlertNotifier;
import com.prckjw.meridianargus.alerting.RollingWindowAlertEvaluator;
import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MeridianArgusAlertingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MeridianArgusAutoConfiguration.class,
                    MeridianArgusAlertingAutoConfiguration.class));

    @Test
    void disabledByDefaultEvenWithTheOptionalDependencyPresent() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(RollingWindowAlertEvaluator.class));
    }

    @Test
    void enablingWithoutAnyThresholdsFailsFast() {
        contextRunner
                .withPropertyValues("meridian-argus.alerting.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage(
                                "meridian-argus.alerting.enabled=true but no thresholds are configured under "
                                        + "meridian-argus.alerting.thresholds — at least one is required."));
    }

    @Test
    void createsEvaluatorAndRegistersItWhenThresholdConfigured() {
        contextRunner
                .withPropertyValues(
                        "meridian-argus.alerting.enabled=true",
                        "meridian-argus.alerting.thresholds[0].name=hourly-budget",
                        "meridian-argus.alerting.thresholds[0].max-cost-usd=50.00",
                        "meridian-argus.alerting.thresholds[0].window=PT1H")
                .run(context -> {
                    assertThat(context).hasSingleBean(RollingWindowAlertEvaluator.class);

                    LLMObservabilityRegistry registry = context.getBean(LLMObservabilityRegistry.class);
                    assertThat(registry.getInterceptors()).hasSize(1);
                });
    }

    @Test
    void userSuppliedNotifierBeanIsPickedUpAutomatically() {
        contextRunner
                .withUserConfiguration(CustomNotifierConfig.class)
                .withPropertyValues(
                        "meridian-argus.alerting.enabled=true",
                        "meridian-argus.alerting.thresholds[0].name=hourly-budget",
                        "meridian-argus.alerting.thresholds[0].max-cost-usd=50.00",
                        "meridian-argus.alerting.thresholds[0].window=PT1H")
                .run(context -> {
                    // No direct getter for the notifier list, but a clean
                    // context start (no wiring exception for the
                    // List<AlertNotifier> constructor parameter) combined
                    // with the custom bean being present in the context is
                    // enough to confirm it was collected without error.
                    assertThat(context).hasSingleBean(RollingWindowAlertEvaluator.class);
                    assertThat(context).hasSingleBean(AlertNotifier.class);
                });
    }

    @Test
    void skippedEntirelyWhenTheOptionalModuleIsAbsentFromTheClasspath() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(RollingWindowAlertEvaluator.class))
                .withPropertyValues(
                        "meridian-argus.alerting.enabled=true",
                        "meridian-argus.alerting.thresholds[0].name=hourly-budget",
                        "meridian-argus.alerting.thresholds[0].max-cost-usd=50.00",
                        "meridian-argus.alerting.thresholds[0].window=PT1H")
                .run(context -> assertThat(context).doesNotHaveBean(RollingWindowAlertEvaluator.class));
    }

    @Test
    void masterSwitchDisablesItToo() {
        contextRunner
                .withPropertyValues(
                        "meridian-argus.enabled=false",
                        "meridian-argus.alerting.enabled=true",
                        "meridian-argus.alerting.thresholds[0].name=hourly-budget",
                        "meridian-argus.alerting.thresholds[0].max-cost-usd=50.00",
                        "meridian-argus.alerting.thresholds[0].window=PT1H")
                .run(context -> assertThat(context).doesNotHaveBean(RollingWindowAlertEvaluator.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomNotifierConfig {
        @Bean
        AlertNotifier customNotifier() {
            return event -> {
            };
        }
    }
}
