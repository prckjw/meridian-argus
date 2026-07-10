package com.prckjw.meridianargus.starter;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables MeridianArgus auto-configuration: an {@code LLMObservabilityRegistry}
 * bean, the pricing engine loaded from the bundled or user-supplied pricing
 * table, and (if micrometer-core is on the classpath) a
 * {@code MicrometerMetricsExporter} wired to the application's
 * {@code MeterRegistry}.
 * <p>
 * This annotation is a convenience marker on top of Spring Boot's regular
 * auto-configuration mechanism ({@code MeridianArgusAutoConfiguration} is
 * also registered automatically via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}).
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
@Import(MeridianArgusAutoConfiguration.class)
public @interface EnableLLMObservability {
}
