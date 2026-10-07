package com.ecommerce.inventory.command;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.config.InventoryProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-closed control for every mutation (operations.md §1, ADR-027): the manual
 * {@code inventory.write-freeze} switch and outbox backpressure. The unsequenced-outbox
 * count is refreshed by {@code BacklogMonitor} rather than counted per command.
 */
@Component
public class WriteGuard {

    private final InventoryProperties properties;
    private final AtomicLong unsequencedOutbox = new AtomicLong();

    public WriteGuard(InventoryProperties properties) {
        this.properties = properties;
    }

    public void assertWritable() {
        if (properties.writeFreeze()) {
            throw ApiException.unavailable("Inventory writes are frozen by operations");
        }
        if (unsequencedOutbox.get() > properties.outbox().maxUnsequenced()) {
            throw ApiException.unavailable("Outbox backlog exceeds its safe capacity; writes fail closed");
        }
    }

    public void updateUnsequencedOutbox(long count) {
        unsequencedOutbox.set(count);
    }

    public long unsequencedOutbox() {
        return unsequencedOutbox.get();
    }
}
