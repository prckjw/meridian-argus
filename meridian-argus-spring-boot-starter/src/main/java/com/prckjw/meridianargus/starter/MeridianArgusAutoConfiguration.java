package com.prckjw.meridianargus.starter;

import com.prckjw.meridianargus.core.interceptor.LLMObservabilityRegistry;
import com.prckjw.meridianargus.core.pricing.PricingConfig;
import com.prckjw.meridianargus.core.pricing.PricingEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Auto-configuration entry point. Activated automatically when this starter
 * is on the classpath and {@code meridian-argus.enabled} is not explicitly
 * set to false (see {@code META-INF/spring/...AutoConfiguration.imports}).
 * <p>
 * Beans created:
 * <ul>
 *   <li>{@link LLMObservabilityRegistry} - always, if enabled</li>
 *   <li>{@link PricingConfig} - loads the bundled or user-supplied pricing YAML</li>
 *   <li>{@link PricingEngine} - estimates costs using the active pricing config</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(MeridianArgusProperties.class)
@ConditionalOnProperty(prefix = "meridian-argus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MeridianArgusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LLMObservabilityRegistry llmObservabilityRegistry() {
        return new LLMObservabilityRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public PricingConfig pricingConfig(MeridianArgusProperties properties) {
        return loadPricingConfig(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public PricingEngine pricingEngine(PricingConfig pricingConfig) {
        return new PricingEngine(pricingConfig);
    }

    private PricingConfig loadPricingConfig(MeridianArgusProperties properties) {
        if (properties.getPricingConfigPath() == null) {
            return PricingConfig.loadDefault();
        }
        Resource resource = new DefaultResourceLoader().getResource(properties.getPricingConfigPath());
        try (InputStream is = resource.getInputStream()) {
            return PricingConfig.loadFromStream(is);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load pricing config from " + properties.getPricingConfigPath(), e);
        }
    }
}
