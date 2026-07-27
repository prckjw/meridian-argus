package com.prckjw.meridianargus.alerting;

import com.prckjw.meridianargus.core.alert.AlertThreshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Delivers alert events to a Slack "Incoming Webhook" (the classic
 * {@code https://hooks.slack.com/services/...} URL you get from Slack's
 * app configuration page). Uses the plain {@code {"text": "..."}} payload
 * shape, which every incoming webhook accepts regardless of workspace
 * configuration — no Block Kit dependency needed for a simple text alert.
 * <p>
 * Uses the JDK's built-in {@link HttpClient} rather than pulling in an HTTP
 * library dependency.
 */
public final class SlackWebhookNotifier implements AlertNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(SlackWebhookNotifier.class);

    private final URI webhookUrl;
    private final HttpClient httpClient;

    public SlackWebhookNotifier(URI webhookUrl) {
        this(webhookUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Visible for testing — inject a client pointed at a local mock server. */
    SlackWebhookNotifier(URI webhookUrl, HttpClient httpClient) {
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public void notify(AlertEvent event) {
        String payload = "{\"text\": \"" + escapeJson(buildMessage(event)) + "\"}";

        HttpRequest request = HttpRequest.newBuilder(webhookUrl)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                LOG.warn("Slack webhook returned HTTP {} for alert '{}'", response.statusCode(), event.threshold().getName());
            }
        } catch (IOException e) {
            LOG.warn("Failed to deliver Slack alert notification for threshold '{}'", event.threshold().getName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while delivering Slack alert notification for threshold '{}'", event.threshold().getName(), e);
        }
    }

    private static String buildMessage(AlertEvent event) {
        AlertThreshold t = event.threshold();
        return switch (event.state()) {
            case TRIGGERED -> String.format(
                    ":rotating_light: MeridianArgus alert *%s* triggered — spend $%s exceeded threshold $%s over the last %s",
                    t.getName(), event.currentSpendUsd().toPlainString(), t.getMaxCostUsd().toPlainString(), t.getWindow());
            case RECOVERED -> String.format(
                    ":white_check_mark: MeridianArgus alert *%s* recovered — spend is now $%s (threshold $%s over %s)",
                    t.getName(), event.currentSpendUsd().toPlainString(), t.getMaxCostUsd().toPlainString(), t.getWindow());
        };
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
