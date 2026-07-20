package com.prckjw.meridianargus.starter;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables MeridianArgus auto-configuration: an {@code LLMObservabilityRegistry}
 * bean, the pricing engine loaded from the bundled or user-supplied pricing
 * table, and — depending on which optional modules and properties are
 * present — a {@code MicrometerMetricsExporter} wired to the application's
 * {@code MeterRegistry}, and/or a {@code LocalStorageExporter} writing to a
 * local SQLite file (opt-in via {@code meridian-argus.metrics.sqlite.enabled=true}).
 * <p>
 * This annotation is a convenience marker on top of Spring Boot's regular
 * auto-configuration mechanism ({@code MeridianArgusAutoConfiguration},
 * {@code MeridianArgusMicrometerAutoConfiguration}, and
 * {@code MeridianArgusSqliteAutoConfiguration} are also registered
 * automatically via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * so this explicit {@code @Import} is redundant in practice — it exists
 * purely so the annotation's own declaration matches what it documents).
 * Adding it explicitly is optional but documents intent clearly on the
 * application's entry point:
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableLLMObservability
 * public class MyApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApplication.class, args);
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import({
        MeridianArgusAutoConfiguration.class,
        MeridianArgusMicrometerAutoConfiguration.class,
        MeridianArgusSqliteAutoConfiguration.class
})
public @interface EnableLLMObservability {
}
