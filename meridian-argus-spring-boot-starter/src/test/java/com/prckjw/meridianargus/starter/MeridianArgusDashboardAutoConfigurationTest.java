package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.dashboard.DashboardController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MeridianArgusDashboardAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private WebApplicationContextRunner webContextRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MeridianArgusAutoConfiguration.class,
                        MeridianArgusDashboardAutoConfiguration.class));
    }

    @Test
    void disabledByDefaultEvenWithTheOptionalDependencyPresent() {
        webContextRunner().run(context -> assertThat(context).doesNotHaveBean(DashboardController.class));
    }

    @Test
    void createsControllerWhenExplicitlyEnabled() {
        webContextRunner()
                .withPropertyValues(
                        "meridian-argus.dashboard.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("test.db"))
                .run(context -> assertThat(context).hasSingleBean(DashboardController.class));
    }

    @Test
    void doesNotActivateInANonWebApplicationContext() {
        // A plain (non-web) ApplicationContextRunner -- @ConditionalOnWebApplication(SERVLET)
        // must keep this from activating even if every other condition is satisfied.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MeridianArgusAutoConfiguration.class,
                        MeridianArgusDashboardAutoConfiguration.class))
                .withPropertyValues(
                        "meridian-argus.dashboard.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("test.db"))
                .run(context -> assertThat(context).doesNotHaveBean(DashboardController.class));
    }

    @Test
    void skippedEntirelyWhenTheOptionalModuleIsAbsentFromTheClasspath() {
        webContextRunner()
                .withClassLoader(new FilteredClassLoader(DashboardController.class))
                .withPropertyValues(
                        "meridian-argus.dashboard.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("test.db"))
                .run(context -> assertThat(context).doesNotHaveBean(DashboardController.class));
    }

    @Test
    void masterSwitchDisablesItToo() {
        webContextRunner()
                .withPropertyValues(
                        "meridian-argus.enabled=false",
                        "meridian-argus.dashboard.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("test.db"))
                .run(context -> assertThat(context).doesNotHaveBean(DashboardController.class));
    }
}
