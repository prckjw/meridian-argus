package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.alerting.AlertNotifier;
import com.prckjw.meridianargus.alerting.RollingWindowAlertEvaluator;
import com.prckjw.meridianargus.alerting.SlackWebhookNotifier;
import com.prckjw.meridianargus.alerting.TeamsWebhookNotifier;
import com.prckjw.meridianargus.core.alert.AlertThreshold;
import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Kept as its own {@code @AutoConfiguration} class for the same reason as
 * {@link MeridianArgusMicrometerAutoConfiguration} and
 * {@link MeridianArgusSqliteAutoConfiguration} — see {@code CONTRIBUTING.md}'s
 * "New exporters" section.
 * <p>
 * Opt-in via {@code meridian-argus.alerting.enabled=true}: this actively
 * sends HTTP requests to external webhooks on threshold breaches, which
 * shouldn't happen just because the module is present.
 * <p>
 * Any {@link AlertNotifier} beans you define yourself (e.g. a custom
 * PagerDuty or email notifier) are automatically collected and combined
 * with the Slack/Teams webhook notifiers built from configuration — no
 * need to wire them in manually.
 */
@AutoConfiguration(after = MeridianArgusAutoConfiguration.class)
@ConditionalOnClass(name = "com.prckjw.meridianargus.alerting.RollingWindowAlertEvaluator")
@ConditionalOnProperty(prefix = "meridian-argus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeridianArgusAlertingAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "meridian-argus.alerting", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public RollingWindowAlertEvaluator rollingWindowAlertEvaluator(
            PricingEngine pricingEngine,
            LLMObservabilityRegistry registry,
            MeridianArgusProperties properties,
            List<AlertNotifier> userSuppliedNotifiers) {

        List<AlertThreshold> thresholds = properties.getAlerting().getThresholds().stream()
                .map(t -> new AlertThreshold(t.getName(), t.getMaxCostUsd(), t.getWindow()))
                .toList();

        if (thresholds.isEmpty()) {
            throw new IllegalStateException(
                    "meridian-argus.alerting.enabled=true but no thresholds are configured under "
                            + "meridian-argus.alerting.thresholds — at least one is required.");
        }

        List<AlertNotifier> notifiers = new ArrayList<>(userSuppliedNotifiers);

        String slackUrl = properties.getAlerting().getSlack().getWebhookUrl();
        if (slackUrl != null && !slackUrl.isBlank()) {
            notifiers.add(new SlackWebhookNotifier(URI.create(slackUrl)));
        }

        String teamsUrl = properties.getAlerting().getTeams().getWebhookUrl();
        if (teamsUrl != null && !teamsUrl.isBlank()) {
            notifiers.add(new TeamsWebhookNotifier(URI.create(teamsUrl)));
        }

        RollingWindowAlertEvaluator evaluator = new RollingWindowAlertEvaluator(pricingEngine, thresholds, notifiers);
        registry.register(evaluator);
        return evaluator;
    }
}
