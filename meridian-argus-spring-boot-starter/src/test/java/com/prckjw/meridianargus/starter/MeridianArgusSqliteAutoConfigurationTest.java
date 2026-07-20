package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.metrics.sqlite.LocalStorageExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MeridianArgusSqliteAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MeridianArgusAutoConfiguration.class,
                    MeridianArgusSqliteAutoConfiguration.class));

    @TempDir
    Path tempDir;

    @Test
    void disabledByDefaultEvenWithTheOptionalDependencyPresent() {
        // Unlike the Micrometer exporter, this one must not activate just
        // because the optional module is on the classpath -- it needs an
        // explicit opt-in, since it writes to disk.
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(LocalStorageExporter.class));
    }

    @Test
    void createsExporterAndRegistersItWhenExplicitlyEnabled() {
        Path dbFile = tempDir.resolve("test.db");

        contextRunner
                .withPropertyValues(
                        "meridian-argus.metrics.sqlite.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + dbFile)
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalStorageExporter.class);
                    assertThat(dbFile.toFile()).exists();

                    LLMObservabilityRegistry registry = context.getBean(LLMObservabilityRegistry.class);
                    assertThat(registry.getInterceptors()).hasSize(1);
                });
    }

    @Test
    void skippedEntirelyWhenTheOptionalModuleIsAbsentFromTheClasspath() {
        // This is exactly the scenario the string-based @ConditionalOnClass
        // protects against -- see CONTRIBUTING.md's "New exporters" section.
        contextRunner
                .withClassLoader(new FilteredClassLoader(LocalStorageExporter.class))
                .withPropertyValues(
                        "meridian-argus.metrics.sqlite.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("unreachable.db"))
                .run(context -> assertThat(context).doesNotHaveBean(LocalStorageExporter.class));
    }

    @Test
    void masterSwitchDisablesItToo() {
        contextRunner
                .withPropertyValues(
                        "meridian-argus.enabled=false",
                        "meridian-argus.metrics.sqlite.enabled=true",
                        "meridian-argus.metrics.sqlite.path=" + tempDir.resolve("test.db"))
                .run(context -> assertThat(context).doesNotHaveBean(LocalStorageExporter.class));
    }
}
