package com.ecommerce.shipping.application.port.out;

import java.time.Duration;

// Theo thiết kế shipping_workflow.md mục 2 và shipping_events.md mục 3.6: SETNX-style idempotency keys.
public interface IdempotencyPort {

    boolean acquire(String key, Duration ttl);
}
