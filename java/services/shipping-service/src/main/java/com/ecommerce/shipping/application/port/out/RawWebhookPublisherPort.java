package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.application.dto.ReceiveWebhookCommand;

// Theo thiết kế shipping_workflow.md mục 5: webhook endpoint trả 200 và dump raw vào Kafka.
public interface RawWebhookPublisherPort {

    void publishRawWebhook(ReceiveWebhookCommand command);
}
