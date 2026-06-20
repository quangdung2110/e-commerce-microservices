# Module 11: Temporal Reservation Activities

> **Depends on:** M05 (Repository), M06 (Redis Atomic Ops), M07 (Events)
> **Depended by:** M13 (HTTP API optional — for direct testing)

---

## 1. Goal

Triển khai 3 Temporal Activities được Order Saga gọi:

- `ReserveInventoryActivity` — giữ chỗ tồn kho (pre-payment).
- `DeductInventoryActivity` — xác nhận bán đứt (post-payment).
- `RollbackInventoryActivity` — giải phóng giữ chỗ (compensation: order cancel hoặc reservation expiry).

Plus Temporal Worker bootstrap để host các Activity Implementation này.

> Tham chiếu: `inventory_workflow.md` Luồng 1, 2, 3; `inventory_endpoints.md` Appendix.

---

## 2. Package Layout

```
com.platform.inventoryservice.temporal/
├── TemporalWorkerBootstrap.java         # @PostConstruct khởi tạo Worker
├── ReserveInventoryActivity.java
├── DeductInventoryActivity.java
├── RollbackInventoryActivity.java
├── activity/
│   ├── ReserveActivityImpl.java
│   ├── DeductActivityImpl.java
│   └── RollbackActivityImpl.java
├── command/
│   ├── ReserveCommand.java
│   ├── DeductCommand.java
│   └── RollbackCommand.java
└── result/
    ├── ReserveResult.java
    ├── DeductResult.java
    └── RollbackResult.java
```

> Interface `*Activity` định nghĩa contract (Temporal Activity interface). `*ActivityImpl` chứa logic.

---

## 3. Temporal Activity Contracts

### 3.1 ReserveCommand & ReserveResult

```java
public record ReserveCommand(
    long orderId,
    UUID routePlanId,
    List<ReserveCommandItem> items
) {
    public ReserveCommand {
        if (orderId <= 0) throw new IllegalArgumentException("orderId invalid");
        if (routePlanId == null) throw new IllegalArgumentException("routePlanId required");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items required");
        items = List.copyOf(items);
    }
}

public record ReserveCommandItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity
) {}

public record ReserveResult(
    long orderId,
    List<ReserveResultItem> items,
    Instant reservedAt,
    Instant expiresAt
) {
    public ReserveResult {
        items = List.copyOf(items);
    }
}

public record ReserveResultItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity,
    ReservationStatus status
) {}
```

### 3.2 DeductCommand & DeductResult

```java
public record DeductCommand(
    long orderId,
    List<DeductCommandItem> items
) {
    public DeductCommand {
        items = List.copyOf(items);
    }
}

public record DeductCommandItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity
) {}

public record DeductResult(
    long orderId,
    List<DeductResultItem> items,
    Instant deductedAt
) {
    public DeductResult { items = List.copyOf(items); }
}

public record DeductResultItem(
    long orderItemId,
    String skuId,
    long warehouseId,
    int quantity,
    ReservationStatus status
) {}
```

### 3.3 RollbackCommand & RollbackResult

```java
public record RollbackCommand(
    long orderId,
    List<RollbackCommandItem> items,
    ChangeType changeType    // ORDER_CANCELLED | RESERVATION_EXPIRED
) {
    public RollbackCommand {
        items = List.copyOf(items);
        if (changeType != ChangeType.ORDER_CANCELLED
            && changeType != ChangeType.RESERVATION_EXPIRED) {
            throw new IllegalArgumentException("Unsupported changeType: " + changeType);
        }
    }
}

public record RollbackCommandItem(
    long orderItemId,
    String skuId,
    String productId,
    long warehouseId,
    int quantity
) {}

public record RollbackResult(
    long orderId,
    List<RollbackResultItem> items,
    ChangeType changeType,
    Instant rolledBackAt
) {
    public RollbackResult { items = List.copyOf(items); }
}

public record RollbackResultItem(
    long orderItemId,
    String skuId,
    long warehouseId,
    int quantity,
    ReservationStatus status
) {}
```

---

## 4. Activity Interfaces (Temporal)

```java
@ActivityInterface
public interface ReserveInventoryActivity {
    @ActivityMethod(name = "ReserveInventory")
    ReserveResult reserve(ReserveCommand cmd);
}

@ActivityInterface
public interface DeductInventoryActivity {
    @ActivityMethod(name = "DeductInventory")
    DeductResult deduct(DeductCommand cmd);
}

@ActivityInterface
public interface RollbackInventoryActivity {
    @ActivityMethod(name = "RollbackInventory")
    RollbackResult rollback(RollbackCommand cmd);
}
```

---

## 5. ReserveActivityImpl — Logic chính

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ReserveActivityImpl implements ReserveInventoryActivity {

    private final StockAtomicOps stockOps;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public ReserveResult reserve(ReserveCommand cmd) {
        String correlationId = Workflow.getInfo().getWorkflowId();   // Temporal Workflow ID
        Instant reservedAt = Instant.now();
        Instant expiresAt = reservedAt.plus(
            Duration.ofMinutes(props.getRestock().getReservationTtlMinutes()));
        List<ReserveResultItem> results = new ArrayList<>();
        List<ReserveCommandItem> applied = new ArrayList<>();

        try {
            // 1. Lua reserve cho từng item
            for (ReserveCommandItem item : cmd.items()) {
                SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());

                StockMutationResult res = stockOps.reserve(
                    key, cmd.orderId(), item.orderItemId(),
                    item.productId(), item.quantity(), correlationId);

                switch (res.returnCode()) {
                    case APPLIED -> {
                        applied.add(item);

                        // 2. Insert reservation row (idempotent via partial UNIQUE)
                        InventoryReservation reservation = InventoryReservation.newReserved(
                            cmd.orderId(), item.orderItemId(),
                            item.skuId(), item.productId(), item.warehouseId(),
                            item.quantity(), cmd.routePlanId(), expiresAt);
                        reservationRepo.insertIfAbsent(reservation);

                        results.add(new ReserveResultItem(
                            item.orderItemId(), item.skuId(), item.productId(), item.warehouseId(),
                            item.quantity(), ReservationStatus.RESERVED));
                    }
                    case IDEMPOTENT_HIT -> {
                        log.info("Reserve idempotent hit for sku={} order={}",
                                 item.skuId(), cmd.orderId());
                        results.add(new ReserveResultItem(
                            item.orderItemId(), item.skuId(), item.productId(), item.warehouseId(),
                            item.quantity(), ReservationStatus.RESERVED));
                    }
                    case OUT_OF_STOCK -> {
                        // Rollback đã apply trước đó
                        rollbackApplied(cmd.orderId(), applied, ChangeType.ORDER_CANCELLED,
                                        correlationId);
                        throw new OutOfStockException(item.skuId(), item.warehouseId());
                    }
                    case SKU_SUSPENDED -> {
                        throw new SkuSuspendedException(
                            item.skuId(), item.warehouseId(), "MANUAL_REVIEW");
                    }
                    case INVARIANT_DRIFT -> {
                        // Caller đã handle suspension flag trong M06
                        throw new InvariantDriftException(item.skuId(), item.warehouseId());
                    }
                    default -> throw new IllegalStateException(
                        "Unexpected return code: " + res.returnCode());
                }
            }

            metrics.recordReserveSuccess(cmd.items().size());
            return new ReserveResult(cmd.orderId(), results, reservedAt, expiresAt);

        } catch (DomainException e) {
            metrics.recordReserveFailure(e.getClass().getSimpleName());
            throw e;   // Temporal retry policy sẽ xử lý
        }
    }

    private void rollbackApplied(long orderId, List<ReserveCommandItem> applied,
                                 ChangeType changeType, String correlationId) {
        for (ReserveCommandItem item : applied) {
            try {
                SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());
                stockOps.release(key, orderId, item.orderItemId(),
                                 item.productId(), item.quantity(),
                                 correlationId, changeType);
            } catch (Exception e) {
                log.error("Rollback failed for order={} sku={}", orderId, item.skuId(), e);
                // Drift risk: log alert; admin manual reconcile
            }
        }
    }
}
```

> ⚠️ Lua `reserve` đã append event vào Redis Stream — Ledger Worker sẽ pick up và ghi DB ledger + outbox. Activity chỉ insert `inventory_reservations` row (idempotent qua partial UNIQUE `(order_id, sku_id, warehouse_id) WHERE status='RESERVED'`).

---

## 6. DeductActivityImpl — Logic chính

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DeductActivityImpl implements DeductInventoryActivity {

    private final StockAtomicOps stockOps;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public DeductResult deduct(DeductCommand cmd) {
        String correlationId = Workflow.getInfo().getWorkflowId();
        Instant deductedAt = Instant.now();
        List<DeductResultItem> results = new ArrayList<>();

        for (DeductCommandItem item : cmd.items()) {
            SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());

            StockMutationResult res = stockOps.deduct(
                key, cmd.orderId(), item.orderItemId(),
                item.productId(), item.quantity(), correlationId);

            switch (res.returnCode()) {
                case APPLIED -> {
                    // Reservation row UPDATE status RESERVED → DEDUCTED.
                    // Lookup theo (orderId, skuId, warehouseId) — single row per reservation lifecycle.
                    Optional<InventoryReservation> existing = reservationRepo
                        .findActiveByOrderSkuWarehouse(cmd.orderId(), item.skuId(), item.warehouseId());
                    if (existing.isPresent()) {
                        existing.get().markDeducted();
                        reservationRepo.save(existing.get());
                    }
                    // Ledger row + outbox row được Ledger Worker (M09) handle qua event stream
                    results.add(new DeductResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.DEDUCTED));
                }
                case IDEMPOTENT_HIT -> {
                    log.info("Deduct idempotent hit sku={} order={}",
                             item.skuId(), cmd.orderId());
                    results.add(new DeductResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.DEDUCTED));
                }
                case INSUFFICIENT_RESERVED -> {
                    throw new InsufficientReservedException(item.skuId(), item.warehouseId());
                }
                case SKU_SUSPENDED -> {
                    throw new SkuSuspendedException(
                        item.skuId(), item.warehouseId(), "MANUAL_REVIEW");
                }
                case INVARIANT_DRIFT -> {
                    throw new InvariantDriftException(item.skuId(), item.warehouseId());
                }
                default -> throw new IllegalStateException(
                    "Unexpected return code: " + res.returnCode());
            }
        }

        metrics.recordDeductSuccess(cmd.items().size());
        return new DeductResult(cmd.orderId(), results, deductedAt);
    }
}
```

> Deduct KHÔNG rollback (đã payment xong, không release). Nếu `INSUFFICIENT_RESERVED` → trả lỗi nghiệp vụ cho Order Saga xử lý (manual investigation).

---

## 7. RollbackActivityImpl — Logic chính

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RollbackActivityImpl implements RollbackInventoryActivity {

    private final StockAtomicOps stockOps;
    private final InventoryReservationRepository reservationRepo;
    private final InventoryMetrics metrics;

    @Override
    @Transactional
    public RollbackResult rollback(RollbackCommand cmd) {
        String correlationId = Workflow.getInfo().getWorkflowId();
        Instant rolledBackAt = Instant.now();
        List<RollbackResultItem> results = new ArrayList<>();

        for (RollbackCommandItem item : cmd.items()) {
            SkuWarehouseKey key = new SkuWarehouseKey(item.skuId(), item.warehouseId());

            StockMutationResult res = stockOps.release(
                key, cmd.orderId(), item.orderItemId(),
                item.productId(), item.quantity(),
                correlationId, cmd.changeType());

            switch (res.returnCode()) {
                case APPLIED -> {
                    // Reservation row UPDATE status RESERVED → CANCELLED.
                    // Lookup theo (orderId, skuId, warehouseId) — single row per reservation lifecycle.
                    Optional<InventoryReservation> existing = reservationRepo
                        .findActiveByOrderSkuWarehouse(cmd.orderId(), item.skuId(), item.warehouseId());
                    if (existing.isPresent()) {
                        existing.get().markCancelled();
                        reservationRepo.save(existing.get());
                    }
                    results.add(new RollbackResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.CANCELLED));
                }
                case IDEMPOTENT_HIT -> {
                    results.add(new RollbackResultItem(
                        item.orderItemId(), item.skuId(), item.warehouseId(),
                        item.quantity(), ReservationStatus.CANCELLED));
                }
                case RELEASE_UNDERFLOW -> {
                    throw new ReleaseUnderflowException(item.skuId(), item.warehouseId());
                }
                case SKU_SUSPENDED -> {
                    throw new SkuSuspendedException(
                        item.skuId(), item.warehouseId(), "MANUAL_REVIEW");
                }
                default -> throw new IllegalStateException(
                    "Unexpected return code: " + res.returnCode());
            }
        }

        metrics.recordRollbackSuccess(cmd.items().size(), cmd.changeType().name());
        return new RollbackResult(cmd.orderId(), results, cmd.changeType(), rolledBackAt);
    }
}
```

---

## 8. TemporalWorkerBootstrap

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class TemporalWorkerBootstrap {

    private final InventoryProperties props;
    private final ReserveActivityImpl reserveImpl;
    private final DeductActivityImpl deductImpl;
    private final RollbackActivityImpl rollbackImpl;

    private Worker worker;

    @PostConstruct
    void start() {
        WorkflowClient client = WorkflowClient.newInstance(
            WorkflowServiceStubs.newInstance(
                WorkflowServiceStubsOptions.newBuilder()
                    .setTarget(props.getTemporal().getTarget())
                    .build()),
            WorkflowClientOptions.newBuilder()
                .setNamespace(props.getTemporal().getNamespace())
                .build());

        worker = factory(client).newWorker(props.getTemporal().getTaskQueue());
        worker.registerActivitiesImplementations(reserveImpl, deductImpl, rollbackImpl);

        // Start worker in background
        Thread t = new Thread(() -> {
            try {
                factory.start();
            } catch (Exception e) {
                log.error("Temporal worker failed", e);
            }
        }, "temporal-worker-bootstrap");
        t.setDaemon(true);
        t.start();

        log.info("Temporal Worker started: target={}, namespace={}, taskQueue={}",
                 props.getTemporal().getTarget(),
                 props.getTemporal().getNamespace(),
                 props.getTemporal().getTaskQueue());
    }

    private WorkerFactory factory(WorkflowClient client) {
        return WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder()
            .setWorkerIdentity(props.getTemporal().getWorkerIdentity())
            .setMaxWorkflowThreadCount(20)
            .build());
    }

    @PreDestroy
    void stop() {
        if (worker != null) {
            worker.shutdown();
            log.info("Temporal Worker shutdown complete");
        }
    }
}
```

---

## 9. Temporal Retry Policy

| Activity | Retry Policy | Initial Interval | Max Interval | Max Attempts |
|:---|:---|:---|:---|:---|
| Reserve | Default + idempotent | 1s | 30s | 5 |
| Deduct | Default + idempotent | 1s | 30s | 5 |
| Rollback | Default + idempotent | 1s | 30s | 5 |

> Activity throw `DomainException` không retry — Temporal Saga nhận lỗi nghiệp vụ và xử lý compensation.
> Activity throw `InventoryUnavailableException` (Redis/Kafka down) → retry theo policy.

```java
ActivityOptions options = ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofSeconds(30))
    .setRetryOptions(RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(1))
        .setMaximumInterval(Duration.ofSeconds(30))
        .setMaximumAttempts(5)
        .setBackoffCoefficient(2.0)
        .build())
    .build();
```

---

## 10. Reservation Expiry Handling

> ⚠️ Order Service owns the 30-min reservation timer. Khi timer fire trong Order Saga:
>
> 1. Order Workflow state vẫn pre-payment → gọi `RollbackInventoryActivity` với `change_type = RESERVATION_EXPIRED`.
> 2. RollbackActivity gọi Lua script release (M03).
> 3. Event `INVENTORY_STOCK_MUTATION` với `change_type=RESERVATION_EXPIRED` được ghi vào Redis Stream.
> 4. Ledger Worker update `inventory_reservations.status = CANCELLED` + write ledger với `reference_type = RESERVATION_EXPIRED`.

> **KHÔNG** có scheduled scan trong Inventory Service.

---

## 11. All-or-Nothing Multi-Item Semantics

| Failure | Item i | Item i+1 | Hành động |
|:---|:---|:---|:---|
| `OUT_OF_STOCK` | reject | — | Rollback items 0..i-1 đã apply (gọi Release Lua) |
| `INSUFFICIENT_RESERVED` (deduct) | reject | — | Không rollback (đã payment xong) |
| `RELEASE_UNDERFLOW` (rollback) | reject | — | Không rollback nữa; fail activity |
| `SKU_SUSPENDED` | reject | — | Không rollback; fail activity |
| `INVARIANT_DRIFT` | reject | — | Set suspend flag, fail activity |
| `IDEMPOTENT_HIT` | success | tiếp tục | Coi như applied, không emit event mới |
| Exception (Redis down) | — | — | Temporal retry |

---

## 12. Database Reads từ Activity

> Activity cần lookup reservation row existence để UPDATE status (Deduct, Rollback). Lookup dùng `findByIdempotencyKey` qua repository (M05).

> KHÔNG đọc `physical_inventory` từ Activity — toàn bộ physical state đi qua Lua + Ledger Worker.

---

## 13. Testing

| Test | Mục đích |
|:---|:---|
| Reserve happy path | All items apply, reservation rows inserted |
| Reserve partial failure | Rollback applied items |
| Reserve idempotent | Second call returns IDEMPOTENT_HIT |
| Deduct happy path | Status RESERVED → DEDUCTED |
| Deduct INSUFFICIENT_RESERVED | Throw exception, fail activity |
| Rollback ORDER_CANCELLED | Status RESERVED → CANCELLED |
| Rollback RESERVATION_EXPIRED | Same flow, different change_type |
| Temporal Worker integration | Start worker, mock client, invoke activity |

---

## 14. Verification

| Check | Cách verify |
|:---|:---|
| Worker connects Temporal | Log "Temporal Worker started" |
| Reserve success | Activity returns ReserveResult; Redis hash updated; reservation row in DB |
| Reserve rollback | Out-of-stock item → previous items released |
| Deduct idempotent | Same DeductCommand twice → both success, status DEDUCTED |
| Rollback idempotent | Same RollbackCommand twice → both success, status CANCELLED |
| Activity exception mapping | OUT_OF_STOCK → OutOfStockException → Temporal saga compensation |

---

## 15. Files Created

```
src/main/java/com/platform/inventoryservice/temporal/
├── TemporalWorkerBootstrap.java
├── ReserveInventoryActivity.java
├── DeductInventoryActivity.java
├── RollbackInventoryActivity.java
├── activity/
│   ├── ReserveActivityImpl.java
│   ├── DeductActivityImpl.java
│   └── RollbackActivityImpl.java
├── command/
│   ├── ReserveCommand.java
│   ├── DeductCommand.java
│   └── RollbackCommand.java
└── result/
    ├── ReserveResult.java
    ├── DeductResult.java
    └── RollbackResult.java
```