package com.ecommerce.search.worker;

import com.ecommerce.search.domain.event.CloudEventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the <b>temporary</b> consumer group used during the
 * catch-up phase of a Full Reindex.
 *
 * <p>Unlike the main {@link KafkaConsumerConfig} which has a fixed group ID
 * ({@code search-product-indexer}) and runs continuously, the reindex consumer
 * group ({@code search-reindex-{run_id}}) is created dynamically at reindex
 * time and torn down when the catch-up phase completes.
 *
 * <p>The actual dynamic endpoint registration (start / stop by run_id) is
 * managed by {@link KafkaListenerEndpointRegistry} and orchestrated by the
 * Reindex Job (Module 10).
 */
@Slf4j
@Configuration
public class ReindexConsumerConfig {

    /**
     * Consumer factory for the reindex consumer group.
     *
     * <p>Configuration mirrors the main consumer except:
     * <ul>
     *   <li>Group ID is <em>not</em> set here — it is supplied dynamically
     *       by the {@link KafkaListenerEndpointRegistry} when the reindex
     *       endpoint is registered.</li>
     *   <li>{@code auto.offset.reset} is set to {@code latest} because the
     *       reindex consumer starts from a watermark offset rather than the
     *       beginning of the topic.</li>
     * </ul>
     */
    @Bean
    public ConsumerFactory<String, CloudEventEnvelope> reindexConsumerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        // Do NOT set GROUP_ID_CONFIG here — it is provided by the endpoint
        // registration at runtime.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            org.apache.kafka.common.serialization.StringDeserializer.class);

        // Resilient value deserialisation (same as main consumer)
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ecommerce.search.domain.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
            "com.ecommerce.search.domain.event.CloudEventEnvelope");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Listener container factory for reindex consumers.
     *
     * <p>Differs from the main factory:
     * <ul>
     *   <li><b>Single-record</b> processing (not batch) — each event is
     *       acknowledged individually so progress can be checkpointed.</li>
     *   <li>Concurrency is set to {@code 1} — the reindex job uses a
     *       single-threaded consumer to avoid partition rebalance complexity
     *       during the catch-up phase.</li>
     * </ul>
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope>
    reindexListenerContainerFactory(
            ConsumerFactory<String, CloudEventEnvelope> reindexConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope> factory =
            new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(reindexConsumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setBatchListener(false);

        return factory;
    }

    /**
     * Expose the {@link KafkaListenerEndpointRegistry} so that the Reindex Job
     * (Module 10) can dynamically register and remove listener endpoints for
     * the temporary consumer group.
     *
     * <p>Spring Boot auto-configures a registry bean; this method simply
     * re-exposes it with a named qualifier for clarity.
     */
    @Bean
    public KafkaListenerEndpointRegistry reindexEndpointRegistry(
            KafkaListenerEndpointRegistry registry) {
        return registry;
    }
}
