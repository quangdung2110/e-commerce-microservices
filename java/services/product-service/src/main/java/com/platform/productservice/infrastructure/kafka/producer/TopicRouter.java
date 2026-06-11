package com.platform.productservice.infrastructure.kafka.producer;

import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.domain.event.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TopicRouter {

    private static final Set<String> CATEGORY_EVENT_TYPES = Set.of(
            "CATEGORY_CREATED",
            "CATEGORY_UPDATED",
            "CATEGORY_DELETED"
    );

    public String routeTopic(DomainEvent event) {
        if (event == null || event.getEventType() == null) {
            return ServiceConstants.TOPIC_PRODUCT_EVENTS;
        }

        if (CATEGORY_EVENT_TYPES.contains(event.getEventType())) {
            return ServiceConstants.TOPIC_CATEGORY_EVENTS;
        }

        return ServiceConstants.TOPIC_PRODUCT_EVENTS;
    }

    public String getPartitionKey(DomainEvent event) {
        if (event == null) {
            return null;
        }
        return event.getPartitionKey();
    }
}
