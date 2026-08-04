package com.prckjw.meridianargus.dashboard;

import com.prckjw.meridianargus.metrics.sqlite.ProviderModelCostSummary;
import com.prckjw.meridianargus.metrics.sqlite.UsageQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Serves the dashboard's single static HTML page plus the JSON API it calls.
 * <p>
 * Not annotated with {@code @Controller}/{@code @RestController} for
 * component-scan discovery purposes — this class is registered explicitly
 * as a {@code @Bean} by {@code MeridianArgusDashboardAutoConfiguration} in
 * the Spring Boot starter, since it lives in a library package that a
 * consumer's component scan wouldn't otherwise reach. It still carries
 * {@code @RestController}/{@code @GetMapping} because Spring MVC's handler
 * mapping recognizes those stereotypes on any bean in the context,
 * regardless of how the bean was registered.
 */
@RestController
public final class DashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardController.class);
    private static final String INDEX_HTML_CLASSPATH_LOCATION = "meridian-argus-dashboard/index.html";

    private final UsageQuery usageQuery;
    private final String indexHtml;

    public DashboardController(UsageQuery usageQuery) {
        this.usageQuery = usageQuery;
        this.indexHtml = loadIndexHtml();
    }

    @GetMapping(value = "/meridian-argus/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> dashboardPage() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(indexHtml);
    }

    @GetMapping(value = "/meridian-argus/dashboard/api/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DashboardSummaryResponse> summary(
            @RequestParam(name = "windowHours", defaultValue = "24") long windowHours) {

        if (windowHours <= 0) {
            return ResponseEntity.badRequest().build();
        }

        Duration lookback = Duration.ofHours(windowHours);
        List<ProviderModelCostSummary> summaries = usageQuery.costByProviderModel(lookback);

        List<ProviderModelRow> rows = summaries.stream()
                .map(s -> new ProviderModelRow(
                        s.provider(),
                        s.model(),
                        s.callCount(),
                        s.errorCount(),
                        s.totalTokens(),
                        s.totalCostUsd() == null ? BigDecimal.ZERO : s.totalCostUsd(),
                        s.hasKnownCost()))
                .toList();

        long totalCalls = summaries.stream().mapToLong(ProviderModelCostSummary::callCount).sum();
        long totalErrors = summaries.stream().mapToLong(ProviderModelCostSummary::errorCount).sum();
        BigDecimal totalCost = usageQuery.totalCost(lookback);

        return ResponseEntity.ok(new DashboardSummaryResponse(windowHours, totalCost, totalCalls, totalErrors, rows));
    }

    private static String loadIndexHtml() {
        try (InputStream is = DashboardController.class.getClassLoader()
                .getResourceAsStream(INDEX_HTML_CLASSPATH_LOCATION)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Could not find " + INDEX_HTML_CLASSPATH_LOCATION + " on the classpath -- "
                                + "this should be bundled inside meridian-argus-web-dashboard's jar.");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Failed to load the MeridianArgus dashboard HTML page", e);
            throw new UncheckedIOException(e);
        }
    }
}
