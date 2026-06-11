package com.platform.productservice.infrastructure.kafka.deserializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class CloudEventDeserializer implements Deserializer<CloudEvent> {

    private static final Logger log = LoggerFactory.getLogger(CloudEventDeserializer.class);
    private static final String CORRELATION_ID_HEADER = "correlation_id";
    private static final String PARTITION_KEY_HEADER = "partition_key";

    private final ObjectMapper objectMapper;

    public CloudEventDeserializer() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void configure(java.util.Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public CloudEvent deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }

        try {
            CloudEvent cloudEvent = objectMapper.readValue(data, CloudEvent.class);
            return cloudEvent;
        } catch (Exception e) {
            log.error("Failed to deserialize CloudEvent from topic {}: {}", topic, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public CloudEvent deserialize(String topic, Headers headers, byte[] data) {
        CloudEvent cloudEvent = deserialize(topic, data);
        if (cloudEvent == null) {
            return null;
        }

        if (headers != null) {
            extractHeader(headers, CORRELATION_ID_HEADER)
                    .ifPresent(cloudEvent::setCorrelationId);
            extractHeader(headers, PARTITION_KEY_HEADER)
                    .ifPresent(cloudEvent::setPartitionKey);
        }

        return cloudEvent;
    }

    private Optional<String> extractHeader(Headers headers, String headerName) {
        Header header = headers.lastHeader(headerName);
        if (header != null && header.value() != null) {
            return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    @Override
    public void close() {
    }
}
