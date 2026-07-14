package com.prckjw.meridianargus.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test: boots the full Spring context (including
 * auto-configuration) on a random port, hits the real HTTP endpoints, and
 * checks the Prometheus scrape output.
 * <p>
 * This is deliberately the kind of test that would have caught the
 * NoClassDefFoundError class of bug fixed in
 * MeridianArgusMicrometerAutoConfiguration — a plain unit test against the
 * auto-configuration classes in isolation doesn't exercise the real
 * classpath assembled by Maven for this module, but booting the actual demo
 * application does.
 * <p>
 * {@code @AutoConfigureTestRestTemplate} is required as of Spring Boot 4:
 * {@code TestRestTemplate} moved out of {@code spring-boot-test} into the
 * separate {@code spring-boot-resttestclient} module and is no longer
 * auto-configured for injection by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DemoApplicationSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Intentionally empty -- if MeridianArgusAutoConfiguration or
        // MeridianArgusMicrometerAutoConfiguration can't wire up cleanly
        // against this module's actual dependency set, this test fails at
        // context startup before ever reaching an assertion.
    }

    @Test
    void askEndpointReturnsASimulatedResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/ask?prompt=What+is+observability"), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("What is observability");
    }

    @Test
    void prometheusEndpointExposesMeridianArgusMeters() {
        // Exercise the /ask endpoint first so there's at least one recorded call.
        restTemplate.getForEntity(url("/ask?prompt=warm+up"), String.class);

        ResponseEntity<String> scrape = restTemplate.getForEntity(url("/actuator/prometheus"), String.class);

        assertThat(scrape.getStatusCode().is2xxSuccessful()).isTrue();
        String body = scrape.getBody();
        assertThat(body).contains("meridian_argus_calls_total");
        assertThat(body).contains("meridian_argus_tokens_input_total");
        assertThat(body).contains("meridian_argus_tokens_output_total");
        assertThat(body).contains("meridian_argus_latency_seconds");
        assertThat(body).contains("provider=\"openai\"");
        assertThat(body).contains("model=\"gpt-4o\"");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
