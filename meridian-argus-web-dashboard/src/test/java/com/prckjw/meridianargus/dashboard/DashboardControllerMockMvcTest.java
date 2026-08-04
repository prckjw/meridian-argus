package com.prckjw.meridianargus.dashboard;

import com.prckjw.meridianargus.core.model.LLMCallRecord;
import com.prckjw.meridianargus.core.model.ProviderModel;
import com.prckjw.meridianargus.core.model.TokenUsage;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import com.prckjw.meridianargus.metrics.sqlite.LocalStorageExporter;
import com.prckjw.meridianargus.metrics.sqlite.UsageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the controller through actual Spring MVC dispatch (routing,
 * argument resolution, Jackson serialization) via a standalone MockMvc
 * setup -- no full Spring Boot context needed, but unlike calling the
 * controller's Java methods directly, this catches mapping/serialization
 * mistakes that a plain unit test wouldn't (the same category of bug the
 * demo app's end-to-end smoke test exists to catch elsewhere in this
 * project).
 */
class DashboardControllerMockMvcTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Path dbFile = tempDir.resolve("test.db");
        String yaml = """
                providers:
                  openai:
                    gpt-4o:
                      input: 1.0
                      output: 1.0
                """;
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        PricingEngine pricingEngine = new PricingEngine(PricingConfig.loadFromStream(is));

        try (LocalStorageExporter exporter = new LocalStorageExporter(pricingEngine, dbFile)) {
            exporter.onCallCompleted(LLMCallRecord.builder()
                    .providerModel(ProviderModel.of("openai", "gpt-4o"))
                    .tokenUsage(TokenUsage.of(1000, 1000))
                    .build());
        }

        DashboardController controller = new DashboardController(new UsageQuery(dbFile));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void dashboardPageRoutesCorrectlyAndReturnsHtml() throws Exception {
        mockMvc.perform(get("/meridian-argus/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MeridianArgus Dashboard")));
    }

    @Test
    void summaryEndpointRoutesCorrectlyAndSerializesJson() throws Exception {
        mockMvc.perform(get("/meridian-argus/dashboard/api/summary").param("windowHours", "24"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.windowHours").value(24))
                .andExpect(jsonPath("$.totalCalls").value(1))
                .andExpect(jsonPath("$.totalCostUsd").value(2.0))
                .andExpect(jsonPath("$.byProviderModel[0].provider").value("openai"))
                .andExpect(jsonPath("$.byProviderModel[0].model").value("gpt-4o"));
    }

    @Test
    void summaryEndpointDefaultsTo24HoursWhenParamOmitted() throws Exception {
        mockMvc.perform(get("/meridian-argus/dashboard/api/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowHours").value(24));
    }

    @Test
    void summaryEndpointReturns400ForInvalidWindow() throws Exception {
        mockMvc.perform(get("/meridian-argus/dashboard/api/summary").param("windowHours", "0"))
                .andExpect(status().isBadRequest());
    }
}
