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
 * Delivers alert events to a Microsoft Teams "Incoming Webhook" set up via
 * the current Workflows app (Teams retired the legacy Office 365 Connectors
 * incoming webhook in 2024/2025 — if your webhook URL was created through
 * "Workflows" &gt; "Post to a channel when a webhook request is received",
 * this notifier targets the right format; the old {@code {"text": "..."}}
 * flat payload the legacy connectors accepted no longer works).
 * <p>
 * Sends a minimal Adaptive Card wrapped in the required {@code "message"}
 * envelope. Verify against
 * <a href="https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook">
 * Microsoft's current documentation</a> if delivery fails — this integration
 * point has changed more than once and may again.
 * <p>
 * Uses the JDK's built-in {@link HttpClient} rather than pulling in an HTTP
 * library dependency.
 */
public final class TeamsWebhookNotifier implements AlertNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(TeamsWebhookNotifier.class);

    private final URI webhookUrl;
    private final HttpClient httpClient;

    public TeamsWebhookNotifier(URI webhookUrl) {
        this(webhookUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Visible for testing — inject a client pointed at a local mock server. */
    TeamsWebhookNotifier(URI webhookUrl, HttpClient httpClient) {
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public void notify(AlertEvent event) {
        String payload = buildAdaptiveCardPayload(event);

        HttpRequest request = HttpRequest.newBuilder(webhookUrl)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                LOG.warn("Teams webhook returned HTTP {} for alert '{}'", response.statusCode(), event.threshold().getName());
            }
        } catch (IOException e) {
            LOG.warn("Failed to deliver Teams alert notification for threshold '{}'", event.threshold().getName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while delivering Teams alert notification for threshold '{}'", event.threshold().getName(), e);
        }
    }

    private static String buildAdaptiveCardPayload(AlertEvent event) {
        AlertThreshold t = event.threshold();
        boolean triggered = event.state() == AlertEvent.State.TRIGGERED;
        String title = triggered
                ? "\uD83D\uDEA8 MeridianArgus alert triggered: " + t.getName()
                : "\u2705 MeridianArgus alert recovered: " + t.getName();
        String detail = String.format(
                "Spend is $%s against a threshold of $%s over the last %s.",
                event.currentSpendUsd().toPlainString(), t.getMaxCostUsd().toPlainString(), t.getWindow());

        return """
                {
                  "type": "message",
                  "attachments": [
                    {
                      "contentType": "application/vnd.microsoft.card.adaptive",
                      "content": {
                        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                        "type": "AdaptiveCard",
                        "version": "1.4",
                        "body": [
                          { "type": "TextBlock", "text": "%s", "weight": "Bolder", "size": "Medium", "wrap": true },
                          { "type": "TextBlock", "text": "%s", "wrap": true }
                        ]
                      }
                    }
                  ]
                }
                """.formatted(escapeJson(title), escapeJson(detail));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
