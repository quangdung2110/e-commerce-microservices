# Module 04: Domain Model

> **Depends on:** M01 (Project Foundation)
> **Depended by:** M05 (Repository Layer), M06 (Redis Atomic Ops), M07 (Event Definitions), M10 (Routing), M11 (Temporal), M12 (Admin Services)

---

## 1. Goal

Định nghĩa **pure domain** của Inventory Service: entities, value objects, enums, exceptions. **Tuyệt đối không** import Spring, JPA, Lettuce, Kafka, hay bất kỳ infrastructure nào. Đây là layer "innermost" của Hexagonal architecture.

> Nguyên tắc: domain POJO có thể compile và test độc lập với infrastructure.

---

## 2. Package Layout

```
com.platform.inventoryservice.domain/
├── entity/                  # Aggregate roots & entities
├── vo/                      # Immutable value objects
├── enums/                   # Pure enums
├── event/                   # CloudEvents + payload classes (M07 surface)
└── exception/               # Domain exceptions
```

---

## 3. Enums

### 3.1 ReferenceType

> `inventory_ledger.reference_type` enum. Ràng buộc CHECK trong DB (M02).

```java
package com.platform.inventoryservice.domain.enums;

public enum ReferenceType {
    ORDER_RESERVED,          // Giữ chỗ — actual_quantity unchanged, reserved tăng
    ORDER_DEDUCTED,          // Xác nhận bán — actual_quantity giảm
    ORDER_CANCELLED,         // Buyer cancel trước payment
    RESERVATION_EXPIRED,     // Temporal timer fire (do Order Saga gọi)
    ADMIN_RESTOCK,           // Admin nhập hàng mới
    ORDER_RETURNED_RESTOCK;  // Admin xác nhận hàng hoàn về kho

    public boolean affectsActualQuantity() {
        return this == ORDER_DEDUCTED
            || this == ADMIN_RESTOCK
            || this == ORDER_RETURNED_RESTOCK;
    }
}
```

### 3.2 ReservationStatus

```java
package com.platform.inventoryservice.domain.enums;

public enum ReservationStatus {
    RESERVED,    // Pre-payment: stock đang được giữ
    DEDUCTED,    // Post-payment: stock đã được trừ đứt
    CANCELLED;   // Compensation: stock đã được trả lại (cancel hoặc expire)

    public boolean isTerminal() {
        return this == DEDUCTED || this == CANCELLED;
    }
}
```

### 3.3 LuaReturnCode

```java
package com.platform.inventoryservice.domain.enums;

public enum LuaReturnCode {
    APPLIED(1, ""),                          // Mutation mới
    IDEMPOTENT_HIT(2, ""),                   // Đã apply trước đó
    OUT_OF_STOCK(0, "OUT_OF_STOCK"),
    INSUFFICIENT_RESERVED(0, "INSUFFICIENT_RESERVED"),
    RELEASE_UNDERFLOW(0, "RELEASE_UNDERFLOW"),
    SKU_SUSPENDED(0, "SKU_SUSPENDED"),
    INVARIANT_DRIFT(-1, "INVARIANT_DRIFT");

    private final int code;
    private final String reason;

    LuaReturnCode(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    public int code() { return code; }
    public String reason() { return reason; }

    public static LuaReturnCode fromArray(List<Object> result) {
        if (result == null || result.size() != 2) {
            throw new IllegalArgumentException("Invalid Lua return: " + result);
        }
        int c = (int) result.get(0);
        String r = String.valueOf(result.get(1));
        for (LuaReturnCode v : values()) {
            if (v.code == c && v.reason.equals(r)) return v;
        }
        throw new IllegalStateException("Unknown Lua return: {" + c + ", " + r + "}");
    }

    public boolean isSuccess() {
        return this == APPLIED || this == IDEMPOTENT_HIT;
    }

    public boolean isIdempotent() {
        return this == IDEMPOTENT_HIT;
    }

    public boolean isBusinessReject() {
        return code == 0;
    }

    public boolean isInvariantBreach() {
        return this == INVARIANT_DRIFT;
    }
}
```

### 3.4 OutboxEventType

```java
package com.platform.inventoryservice.domain.enums;

public enum OutboxEventType {
    INVENTORY_STOCK_CHANGED,
    INVENTORY_RETURN_RECEIVED_ALL,
    INVENTORY_SHIP_TO_CUSTOMER;

    public String aggregateType() {
        return "Inventory";
    }

    /** Kafka topic name cho event này. */
    public String topic() {
        return "t.inventory.events";
    }

    /** Default partition key nếu caller không override. */
    public String defaultPartitionKey() {
        return switch (this) {
            case INVENTORY_STOCK_CHANGED -> null;     // caller phải cung cấp sku_id
            case INVENTORY_RETURN_RECEIVED_ALL -> null; // caller cung cấp order_id
            case INVENTORY_SHIP_TO_CUSTOMER -> null;    // caller cung cấp order_id
        };
    }
}
```

### 3.5 ChangeType (cho INVENTORY_STOCK_MUTATION event)

```java
package com.platform.inventoryservice.domain.enums;

public enum ChangeType {
    ORDER_RESERVED,
    ORDER_DEDUCTED,
    ORDER_CANCELLED,
    RESERVATION_EXPIRED;

    public static ChangeType fromReferenceType(ReferenceType rt) {
        return switch (rt) {
            case ORDER_RESERVED      -> ORDER_RESERVED;
            case ORDER_DEDUCTED      -> ORDER_DEDUCTED;
            case ORDER_CANCELLED     -> ORDER_CANCELLED;
            case RESERVATION_EXPIRED -> RESERVATION_EXPIRED;
            // ADMIN_RESTOCK / ORDER_RETURNED_RESTOCK không bao giờ xuất hiện trong
            // INVENTORY_STOCK_MUTATION — chúng đi qua API → DB outbox.
            default -> throw new IllegalStateException("Cannot map to ChangeType: " + rt);
        };
    }
}
```

---

## 4. Value Objects (immutable, record-based)

### 4.1 SkuWarehouseKey

Composite identity cho `(sku_id, warehouse_id)`. Dùng cho tất cả Redis key construction (hash-tag invariant).

```java
package com.platform.inventoryservice.domain.vo;

public record SkuWarehouseKey(String skuId, long warehouseId) {

    public SkuWarehouseKey {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (warehouseId <= 0) {
            throw new IllegalArgumentException("warehouseId must be positive");
        }
    }

    /** Hash-tag thống nhất cho Redis Cluster — dùng cho mọi key construction. */
    public String hashTag() {
        return "{" + skuId + ":" + warehouseId + "}";
    }
}
```

### 4.2 StockHash

Snapshot trạng thái stock hiện tại trong Redis hash. Read-only — application không mutate trực tiếp (chỉ qua Lua scripts).

```java
package com.platform.inventoryservice.domain.vo;

public record StockHash(
    long total,
    long reserved,
    long available,
    long updatedAtMs
) {
    public StockHash {
        if (total < 0 || reserved < 0 || available < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
        if (total != reserved + available) {
            throw new IllegalArgumentException(
                "Invariant violated: total(" + total + ") != reserved(" + reserved
                + ") + available(" + available + ")");
        }
    }

    public static StockHash empty() {
        return new StockHash(0, 0, 0, 0L);
    }
}
```

### 4.3 AvailableStock

Per-warehouse available quantity read từ `physical_inventory`. Aggregate (sum across warehouses) được tính ở routing layer.

```java
package com.platform.inventoryservice.domain.vo;

public record AvailableStock(String skuId, long warehouseId, long quantity) {
    public AvailableStock {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
    }

    public boolean canFulfill(long requested) {
        return quantity >= requested;
    }
}
```

### 4.4 CustomerLocation

```java
package com.platform.inventoryservice.domain.vo;

public record CustomerLocation(String cityCode, String districtCode) {
    public CustomerLocation {
        if (cityCode == null || cityCode.isBlank()) {
            throw new IllegalArgumentException("cityCode is required");
        }
        // districtCode optional in Phase 14 (reserved for future)
    }
}
```

### 4.5 RoutePlan

> Routing plan fulfillment cho checkout. Immutable record; instance sinh ra từ `RoutePlanBuilder` (M10).

```java
package com.platform.inventoryservice.domain.vo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoutePlan(
    UUID routePlanId,
    Instant expiresAt,
    List<RoutePlanItem> items,
    List<WarehouseOption> warehouseOptions
) {
    public RoutePlan {
        if (routePlanId == null) throw new IllegalArgumentException("routePlanId required");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items required");
        if (warehouseOptions == null || warehouseOptions.isEmpty()) {
            throw new IllegalArgumentException("warehouseOptions required");
        }
        items = List.copyOf(items);
        warehouseOptions = List.copyOf(warehouseOptions);
    }

    public record RoutePlanItem(
        String skuId,
        String productId,
        long quantity,
        long warehouseId
    ) {
        public RoutePlanItem {
            if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
            if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId must be positive");
        }
    }

    public record WarehouseOption(
        long warehouseId,
        long availableQuantity,
        int estimatedDeliveryDays
    ) {}
}
```

### 4.6 Warehouse (read-side entity)

```java
package com.platform.inventoryservice.domain.entity;

import java.time.Instant;

public class Warehouse {
    private Long id;
    private String name;
    private String address;
    private String cityCode;
    private String districtCode;
    private String wardCode;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;

    public static Warehouse createActive(String name, String address, String cityCode) {
        Warehouse w = new Warehouse();
        w.name = name;
        w.address = address;
        w.cityCode = cityCode;
        w.active = true;
        return w;
    }

    public boolean canFulfill() {
        return active;
    }

    // getters/setters/equals/hashCode/toString
    // (Chi tiết trong implementation — dùng Lombok @Getter/@Setter cho gọn)
}
```

> `Warehouse` là entity đơn giản. Trong Phase 14 chỉ cần lookup `is_active = true` — không có business logic phức tạp. CRUD đầy đủ dành cho module admin (out of scope cho service này nếu backend khác xử lý).

### 4.7 PhysicalInventory (entity, write-side)

> Domain entity. JPA mapping thuộc M05.

```java
package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;

import java.time.Instant;

public class PhysicalInventory {

    private Long id;
    private SkuWarehouseKey key;
    private String productId;
    private int actualQuantity;
    private int reservedQuantity;
    private Instant updatedAt;

    /** Constructor dùng cho cả create mới (restock lần đầu) và update. */
    public static PhysicalInventory forUpsert(
        SkuWarehouseKey key, String productId, int actualQtyToAdd
    ) {
        PhysicalInventory p = new PhysicalInventory();
        p.key = key;
        p.productId = productId;
        p.actualQuantity = actualQtyToAdd;
        p.reservedQuantity = 0;
        return p;
    }

    public int availableQuantity() {
        return actualQuantity - reservedQuantity;
    }

    public void addActualQuantity(int delta) {
        if (delta < 0) throw new IllegalArgumentException("delta must be non-negative");
        this.actualQuantity += delta;
    }

    public void applyReserve(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        if (reservedQuantity + qty > actualQuantity) {
            throw new IllegalStateException("Cannot reserve more than available");
        }
        this.reservedQuantity += qty;
    }

    public void applyDeduct(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        if (reservedQuantity < qty) {
            throw new IllegalStateException("Insufficient reserved for deduct");
        }
        this.reservedQuantity -= qty;
        this.actualQuantity -= qty;
    }

    public void applyRelease(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive");
        if (reservedQuantity < qty) {
            throw new IllegalStateException("Cannot release more than reserved");
        }
        this.reservedQuantity -= qty;
    }

    // getters/setters
}
```

> Domain method `applyReserve`/`applyDeduct`/`applyRelease` đảm bảo invariant tại Java layer. Tuy nhiên trong hot path, mutation đi qua Lua script — những method này chủ yếu dùng cho admin REST (M12) hoặc test logic.

### 4.8 InventoryReservation (entity)

```java
package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public class InventoryReservation {

    private Long id;
    private long orderId;
    private long orderItemId;
    private String skuId;
    private String productId;
    private long warehouseId;
    private int quantity;
    private UUID routePlanId;
    private ReservationStatus status;
    private String idempotencyKey;
    private Instant expiresAt;
    private boolean refundExpected;
    private String refundRequestId;
    private Instant refundReturnedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static InventoryReservation newReserved(
        long orderId, long orderItemId,
        String skuId, String productId, long warehouseId,
        int quantity, UUID routePlanId, String idempotencyKey,
        Instant expiresAt
    ) {
        InventoryReservation r = new InventoryReservation();
        r.orderId = orderId;
        r.orderItemId = orderItemId;
        r.skuId = skuId;
        r.productId = productId;
        r.warehouseId = warehouseId;
        r.quantity = quantity;
        r.routePlanId = routePlanId;
        r.status = ReservationStatus.RESERVED;
        r.idempotencyKey = idempotencyKey;
        r.expiresAt = expiresAt;
        r.refundExpected = false;
        return r;
    }

    public void markDeducted() {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Only RESERVED can be deducted, was " + status);
        }
        this.status = ReservationStatus.DEDUCTED;
    }

    public void markCancelled() {
        if (status == ReservationStatus.DEDUCTED) {
            throw new IllegalStateException("Cannot cancel DEDUCTED reservation");
        }
        this.status = ReservationStatus.CANCELLED;
    }

    public void markRefundExpected(String refundRequestId) {
        this.refundExpected = true;
        this.refundRequestId = refundRequestId;
    }

    public void markRefundReturned(Instant when) {
        if (!refundExpected) {
            throw new IllegalStateException("Cannot mark returned for non-expected refund");
        }
        if (refundReturnedAt != null) {
            throw new IllegalStateException("Already marked returned");
        }
        this.refundReturnedAt = when;
    }

    // getters
}
```

### 4.9 InventoryLedgerEntry (entity, append-only)

```java
package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.ReferenceType;

import java.time.Instant;
import java.util.UUID;

public class InventoryLedgerEntry {

    private Long id;
    private UUID eventId;
    private String skuId;
    private String productId;
    private long warehouseId;
    private ReferenceType referenceType;
    private int quantityChange;
    private int balanceAfter;
    private String correlationId;
    private Instant createdAt;

    public static InventoryLedgerEntry append(
        UUID eventId,
        String skuId, String productId, long warehouseId,
        ReferenceType referenceType,
        int quantityChange,
        int balanceAfter,
        String correlationId
    ) {
        if (quantityChange == 0 && referenceType.affectsActualQuantity()) {
            throw new IllegalArgumentException(
                "Reference type " + referenceType + " must affect actual_quantity");
        }
        InventoryLedgerEntry e = new InventoryLedgerEntry();
        e.eventId = eventId;
        e.skuId = skuId;
        e.productId = productId;
        e.warehouseId = warehouseId;
        e.referenceType = referenceType;
        e.quantityChange = quantityChange;
        e.balanceAfter = balanceAfter;
        e.correlationId = correlationId;
        return e;
    }

    // getters (no setters — append-only)
}
```

### 4.10 OutboxEvent (entity)

```java
package com.platform.inventoryservice.domain.entity;

import com.platform.inventoryservice.domain.enums.OutboxEventType;

import java.time.Instant;
import java.util.UUID;

public class OutboxEvent {

    private UUID eventId;
    private OutboxEventType eventType;
    private int schemaVersion;
    private String aggregateType;
    private String aggregateId;
    private String partitionKey;
    private String refundRequestId;  // nullable
    private String payload;          // JSON serialized
    private Instant createdAt;

    public static OutboxEvent of(
        OutboxEventType eventType,
        String aggregateId,
        String partitionKey,
        String payloadJson
    ) {
        return of(eventType, aggregateId, partitionKey, payloadJson, null);
    }

    public static OutboxEvent of(
        OutboxEventType eventType,
        String aggregateId,
        String partitionKey,
        String payloadJson,
        String refundRequestId
    ) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = UUID.randomUUID();
        e.eventType = eventType;
        e.schemaVersion = 1;
        e.aggregateType = "Inventory";
        e.aggregateId = aggregateId;
        e.partitionKey = partitionKey;
        e.refundRequestId = refundRequestId;
        e.payload = payloadJson;
        e.createdAt = Instant.now();
        return e;
    }

    public boolean isReturnReceivedAll() {
        return eventType == OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL;
    }

    // getters
}
```

> `OutboxEvent` aggregate ID convention:
> - `INVENTORY_STOCK_CHANGED` → `aggregate_id = sku_id`, `partition_key = sku_id`
> - `INVENTORY_RETURN_RECEIVED_ALL` → `aggregate_id = order_id`, `partition_key = order_id`
> - `INVENTORY_SHIP_TO_CUSTOMER` → `aggregate_id = order_id`, `partition_key = order_id`

---

## 5. Event Envelope & Payloads (M07 surface)

> Class definitions trong package `domain/event/`. Chỉ trình bày signature ở đây; chi tiết tại M07.

### 5.1 CloudEvent

```java
package com.platform.inventoryservice.domain.event;

import java.time.Instant;
import java.util.UUID;

public interface CloudEvent<P> {
    UUID eventId();
    String eventType();
    String aggregateType();
    String aggregateId();
    String correlationId();
    Instant timestamp();
    String source();
    int schemaVersion();
    P payload();
}
```

### 5.2 StockMutationPayload

```java
package com.platform.inventoryservice.domain.event;

public record StockMutationPayload(
    String skuId,
    String productId,
    long warehouseId,
    String changeType,        // ORDER_RESERVED | ORDER_DEDUCTED | ORDER_CANCELLED | RESERVATION_EXPIRED
    int quantity,
    long availableAfter,      // per-warehouse
    long reservedAfter,       // per-warehouse
    long orderId,
    long orderItemId
) {}
```

### 5.3 StockChangedPayload

```java
package com.platform.inventoryservice.domain.event;

public record StockChangedPayload(
    String productId,
    String skuId,
    long availableQuantity,   // per-SKU aggregate (sum across warehouses)
    String changeType
) {}
```

### 5.4 ReturnReceivedAllPayload

```java
package com.platform.inventoryservice.domain.event;

import java.time.Instant;
import java.util.List;

public record ReturnReceivedAllPayload(
    long orderId,
    String refundRequestId,
    List<Long> requiredOrderItemIds,
    List<Long> receivedOrderItemIds,
    boolean allItemsReceived,
    Instant confirmedAt
) {}
```

### 5.5 ShipToCustomerPayload

```java
package com.platform.inventoryservice.domain.event;

import java.time.Instant;

public record ShipToCustomerPayload(
    long orderId,
    long warehouseId,
    Instant shippedAt,
    String courierNote
) {}
```

---

## 6. Domain Exceptions

> Tất cả exceptions **không** kế thừa `RuntimeException` thông thường — chúng kế thừa `DomainException` base, có `errorCode` để map sang HTTP ở M13.

```java
package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public abstract class DomainException extends RuntimeException {
    private final ErrorCode errorCode;
    protected DomainException(ErrorCode code, String message) {
        super(message);
        this.errorCode = code;
    }
    public ErrorCode errorCode() { return errorCode; }
}
```

### 6.1 Inventory-specific exceptions

```java
public class InsufficientStockException extends DomainException {
    public InsufficientStockException(String skuId) {
        super(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for sku: " + skuId);
    }
}

public class OutOfStockException extends DomainException {
    public OutOfStockException(String skuId, long warehouseId) {
        super(ErrorCode.OUT_OF_STOCK,
              "Out of stock at warehouse " + warehouseId + " for sku: " + skuId);
    }
}

public class InsufficientReservedException extends DomainException {
    public InsufficientReservedException(String skuId, long warehouseId) {
        super(ErrorCode.INSUFFICIENT_RESERVED,
              "Insufficient reserved at warehouse " + warehouseId + " for sku: " + skuId);
    }
}

public class ReleaseUnderflowException extends DomainException {
    public ReleaseUnderflowException(String skuId, long warehouseId) {
        super(ErrorCode.RELEASE_UNDERFLOW,
              "Release underflow at warehouse " + warehouseId + " for sku: " + skuId);
    }
}

public class SkuSuspendedException extends DomainException {
    public SkuSuspendedException(String skuId, long warehouseId, String reason) {
        super(ErrorCode.SKU_SUSPENDED,
              "SKU " + skuId + " at warehouse " + warehouseId + " suspended: " + reason);
    }
}

public class InvariantDriftException extends DomainException {
    public InvariantDriftException(String skuId, long warehouseId) {
        super(ErrorCode.INVARIANT_DRIFT,
              "Invariant drift at " + skuId + ":" + warehouseId);
    }
}

public class WarehouseNotFoundException extends DomainException {
    public WarehouseNotFoundException(long warehouseId) {
        super(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found: " + warehouseId);
    }
}

public class ReservationNotFoundException extends DomainException {
    public ReservationNotFoundException(String context) {
        super(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found: " + context);
    }
}

public class RefundScopeMismatchException extends DomainException {
    private final List<Long> existing;
    private final List<Long> requested;
    public RefundScopeMismatchException(List<Long> existing, List<Long> requested) {
        super(ErrorCode.REFUND_SCOPE_MISMATCH,
              "Refund scope mismatch. Existing: " + existing + ", Requested: " + requested);
        this.existing = List.copyOf(existing);
        this.requested = List.copyOf(requested);
    }
    public List<Long> existing() { return existing; }
    public List<Long> requested() { return requested; }
}

public class SkuNotSuspendedException extends DomainException {
    public SkuNotSuspendedException(String skuId, long warehouseId) {
        super(ErrorCode.SKU_NOT_SUSPENDED,
              "SKU " + skuId + ":" + warehouseId + " is not suspended");
    }
}
```

---

## 7. Pure-Domain Invariants (cho self-check)

Đảm bảo bằng test thuần (no Spring context):

| Test | Mục đích |
|:---|:---|
| `PhysicalInventory.applyReserve_overflow_throws` | Invariant 1: reserved ≤ actual |
| `PhysicalInventory.applyDeduct_insufficientReserved_throws` | Invariant 2: deduct ≤ reserved |
| `PhysicalInventory.applyRelease_underflow_throws` | Invariant 3: release ≤ reserved |
| `InventoryReservation.newReserved_negativeQuantity_throws` | Argument validation |
| `InventoryReservation.markDeducted_fromCancelled_throws` | State machine guard |
| `InventoryLedgerEntry.append_inconsistentChange_throws` | Domain rule (quantity_change == 0 không được affect actual) |
| `SkuWarehouseKey.blankSkuId_throws` | Hash-tag invariant |
| `StockHash.invariantViolation_throws` | Cross-field invariant |
| `LuaReturnCode.fromArray_malformed_throws` | Defensive parse |

> Tests cho module này nằm trong `src/test/java/com/platform/inventoryservice/domain/` và **không** dùng Spring context — chỉ JUnit 5 thuần.

---

## 8. Anti-Patterns (KHÔNG làm)

❌ KHÔNG annotate entity domain bằng `@Entity`, `@Table`, `@Column` (JPA concern thuộc M05).
❌ KHÔNG inject `RedisTemplate`, `KafkaTemplate`, `EntityManager` vào domain classes.
❌ KHÔNG dùng `Optional` cho field bắt buộc trong record (dùng validation thay thế).
❌ KHÔNG expose mutable collection qua getter (`List.copyOf` đã làm ở constructor).
❌ KHÔNG thêm business logic vào exception classes ngoài `errorCode` và message.

---

## 9. Verification

| Check | Cách verify |
|:---|:---|
| Pure domain compile | `./mvnw -pl inventory-service compile` không có lỗi khi thiếu infrastructure |
| Pure domain tests | `./mvnw test -Dtest='com.platform.inventoryservice.domain.**'` pass |
| No infrastructure leak | `grep -r 'import org.springframework' domain/` → empty |
| No JPA leak | `grep -r 'import jakarta.persistence' domain/` → empty |
| No Lettuce leak | `grep -r 'import io.lettuce' domain/` → empty |

---

## 10. Files Created

```
src/main/java/com/platform/inventoryservice/domain/
├── enums/
│   ├── ReferenceType.java
│   ├── ReservationStatus.java
│   ├── LuaReturnCode.java
│   ├── OutboxEventType.java
│   └── ChangeType.java
├── vo/
│   ├── SkuWarehouseKey.java
│   ├── StockHash.java
│   ├── AvailableStock.java
│   ├── CustomerLocation.java
│   └── RoutePlan.java
├── entity/
│   ├── Warehouse.java
│   ├── PhysicalInventory.java
│   ├── InventoryReservation.java
│   ├── InventoryLedgerEntry.java
│   └── OutboxEvent.java
├── event/
│   ├── CloudEvent.java
│   ├── StockMutationPayload.java
│   ├── StockChangedPayload.java
│   ├── ReturnReceivedAllPayload.java
│   └── ShipToCustomerPayload.java
└── exception/
    ├── DomainException.java
    ├── InsufficientStockException.java
    ├── OutOfStockException.java
    ├── InsufficientReservedException.java
    ├── ReleaseUnderflowException.java
    ├── SkuSuspendedException.java
    ├── InvariantDriftException.java
    ├── WarehouseNotFoundException.java
    ├── ReservationNotFoundException.java
    ├── RefundScopeMismatchException.java
    └── SkuNotSuspendedException.java
```