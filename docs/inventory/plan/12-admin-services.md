# Module 12: Admin Business Services

> **Depends on:** M05 (Repository), M06 (Redis Cache), M07 (Events), M08 (Outbox)
> **Depended by:** M13 (HTTP API Controllers)

---

## 1. Goal

Triển khai business logic cho 4 admin operations (HTTP REST):

1. **RestockService** — nhập hàng vào kho (`ADMIN_RESTOCK` + `ORDER_RETURNED_RESTOCK`).
2. **ShipToCustomerService** — admin xác nhận xuất hàng cho khách (signal nghiệp vụ, không mutate stock).
3. **RefundExpectationsService** — Order đăng ký tập item refund.
4. **UnlockSkuService** — admin manual unlock SKU bị suspend.

Mỗi service đảm bảo:
- PostgreSQL transaction (multi-table consistency).
- Outbox INSERT trong cùng TX (cho public events).
- Redis cache update (nếu có).
- Audit trail qua `inventory_ledger`.

> Tham chiếu: `inventory_endpoints.md` §2, §3, §4, §5; `inventory_workflow.md` Luồng 5, 6, 7.

---

## 2. Package Layout

```
com.platform.inventoryservice.service/
├── RestockService.java
├── ShipToCustomerService.java
├── RefundExpectationsService.java
├── UnlockSkuService.java
├── command/
│   ├── RestockCommand.java
│   ├── ShipToCustomerCommand.java
│   ├── RefundExpectationsCommand.java
│   └── UnlockSkuCommand.java
└── result/
    ├── RestockResult.java
    ├── ShipToCustomerResult.java
    ├── RefundExpectationsResult.java
    └── UnlockSkuResult.java
```

> Triển khai `port/inbound/` interfaces (đã khai báo ở M04/M11).

---

## 3. RestockService

### 3.1 Command & Result

```java
public record RestockCommand(
    long warehouseId,
    String skuId,
    String productId,
    int quantity,
    ReferenceType referenceType,    // ADMIN_RESTOCK | ORDER_RETURNED_RESTOCK
    Long orderId,                    // required if ORDER_RETURNED_RESTOCK
    Long orderItemId,                // required if ORDER_RETURNED_RESTOCK
    String refundRequestId,          // required if ORDER_RETURNED_RESTOCK
    String correlationId             // X-Correlation-Id header
) {
    public RestockCommand {
        if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId invalid");
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");

        if (referenceType == ReferenceType.ORDER_RETURNED_RESTOCK) {
            if (orderId == null || orderItemId == null || refundRequestId == null) {
                throw new IllegalArgumentException(
                    "orderId, orderItemId, refundRequestId required for ORDER_RETURNED_RESTOCK");
            }
        } else if (referenceType != ReferenceType.ADMIN_RESTOCK) {
            throw new IllegalArgumentException("Unsupported referenceType: " + referenceType);
        }
    }
}

public record RestockResult(
    long warehouseId,
    String skuId,
    String productId,
    ReferenceType referenceType,
    int quantityAdded,
    StockSnapshot physical,
    RefundSnapshot refund,             // nullable for ADMIN_RESTOCK
    UUID eventId,
    Instant timestamp
) {}

public record StockSnapshot(int actualQuantity, int reservedQuantity, int availableQuantity) {}
public record RefundSnapshot(
    long orderId, long orderItemId, String refundRequestId,
    Instant refundReturnedAt, boolean allItemsReceived
) {}
```

### 3.2 Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RestockService implements RestockPort {

    private final WarehouseRepository warehouseRepo;
    private final PhysicalInventoryRepository physicalRepo;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryLedgerRepository ledgerRepo;
    private final OutboxRepository outboxRepo;
    private final TransactionalOutboxWriter outboxWriter;
    private final InventoryEventBuilder eventBuilder;
    private final StringRedisTemplate redis;
    private final RedisKeyBuilder redisKeys;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public RestockResult restock(RestockCommand cmd) {
        // 1. Validate warehouse exists & active
        Warehouse warehouse = warehouseRepo.findById(cmd.warehouseId())
            .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

        // 2. Upsert physical_inventory (atomic increase actual_quantity)
        int newActual = physicalRepo.upsert(
            new SkuWarehouseKey(cmd.skuId(), cmd.warehouseId()),
            cmd.productId(),
            cmd.quantity()
        );

        // 3. Reference-type specific logic
        RefundSnapshot refundSnapshot = null;
        if (cmd.referenceType() == ReferenceType.ORDER_RETURNED_RESTOCK) {
            refundSnapshot = handleReturnRestock(cmd);
        }

        // 4. Append ledger
        UUID ledgerEventId = UUID.randomUUID();
        InventoryLedgerEntry ledger = InventoryLedgerEntry.append(
            ledgerEventId,
            cmd.skuId(), cmd.productId(), cmd.warehouseId(),
            cmd.referenceType(),
            cmd.quantity(),
            newActual,
            cmd.correlationId()
        );
        ledgerRepo.append(ledger);

        // 5. Compute aggregate per-SKU available for outbox payload
        long aggregateAvailable = physicalRepo.sumAvailableBySkuIds(List.of(cmd.skuId()))
            .getOrDefault(cmd.skuId(), 0L);

        // 6. Insert outbox INVENTORY_STOCK_CHANGED (cold track)
        String stockChangedType = cmd.referenceType().name();  // ADMIN_RESTOCK | ORDER_RETURNED_RESTOCK
        CloudEventImpl<StockChangedPayload> stockChangedEvent = eventBuilder.buildStockChanged(
            cmd.skuId(), cmd.productId(),
            aggregateAvailable,
            stockChangedType,
            cmd.correlationId()
        );
        outboxWriter.writeCloudEvent(stockChangedEvent, stockChangedEvent.payload().toJsonString());

        // 7. If refund completion → emit INVENTORY_RETURN_RECEIVED_ALL
        if (refundSnapshot != null && refundSnapshot.allItemsReceived()) {
            emitReturnReceivedAll(cmd, refundSnapshot, cmd.correlationId());
        }

        // 8. Update Redis cache (best-effort; failure logged but doesn't fail TX)
        updateRedisCache(cmd, newActual);

        metrics.recordRestock(cmd.referenceType().name(), cmd.quantity());

        return new RestockResult(
            cmd.warehouseId(), cmd.skuId(), cmd.productId(),
            cmd.referenceType(), cmd.quantity(),
            new StockSnapshot(newActual, 0, newActual),
            refundSnapshot,
            stockChangedEvent.eventId(),
            Instant.now()
        );
    }

    // -- helpers --

    private RefundSnapshot handleReturnRestock(RestockCommand cmd) {
        // 1. Find DEDUCTED reservation
        InventoryReservation reservation = reservationRepo
            .findByOrderIdAndOrderItemIdAndStatus(
                cmd.orderId(), cmd.orderItemId(), ReservationStatus.DEDUCTED)
            .orElseThrow(() -> new ReservationNotFoundException(
                "order=" + cmd.orderId() + " item=" + cmd.orderItemId()));

        if (!reservation.isRefundExpected()) {
            throw new RefundNotExpectedException(
                cmd.orderId(), cmd.orderItemId());   // mapped to 409 REFUND_NOT_EXPECTED
        }

        // 2. Mark refund_returned_at
        reservation.markRefundReturned(Instant.now());
        reservationRepo.save(reservation);

        // 3. Refund completion check
        var snapshot = reservationRepo.checkRefundCompletion(
            cmd.orderId(), cmd.refundRequestId());

        return new RefundSnapshot(
            cmd.orderId(), cmd.orderItemId(), cmd.refundRequestId(),
            reservation.getRefundReturnedAt(),
            snapshot.pendingCount() == 0
        );
    }

    private void emitReturnReceivedAll(
        RestockCommand cmd, RefundSnapshot snapshot, String correlationId
    ) {
        var completion = reservationRepo.checkRefundCompletion(cmd.orderId(), cmd.refundRequestId());

        CloudEventImpl<ReturnReceivedAllPayload> envelope = eventBuilder.buildReturnReceivedAll(
            cmd.orderId(), cmd.refundRequestId(),
            completion.requiredItemIds(), completion.receivedItemIds(),
            Instant.now(),
            correlationId
        );
        outboxWriter.writeReturnReceivedAll(
            cmd.orderId(), cmd.refundRequestId(),
            envelope.payload().toJsonString());
    }

    private void updateRedisCache(RestockCommand cmd, int newActual) {
        try {
            String key = redisKeys.stockKey(new SkuWarehouseKey(cmd.skuId(), cmd.warehouseId()));
            Map<String, String> hash = Map.of(
                "total", String.valueOf(newActual),
                "reserved", "0",
                "available", String.valueOf(newActual),
                "updated_at_ms", String.valueOf(System.currentTimeMillis())
            );
            redis.opsForHash().putAll(key, hash);
        } catch (Exception e) {
            log.warn("Redis cache update failed (non-fatal) for sku={} wh={}",
                     cmd.skuId(), cmd.warehouseId(), e);
        }
    }
}
```

> ⚠️ Redis cache update KHÔNG nằm trong transaction — chỉ best-effort. Nếu Redis down, DB write vẫn commit thành công.

---

## 4. ShipToCustomerService

### 4.1 Command & Result

```java
public record ShipToCustomerCommand(
    long orderId,
    long warehouseId,
    Instant shippedAt,
    String courierNote,
    String correlationId
) {
    public ShipToCustomerCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId invalid");
        if (shippedAt == null) throw new IllegalArgumentException("shippedAt required");
    }
}

public record ShipToCustomerResult(
    boolean success,
    long orderId,
    long warehouseId,
    Instant shippedAt,
    UUID eventId,
    int affectedItems
) {}
```

### 4.2 Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipToCustomerService implements ShipToCustomerPort {

    private final WarehouseRepository warehouseRepo;
    private final InventoryReservationRepository reservationRepo;
    private final OutboxRepository outboxRepo;
    private final TransactionalOutboxWriter outboxWriter;
    private final InventoryEventBuilder eventBuilder;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public ShipToCustomerResult ship(ShipToCustomerCommand cmd) {
        // 1. Validate warehouse
        warehouseRepo.findById(cmd.warehouseId())
            .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

        // 2. Validate & count DEDUCTED reservations at this warehouse
        List<Long> itemIds = reservationRepo.findDeductedItemIds(
            cmd.orderId(), cmd.warehouseId());
        if (itemIds.isEmpty()) {
            throw new ReservationNotFoundException(
                "No DEDUCTED reservation for order=" + cmd.orderId()
                + " at warehouse=" + cmd.warehouseId());
        }

        // 3. Build outbox event (signal only — no stock mutation)
        CloudEventImpl<ShipToCustomerPayload> envelope = eventBuilder.buildShipToCustomer(
            cmd.orderId(), cmd.warehouseId(),
            cmd.shippedAt(), cmd.courierNote(),
            cmd.correlationId()
        );
        outboxWriter.writeCloudEvent(envelope, envelope.payload().toJsonString());

        metrics.recordShip(itemIds.size());

        return new ShipToCustomerResult(
            true, cmd.orderId(), cmd.warehouseId(),
            cmd.shippedAt(), envelope.eventId(), itemIds.size()
        );
    }
}
```

> KHÔNG mutate `physical_inventory`, `inventory_reservations`, hay `inventory_ledger` — đây là signal nghiệp vụ.

---

## 5. RefundExpectationsService

### 5.1 Command & Result

```java
public record RefundExpectationsCommand(
    long orderId,
    String refundRequestId,
    List<Long> orderItemIds,
    String correlationId
) {
    public RefundExpectationsCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (refundRequestId == null || refundRequestId.isBlank()) {
            throw new IllegalArgumentException("refundRequestId required");
        }
        if (orderItemIds == null || orderItemIds.isEmpty()) {
            throw new IllegalArgumentException("orderItemIds required");
        }
        orderItemIds = List.copyOf(orderItemIds);
    }
}

public record RefundExpectationsResult(
    long orderId,
    String refundRequestId,
    int markedCount
) {}
```

### 5.2 Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundExpectationsService implements RefundExpectationsPort {

    private final InventoryReservationRepository reservationRepo;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public RefundExpectationsResult registerExpectations(RefundExpectationsCommand cmd) {
        // 1. Idempotency check
        List<InventoryReservation> existing =
            reservationRepo.findRefundExpectedByOrderIdAndRefundRequestId(
                cmd.orderId(), cmd.refundRequestId());

        Set<Long> existingSet = existing.stream()
            .map(InventoryReservation::getOrderItemId)
            .collect(Collectors.toSet());
        Set<Long> requestedSet = new TreeSet<>(cmd.orderItemIds());

        if (!existingSet.isEmpty() && !existingSet.equals(requestedSet)) {
            // Conflict: same (order, refund_request_id) but different items
            throw new RefundScopeMismatchException(
                new ArrayList<>(existingSet), cmd.orderItemIds());
        }

        if (!existingSet.isEmpty()) {
            // Idempotent hit — same scope
            log.info("Refund expectations idempotent hit order={} rrid={}",
                     cmd.orderId(), cmd.refundRequestId());
            return new RefundExpectationsResult(
                cmd.orderId(), cmd.refundRequestId(), existingSet.size());
        }

        // 2. Validate at least 1 DEDUCTED reservation exists
        List<InventoryReservation> allDeducted = reservationRepo
            .findByOrderIdAndOrderItemIdInAndStatus(
                cmd.orderId(), cmd.orderItemIds(), ReservationStatus.DEDUCTED);

        if (allDeducted.isEmpty()) {
            throw new ReservationNotFoundException(
                "No DEDUCTED reservations for order=" + cmd.orderId()
                + " items=" + cmd.orderItemIds());
        }

        // 3. Update reservations
        int markedCount = reservationRepo.markRefundExpected(
            cmd.orderId(), cmd.refundRequestId(), cmd.orderItemIds());

        metrics.recordRefundExpectation(markedCount);

        return new RefundExpectationsResult(
            cmd.orderId(), cmd.refundRequestId(), markedCount);
    }
}
```

> Idempotency: replay cùng `(order_id, refund_request_id, order_item_ids)` → 200 OK; replay khác item set → 409 REFUND_SCOPE_MISMATCH.

---

## 6. UnlockSkuService

### 6.1 Command & Result

```java
public record UnlockSkuCommand(
    String skuId,
    long warehouseId,
    String reason,
    String correlationId
) {
    public UnlockSkuCommand {
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (warehouseId <= 0) throw new IllegalArgumentException("warehouseId invalid");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason required");
    }
}

public record UnlockSkuResult(
    String skuId,
    long warehouseId,
    boolean unlocked,
    Instant unlockedAt
) {}
```

### 6.2 Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UnlockSkuService implements UnlockSkuPort {

    private final SuspensionFlagManager suspensionManager;
    private final InventoryMetrics metrics;

    @Override
    public UnlockSkuResult unlock(UnlockSkuCommand cmd) {
        SkuWarehouseKey key = new SkuWarehouseKey(cmd.skuId(), cmd.warehouseId());

        boolean wasSuspended = suspensionManager.isSuspended(key);
        if (!wasSuspended) {
            throw new SkuNotSuspendedException(cmd.skuId(), cmd.warehouseId());
        }

        boolean deleted = suspensionManager.unsuspend(key);

        log.info("SKU unlocked by admin: sku={} warehouse={} reason={} correlation={}",
                 cmd.skuId(), cmd.warehouseId(), cmd.reason(), cmd.correlationId());

        metrics.recordUnlock();

        return new UnlockSkuResult(
            cmd.skuId(), cmd.warehouseId(), deleted, Instant.now());
    }
}
```

> KHÔNG có @Transactional — chỉ Redis operation. Admin tự chịu trách nhiệm verify `physical_inventory` đã nhất quán trước khi unlock.

---

## 7. Port Interfaces (Inbound)

> Các interface khai báo trong `port/inbound/` — implement bởi services ở module này.

```java
package com.platform.inventoryservice.port.inbound;

public interface RestockPort {
    RestockResult restock(RestockCommand cmd);
}

public interface ShipToCustomerPort {
    ShipToCustomerResult ship(ShipToCustomerCommand cmd);
}

public interface RefundExpectationsPort {
    RefundExpectationsResult registerExpectations(RefundExpectationsCommand cmd);
}

public interface UnlockSkuPort {
    UnlockSkuResult unlock(UnlockSkuCommand cmd);
}
```

> Các command/result records có thể đặt trong `port/inbound/` (DTO thuộc về use-case) hoặc `service/command/` (DTO thuộc về service). Convention đặt tại `service/command/` để services tự quản lý.

---

## 8. Exception Mapping

| Exception | HTTP Code (M13) | Trigger |
|:---|:---:|:---|
| `WarehouseNotFoundException` | 404 `WAREHOUSE_NOT_FOUND` | warehouse_id không tồn tại / inactive |
| `ReservationNotFoundException` | 404 `RESERVATION_NOT_FOUND` | DEDUCTED reservation không tìm thấy |
| `SkuNotSuspendedException` | 404 `SKU_NOT_SUSPENDED` | SKU chưa bị suspend |
| `RefundNotExpectedException` | 409 `REFUND_NOT_EXPECTED` | DEDUCTED reservation nhưng refund_expected = false |
| `RefundScopeMismatchException` | 409 `REFUND_SCOPE_MISMATCH` | Cùng (order, rrid) khác item set |

---

## 9. Transaction Discipline

| Service | Transaction | Notes |
|:---|:---|:---|
| RestockService | `@Transactional` | Multi-table (physical + reservations + ledger + outbox) |
| ShipToCustomerService | `@Transactional` | Single-table insert (outbox only) |
| RefundExpectationsService | `@Transactional` | Multi-row update |
| UnlockSkuService | none (Redis only) | No DB write |

---

## 10. Audit Logging

Mỗi admin operation log với structured fields:

```java
log.info("Restock operation: sku={} warehouse={} qty={} refType={} actor={} correlation={}",
         cmd.skuId(), cmd.warehouseId(), cmd.quantity(), cmd.referenceType(),
         currentActor, cmd.correlationId());
```

> `currentActor` lấy từ JWT subject claim (`SecurityContextHolder.getContext().getAuthentication().getName()`).

---

## 11. Metrics

| Metric | Type | Labels |
|:---|:---|:---|
| `inventory_restock_total` | Counter | `reference_type` |
| `inventory_restock_quantity_total` | Counter | `reference_type` |
| `inventory_restock_duration_seconds` | Timer | `reference_type` |
| `inventory_ship_to_customer_total` | Counter | — |
| `inventory_ship_to_customer_items_total` | Counter | — |
| `inventory_refund_expectations_marked_total` | Counter | — |
| `inventory_refund_expectations_conflict_total` | Counter | — |
| `inventory_unlock_sku_total` | Counter | — |
| `inventory_admin_error_total` | Counter | `operation`, `error_type` |

---

## 12. Testing

| Test | Mục đích |
|:---|:---|
| Restock ADMIN_RESTOCK happy path | Physical upsert + ledger + outbox + Redis sync |
| Restock ORDER_RETURNED_RESTOCK + all received | Refund snapshot, RETURN_RECEIVED_ALL outbox |
| Restock ORDER_RETURNED_RESTOCK + partial | No RETURN_RECEIVED_ALL yet |
| RefundExpectations idempotent | Replay same → 200 |
| RefundExpectations conflict | Replay different items → 409 |
| ShipToCustomer no reservation | 404 |
| ShipToCustomer happy path | Outbox insert only |
| UnlockSku not suspended | 404 |
| UnlockSku happy path | Redis DEL |

---

## 13. Verification

| Check | Cách verify |
|:---|:---|
| Restock ADMIN_RESTOCK | curl `/v1/inventory/restock` → 200, DB row updated, outbox row |
| Restock ORDER_RETURNED_RESTOCK last item | 200, `refund.allItemsReceived = true`, 2 outbox rows |
| Restock ORDER_RETURNED_RESTOCK partial | 200, `refund.allItemsReceived = false`, 1 outbox row |
| Ship to customer | 200, 1 outbox row INVENTORY_SHIP_TO_CUSTOMER |
| Refund expectations | 200, marked_count > 0 |
| Refund expectations conflict | 409 REFUND_SCOPE_MISMATCH |
| Unlock SKU | 200, Redis key deleted |

---

## 14. Files Created

```
src/main/java/com/platform/inventoryservice/service/
├── RestockService.java
├── ShipToCustomerService.java
├── RefundExpectationsService.java
├── UnlockSkuService.java
├── command/
│   ├── RestockCommand.java
│   ├── ShipToCustomerCommand.java
│   ├── RefundExpectationsCommand.java
│   └── UnlockSkuCommand.java
└── result/
    ├── RestockResult.java
    ├── ShipToCustomerResult.java
    ├── RefundExpectationsResult.java
    └── UnlockSkuResult.java

src/main/java/com/platform/inventoryservice/port/inbound/
├── RestockPort.java
├── ShipToCustomerPort.java
├── RefundExpectationsPort.java
└── UnlockSkuPort.java
```