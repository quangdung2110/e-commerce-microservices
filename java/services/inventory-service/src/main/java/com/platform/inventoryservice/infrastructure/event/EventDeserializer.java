package com.platform.inventoryservice.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class EventDeserializer {

    private final ObjectMapper mapper;

    public EventDeserializer() {
        this.mapper = EventSerializer.mapper();
    }

    public CloudEventImpl<JsonNode> deserialize(String json) {
        try {
            return mapper.readValue(json, new TypeReference<CloudEventImpl<JsonNode>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize CloudEvent", e);
        }
    }

    public <P> P extractPayload(CloudEventImpl<JsonNode> event, Class<P> payloadType) {
        return mapper.convertValue(event.payload(), payloadType);
    }
}
