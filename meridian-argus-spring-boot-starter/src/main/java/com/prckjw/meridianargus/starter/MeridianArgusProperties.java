package com.prckjw.meridianargus.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 *     sqlite:
 *       enabled: false
 *       path: meridian-argus.db
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

    public static class Metrics {
        private final Micrometer micrometer = new Micrometer();
        private final Sqlite sqlite = new Sqlite();

        public Micrometer getMicrometer() {
            return micrometer;
        }

        public Sqlite getSqlite() {
            return sqlite;
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
}
