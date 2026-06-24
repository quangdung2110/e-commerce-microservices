package com.platform.inventoryservice.domain.enums;

/**
 * Loại event ghi vào {@code inventory_outbox.event_type}.
 * Ràng buộc enum trong DB (V1__init_schema.sql).
 */
public enum OutboxEventType {

    INVENTORY_STOCK_CHANGED,
    INVENTORY_RETURN_RECEIVED_ALL,
    INVENTORY_SHIP_TO_CUSTOMER;

    public String aggregateType() {
        return "Inventory";
    }

    /**
     * @return Kafka topic name cho mọi event inventory.
     */
    public String topic() {
        return "t.inventory.events";
    }

    /**
     * @return default partition key nếu caller không override.
     *         Trả về {@code null} cho cả 3 type — caller bắt buộc truyền
     *         {@code sku_id} (STOCK_CHANGED) hoặc {@code order_id}
     *         (RETURN_RECEIVED_ALL, SHIP_TO_CUSTOMER).
     */
    public String defaultPartitionKey() {
        return null;
    }
}