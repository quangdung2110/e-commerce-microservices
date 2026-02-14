package com.example.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class cho tất cả các events trong hệ thống Event Sourcing
 * Có thể tái sử dụng cho các service khác như shipping-service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Abstract class để không thể khởi tạo trực tiếp à phải kế thừa để tạo các event cụ thể
// Serializable để có thể chuyển đổi thành byte stream khi lưu trữ hoặc truyền qua mạng
// Lý do không dùng interface là vì muốn cung cấp các thuộc tính và phương thức chung cho tất cả các event
public abstract class BaseEvent implements Serializable {
    
    private String eventId;
    private String aggregateId;
    private String eventType;
    private Instant occurredOn;
    private Integer version;
    
    /**
     * Initialize event với các giá trị mặc định
     */
    public void initializeEvent() {
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID().toString();
        }
        if (this.occurredOn == null) {
            this.occurredOn = Instant.now();
        }
        if (this.version == null) {
            this.version = 1;
        }
        if (this.eventType == null) {
            this.eventType = this.getClass().getSimpleName();
        }
    }
}
