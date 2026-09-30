package com.platform.paymentservice.adapter.out.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxConnectorContractTest {
    @Test
    void connector_restart_reuses_persisted_event_identity_key_and_full_payload_without_tombstones()
            throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "kafka/debezium-connector-payment-outbox.json")) {
            JsonNode config = new ObjectMapper().readTree(input).path("config");
            assertThat(config.path("transforms.outbox.table.field.event.id").asText())
                    .isEqualTo("event_id");
            assertThat(config.path("transforms.outbox.table.field.event.key").asText())
                    .isEqualTo("partition_key");
            assertThat(config.path("transforms.outbox.table.field.event.payload").asText())
                    .isEqualTo("payload");
            assertThat(config.path("transforms.outbox.table.expand.json.payload").asText())
                    .isEqualTo("true");
            assertThat(config.path("transforms.paymentTopic.replacement").asText())
                    .isEqualTo("t.payment.events.v2");
            assertThat(config.path("tombstones.on.delete").asText()).isEqualTo("false");
            assertThat(config.path("snapshot.mode").asText()).isEqualTo("no_data");
        }
    }
}
