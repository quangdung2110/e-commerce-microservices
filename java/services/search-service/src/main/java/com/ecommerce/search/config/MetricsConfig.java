package com.ecommerce.search.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics configuration for the Search Service.
 *
 * <p>Configures Micrometer with:
 * <ul>
 *   <li>Common tags (service name) for all metrics</li>
 *   <li>A meter filter that only accepts metrics matching the {@code search.} prefix</li>
 * </ul>
 *
 * <p>The 5 essential metrics (HTTP, ES query, Kafka lag, indexing, errors) are
 * registered and recorded via {@link com.ecommerce.search.util.MetricsRecorder}.
 *
 * @see com.ecommerce.search.util.MetricsRecorder
 */
@Configuration
public class MetricsConfig {

    /**
     * Adds the {@code service=search-service} tag to every metric emitted by this
     * application, enabling cross-service metric filtering in Prometheus/Grafana.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("service", "search-service");
    }

    /**
     * Only accept metric names starting with one of the recognised prefixes,
     * filtering out thousands of auto-generated JVM/Spring Boot metric names
     * that are not useful for service-level alerting.
     */
    @Bean
    public MeterFilter meterFilter() {
        return MeterFilter.acceptNameStartsWith("search.");
    }
}
