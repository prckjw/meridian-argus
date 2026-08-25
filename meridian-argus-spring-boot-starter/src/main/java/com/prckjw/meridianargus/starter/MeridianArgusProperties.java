package com.prckjw.meridianargus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds to {@code meridian-argus.*} in application.yml / application.properties.
 *
 * <pre>{@code
 * meridian-argus:
 *   enabled: true
 *   pricing-config-path: classpath:custom-pricing.yaml   # optional override
 *   metrics:
 *     micrometer:
 *       enabled: true
 *     otel:
 *       enabled: true
 *     sqlite:
 *       enabled: false
 *       path: meridian-argus.db
 *   alerting:
 *     enabled: false
 *     thresholds:
 *       - name: hourly-budget
 *         max-cost-usd: 50.00
 *         window: PT1H
 *     slack:
 *       webhook-url: https://hooks.slack.com/services/...
 *     teams:
 *       webhook-url: https://...webhook.office.com/...
 *   dashboard:
 *     enabled: false
 * }</pre>
 */
@ConfigurationProperties(prefix = "meridian-argus")
public class MeridianArgusProperties {

    /** Master switch. When false, no auto-configuration beans are created. */
    private boolean enabled = true;

    /**
     * Optional override for the pricing table. Defaults to the bundled
     * default-pricing.yaml shipped in meridian-argus-core.
     */
    private String pricingConfigPath;

    private final Metrics metrics = new Metrics();
    private final Alerting alerting = new Alerting();
    private final Dashboard dashboard = new Dashboard();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPricingConfigPath() {
        return pricingConfigPath;
    }

    public void setPricingConfigPath(String pricingConfigPath) {
        this.pricingConfigPath = pricingConfigPath;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Alerting getAlerting() {
        return alerting;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public static class Metrics {
        private final Micrometer micrometer = new Micrometer();
        private final Sqlite sqlite = new Sqlite();
        private final Otel otel = new Otel();

        public Micrometer getMicrometer() {
            return micrometer;
        }

        public Sqlite getSqlite() {
            return sqlite;
        }

        public Otel getOtel() {
            return otel;
        }

        public static class Micrometer {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }

        public static class Otel {
            /**
             * Defaults to true, same reasoning as Micrometer: this doesn't
             * make any network call or write anything on its own — it
             * only adds spans to an {@code OpenTelemetry} SDK the
             * application has already configured for its own purposes.
             * Requires that bean to be present regardless (see
             * {@code MeridianArgusOtelAutoConfiguration}); if it's absent,
             * nothing activates whether this is true or false.
             */
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }

        public static class Sqlite {
            /**
             * Opt-in, unlike the Micrometer exporter: writing to a local
             * file is an active side effect the consumer should choose
             * deliberately, rather than something that activates just
             * because a dependency happens to be on the classpath.
             */
            private boolean enabled = false;

            /** Path to the SQLite database file, relative or absolute. Parent directories are created if missing. */
            private String path = "meridian-argus.db";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }
        }
    }

    public static class Alerting {
        /**
         * Opt-in, same reasoning as the SQLite exporter: this actively
         * sends HTTP requests to an external webhook, which shouldn't
         * happen just because the module is on the classpath.
         */
        private boolean enabled = false;

        private List<ThresholdProperties> thresholds = new ArrayList<>();
        private final Slack slack = new Slack();
        private final Teams teams = new Teams();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<ThresholdProperties> getThresholds() {
            return thresholds;
        }

        public void setThresholds(List<ThresholdProperties> thresholds) {
            this.thresholds = thresholds;
        }

        public Slack getSlack() {
            return slack;
        }

        public Teams getTeams() {
            return teams;
        }

        /** One rolling-window threshold; maps 1:1 to core's {@code AlertThreshold}. */
        public static class ThresholdProperties {
            private String name;
            private BigDecimal maxCostUsd;
            /** Spring Boot binds plain ISO-8601 duration strings (e.g. {@code PT1H}, {@code P1D}) out of the box. */
            private Duration window;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public BigDecimal getMaxCostUsd() {
                return maxCostUsd;
            }

            public void setMaxCostUsd(BigDecimal maxCostUsd) {
                this.maxCostUsd = maxCostUsd;
            }

            public Duration getWindow() {
                return window;
            }

            public void setWindow(Duration window) {
                this.window = window;
            }
        }

        public static class Slack {
            /** A Slack "Incoming Webhook" URL. Left unset (no notifier registered) if blank. */
            private String webhookUrl;

            public String getWebhookUrl() {
                return webhookUrl;
            }

            public void setWebhookUrl(String webhookUrl) {
                this.webhookUrl = webhookUrl;
            }
        }

        public static class Teams {
            /** A Microsoft Teams "Incoming Webhook" URL (via the Workflows app). Left unset (no notifier registered) if blank. */
            private String webhookUrl;

            public String getWebhookUrl() {
                return webhookUrl;
            }

            public void setWebhookUrl(String webhookUrl) {
                this.webhookUrl = webhookUrl;
            }
        }
    }

    public static class Dashboard {
        /**
         * Opt-in, same reasoning as the SQLite exporter and alerting: this
         * exposes an HTTP endpoint, which shouldn't happen just because the
         * module is present.
         * <p>
         * There is deliberately no separate {@code dashboard.path} property
         * — the dashboard reads from the same file
         * {@code meridian-argus.metrics.sqlite.path} points at, since it's
         * a read-only view over data the SQLite exporter writes. Enabling
         * the dashboard without also enabling
         * {@code meridian-argus.metrics.sqlite.enabled} is allowed (e.g. if
         * you want to point it at a database file being written by a
         * separate process) but the dashboard will simply show no data
         * until something writes to that path.
         */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
