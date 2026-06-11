package com.platform.productservice.infrastructure.kafka.deserializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class CloudEventSerializer implements Serializer<CloudEvent> {

    private static final Logger log = LoggerFactory.getLogger(CloudEventSerializer.class);
    private static final String CORRELATION_ID_HEADER = "correlation_id";
    private static final String PARTITION_KEY_HEADER = "partition_key";

    private final ObjectMapper objectMapper;

    public CloudEventSerializer() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void configure(java.util.Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public byte[] serialize(String topic, CloudEvent data) {
        if (data == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.error("Failed to serialize CloudEvent to topic {}: {}", topic, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public byte[] serialize(String topic, Headers headers, CloudEvent data) {
        byte[] bytes = serialize(topic, data);

        if (headers != null && data != null) {
            if (data.getCorrelationId() != null) {
                headers.add(new Header() {
                    @Override
                    public String key() {
                        return CORRELATION_ID_HEADER;
                    }

                    @Override
                    public byte[] value() {
                        return data.getCorrelationId().getBytes(StandardCharsets.UTF_8);
                    }
                });
            }

            if (data.getPartitionKey() != null) {
                headers.add(new Header() {
                    @Override
                    public String key() {
                        return PARTITION_KEY_HEADER;
                    }

                    @Override
                    public byte[] value() {
                        return data.getPartitionKey().getBytes(StandardCharsets.UTF_8);
                    }
                });
            }
        }

        return bytes;
    }

    @Override
    public void close() {
    }
}
