package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.dashboard.DashboardController;
import com.prckjw.meridianargus.metrics.sqlite.UsageQuery;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Kept as its own {@code @AutoConfiguration} class for the same reason as
 * the Micrometer, SQLite, and Alerting auto-configurations — see
 * {@code CONTRIBUTING.md}'s "New exporters" section.
 * <p>
 * Opt-in via {@code meridian-argus.dashboard.enabled=true}: this exposes an
 * HTTP endpoint, which shouldn't happen just because the module is present.
 * Requires a servlet-based web application (not reactive, not a non-web
 * app) — there's no other sensible place to host a controller.
 * <p>
 * Reads from the same database file {@code meridian-argus.metrics.sqlite.path}
 * points at; see {@link MeridianArgusProperties.Dashboard} for why there's
 * no separate path property.
 */
@AutoConfiguration(after = {MeridianArgusAutoConfiguration.class, MeridianArgusSqliteAutoConfiguration.class})
@ConditionalOnClass(name = {
        "com.prckjw.meridianargus.dashboard.DashboardController",
        "com.prckjw.meridianargus.metrics.sqlite.UsageQuery"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "meridian-argus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeridianArgusDashboardAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "meridian-argus.dashboard", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DashboardController meridianArgusDashboardController(MeridianArgusProperties properties) {
        String path = properties.getMetrics().getSqlite().getPath();
        return new DashboardController(new UsageQuery(Path.of(path)));
    }
}
