package com.ecommerce.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Readiness, per {@code docs/search/operations.md §2}.
 *
 * <p>An instance is ready only when Elasticsearch is usable and the consumers have
 * subscribed. Consumer <em>lag</em> deliberately does not affect readiness: lag makes data
 * stale, but the instance can still answer every query, so pulling it out of rotation
 * would only make an incident worse.
 *
 * <p>Liveness is left to the default process probe so a transient Elasticsearch outage
 * cannot trigger a restart storm.
 */
@Component("searchReadiness")
public class SearchReadinessIndicator implements HealthIndicator {

    private final ElasticsearchClient client;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    public SearchReadinessIndicator(ElasticsearchClient client, KafkaListenerEndpointRegistry listenerRegistry) {
        this.client = client;
        this.listenerRegistry = listenerRegistry;
    }

    @Override
    public Health health() {
        try {
            var response = client.cluster().health();
            boolean listenersRunning = listenerRegistry.getListenerContainers().stream()
                    .allMatch(container -> container.isRunning());
            if (!listenersRunning) {
                return Health.down().withDetail("kafka", "listeners have not subscribed yet").build();
            }
            return Health.up()
                    .withDetail("cluster_status", response.status().jsonValue())
                    .withDetail("kafka_listeners", listenerRegistry.getListenerContainers().size())
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("elasticsearch", String.valueOf(e.getMessage())).build();
        }
    }
}
