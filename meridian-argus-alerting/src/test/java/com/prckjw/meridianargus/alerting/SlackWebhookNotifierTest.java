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

class SlackWebhookNotifierTest {

    private HttpServer server;
    private final BlockingQueue<String> receivedBodies = new ArrayBlockingQueue<>(10);
    private int statusCodeToReturn = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.offer(body);
            byte[] response = new byte[0];
            exchange.sendResponseHeaders(statusCodeToReturn, response.length);
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

    private static AlertEvent sampleTriggeredEvent() {
        AlertThreshold threshold = new AlertThreshold("hourly-budget", new BigDecimal("50.00"), Duration.ofHours(1));
        return AlertEvent.triggered(threshold, new BigDecimal("62.50"), Instant.now());
    }

    @Test
    void postsJsonTextPayloadToTheWebhookUrl() throws Exception {
        SlackWebhookNotifier notifier = new SlackWebhookNotifier(webhookUri(), HttpClient.newHttpClient());

        notifier.notify(sampleTriggeredEvent());

        String body = receivedBodies.poll(5, TimeUnit.SECONDS);
        assertTrue(body != null, "expected the webhook to receive a request");
        assertTrue(body.startsWith("{\"text\": \""));
        assertTrue(body.contains("hourly-budget"));
        assertTrue(body.contains("62.5"));
        assertTrue(body.contains("50.0"));
    }

    @Test
    void recoveredEventProducesADifferentMessageThanTriggered() throws Exception {
        SlackWebhookNotifier notifier = new SlackWebhookNotifier(webhookUri(), HttpClient.newHttpClient());
        AlertThreshold threshold = new AlertThreshold("hourly-budget", new BigDecimal("50.00"), Duration.ofHours(1));

        notifier.notify(AlertEvent.recovered(threshold, new BigDecimal("10.00"), Instant.now()));

        String body = receivedBodies.poll(5, TimeUnit.SECONDS);
        assertTrue(body != null);
        assertTrue(body.contains("recovered"));
    }

    @Test
    void nonSuccessStatusCodeIsLoggedNotThrown() {
        statusCodeToReturn = 500;
        SlackWebhookNotifier notifier = new SlackWebhookNotifier(webhookUri(), HttpClient.newHttpClient());

        assertDoesNotThrow(() -> notifier.notify(sampleTriggeredEvent()));
    }

    @Test
    void unreachableHostDoesNotThrow() {
        // Port 1 is a well-known privileged/reserved port that will refuse
        // the connection immediately rather than hang.
        SlackWebhookNotifier notifier = new SlackWebhookNotifier(
                URI.create("http://localhost:1/webhook"), HttpClient.newHttpClient());

        assertDoesNotThrow(() -> notifier.notify(sampleTriggeredEvent()));
    }
}
