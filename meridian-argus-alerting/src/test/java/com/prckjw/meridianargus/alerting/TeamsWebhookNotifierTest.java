package com.prckjw.meridianargus.alerting;

import com.prckjw.meridianargus.core.alert.AlertThreshold;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamsWebhookNotifierTest {

    private HttpServer server;
    private final BlockingQueue<String> receivedBodies = new ArrayBlockingQueue<>(10);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.offer(body);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI webhookUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/webhook");
    }

    @Test
    void postsAdaptiveCardEnvelopeToTheWebhookUrl() throws Exception {
        TeamsWebhookNotifier notifier = new TeamsWebhookNotifier(webhookUri(), HttpClient.newHttpClient());
        AlertThreshold threshold = new AlertThreshold("daily-budget", new BigDecimal("500.00"), Duration.ofDays(1));

        notifier.notify(AlertEvent.triggered(threshold, new BigDecimal("612.00"), Instant.now()));

        String body = receivedBodies.poll(5, TimeUnit.SECONDS);
        assertTrue(body != null, "expected the webhook to receive a request");
        // Must match the "message" + "attachments" envelope Teams Workflows
        // expects -- the legacy flat {"text": "..."} shape no longer works.
        assertTrue(body.contains("\"type\": \"message\""));
        assertTrue(body.contains("\"contentType\": \"application/vnd.microsoft.card.adaptive\""));
        assertTrue(body.contains("\"type\": \"AdaptiveCard\""));
        assertTrue(body.contains("daily-budget"));
        assertTrue(body.contains("612.0"));
    }

    @Test
    void doesNotThrowOnUnreachableHost() {
        TeamsWebhookNotifier notifier = new TeamsWebhookNotifier(
                URI.create("http://localhost:1/webhook"), HttpClient.newHttpClient());
        AlertThreshold threshold = new AlertThreshold("test", new BigDecimal("1"), Duration.ofHours(1));

        assertDoesNotThrow(() -> notifier.notify(AlertEvent.triggered(threshold, BigDecimal.TEN, Instant.now())));
    }
}
