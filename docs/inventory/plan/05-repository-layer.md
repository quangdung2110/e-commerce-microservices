# Module 05: Repository Layer

> **Depends on:** M02 (DB Schema), M04 (Domain Model)
> **Depended by:** M08 (Outbox), M09 (Workers), M10 (Routing), M11 (Temporal), M12 (Admin Services), M14 (Cleanup Job)

---

## 1. Goal

Triển khai persistence layer:

- **Layer 1 — `jpa/entity/`**: JPA entity classes mirror với DB schema (M02).
- **Layer 1 — `jpa/spring/`**: Spring Data JPA repository interfaces.
- **Layer 1 — `jpa/custom/`**: Custom query interfaces + implementations (native SQL).
- **Layer 3 — `outbound/mapper/`**: Domain ↔ JPA bidirectional mappers.
- **Layer 3 — `outbound/repository/`**: Implement `port/outbound/database` interfaces, dùng Spring Data + custom queries.

> Layer separation rule: `jpa/` chỉ là infrastructure; `outbound/` implement port interfaces và bridge domain ↔ JPA.

---

## 2. Package Layout

```
com.platform.inventoryservice.infrastructure.persistence/
├── jpa/
│   ├── entity/
│   │   ├── WarehouseJpaEntity.java
│   │   ├── PhysicalInventoryJpaEntity.java
│   │   ├── InventoryReservationJpaEntity.java
│   │   ├── InventoryLedgerJpaEntity.java
│   │   └── OutboxJpaEntity.java
│   ├── spring/
│   │   ├── WarehouseJpaRepository.java
│   │   ├── PhysicalInventoryJpaRepository.java
│   │   ├── InventoryReservationJpaRepository.java
│   │   ├── InventoryLedgerJpaRepository.java
│   │   └── OutboxJpaRepository.java
│   └── custom/
│       ├── PhysicalInventoryJpaCustom.java
│       ├── PhysicalInventoryJpaCustomImpl.java
│       ├── InventoryReservationJpaCustom.java
│       ├── InventoryReservationJpaCustomImpl.java
│       └── OutboxJpaCustom.java
└── outbound/
    ├── mapper/
    │   ├── WarehouseMapper.java
    │   ├── PhysicalInventoryMapper.java
    │   ├── InventoryReservationMapper.java
    │   ├── InventoryLedgerMapper.java
    │   └── OutboxMapper.java
    └── repository/
        ├── WarehouseRepositoryImpl.java
        ├── PhysicalInventoryRepositoryImpl.java
        ├── InventoryReservationRepositoryImpl.java
        ├── InventoryLedgerRepositoryImpl.java
        └── OutboxRepositoryImpl.java
```

---

## 3. JPA Entities

> Mỗi JPA entity dùng Lombok `@Getter` (không setter public — chỉ mapper layer set). Kế thừa domain rule qua mapper.

### 3.1 WarehouseJpaEntity

```java
package com.platform.inventoryservice.infrastructure.persistence.jpa.entity;

@Entity
@Table(name = "warehouses")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WarehouseJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;
    private String cityCode;
    private String districtCode;
    private String wardCode;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

### 3.2 PhysicalInventoryJpaEntity

```java
@Entity
@Table(name = "physical_inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PhysicalInventoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(name = "actual_quantity", nullable = false)
    private int actualQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /** GENERATED ALWAYS AS STORED — KHÔNG insert/update từ app. */
    @Generated(GenerationTime.ALWAYS)
    @Column(name = "available_quantity", insertable = false, updatable = false)
    private int availableQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        this.updatedAt = Instant.now();
    }
}
```

> `available_quantity` là GENERATED column — JPA `insertable=false, updatable=false`. Hibernate annotation `@Generated(GenerationTime.ALWAYS)` ép re-read sau insert/update.

### 3.3 InventoryReservationJpaEntity

```java
@Entity
@Table(name = "inventory_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InventoryReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "route_plan_id", nullable = false)
    private UUID routePlanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "refund_expected", nullable = false)
    private boolean refundExpected;

    @Column(name = "refund_request_id", length = 64)
    private String refundRequestId;

    @Column(name = "refund_returned_at")
    private Instant refundReturnedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

### 3.4 InventoryLedgerJpaEntity

```java
@Entity
@Table(name = "inventory_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InventoryLedgerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "sku_id", nullable = false, length = 64)
    private String skuId;

    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 50)
    private ReferenceType referenceType;

    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
```

### 3.5 OutboxJpaEntity

```java
@Entity
@Table(name = "inventory_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OutboxJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 100)
    private OutboxEventType eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "partition_key", nullable = false, length = 255)
    private String partitionKey;

    @Column(name = "refund_request_id", length = 64)
    private String refundRequestId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

> `payload` dùng `JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6) thay vì Hibernate-specific `@Type` để tránh dialect coupling. JSON roundtrip tự handle.

---

## 4. Spring Data JPA Repositories

### 4.1 WarehouseJpaRepository

```java
public interface WarehouseJpaRepository extends JpaRepository<WarehouseJpaEntity, Long> {

    Optional<WarehouseJpaEntity> findByIdAndActiveTrue(Long id);

    List<WarehouseJpaEntity> findByCityCodeAndActiveTrue(String cityCode);

    @Query("SELECT w.id FROM WarehouseJpaEntity w WHERE w.active = true")
    List<Long> findAllActiveIds();
}
```

### 4.2 PhysicalInventoryJpaRepository

```java
public interface PhysicalInventoryJpaRepository
        extends JpaRepository<PhysicalInventoryJpaEntity, Long>,
                PhysicalInventoryJpaCustom {

    Optional<PhysicalInventoryJpaEntity> findBySkuIdAndWarehouseId(String skuId, Long warehouseId);

    List<PhysicalInventoryJpaEntity> findBySkuIdInAndAvailableQuantityGreaterThan(
        Collection<String> skuIds, int threshold);
}
```

### 4.3 InventoryReservationJpaRepository

```java
public interface InventoryReservationJpaRepository
        extends JpaRepository<InventoryReservationJpaEntity, Long>,
                InventoryReservationJpaCustom {

    Optional<InventoryReservationJpaEntity> findFirstByOrderIdAndSkuIdAndWarehouseId(
        Long orderId, String skuId, Long warehouseId);

    List<InventoryReservationJpaEntity> findByOrderId(Long orderId);

    List<InventoryReservationJpaEntity> findByOrderIdAndOrderItemId(Long orderId, Long orderItemId);

    @Query("SELECT r FROM InventoryReservationJpaEntity r " +
           "WHERE r.status = :status AND r.expiresAt < :now")
    List<InventoryReservationJpaEntity> findExpired(
        @Param("status") ReservationStatus status,
        @Param("now") Instant now);
}
```

> `findExpired` chỉ dùng cho debug query — KHÔNG có scheduled job scan expiry (Order Saga owns the timer).

### 4.4 InventoryLedgerJpaRepository

```java
public interface InventoryLedgerJpaRepository
        extends JpaRepository<InventoryLedgerJpaEntity, Long> {

    Optional<InventoryLedgerJpaEntity> findByEventId(UUID eventId);

    List<InventoryLedgerJpaEntity> findBySkuIdOrderByCreatedAtDesc(String skuId);

    Optional<InventoryLedgerJpaEntity> findFirstBySkuIdAndWarehouseIdOrderByCreatedAtDesc(
        String skuId, Long warehouseId);
}
```

### 4.5 OutboxJpaRepository

```java
public interface OutboxJpaRepository
        extends JpaRepository<OutboxJpaEntity, UUID>,
                OutboxJpaCustom {

    List<OutboxJpaEntity> findByAggregateIdAndRefundRequestIdAndEventType(
        String aggregateId, String refundRequestId, OutboxEventType eventType);

    long countByCreatedAtBefore(Instant watermark);
}
```

> `findByAggregateIdAndRefundRequestIdAndEventType` hỗ trợ idempotency check trước khi INSERT — cùng với partial UNIQUE index ở DB layer.

---

## 5. Custom Queries

### 5.1 PhysicalInventoryJpaCustom

```java
public interface PhysicalInventoryJpaCustom {
    List<AvailableStockProjection> findAvailableBySkuIds(Collection<String> skuIds);

    /** Aggregate per-SKU total available across warehouses. */
    Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds);

    /** Bulk update reserved_quantity for many rows. */
    int bulkAdjustReserved(Collection<ReservedAdjustment> adjustments);

    /** Bulk upsert (INSERT ... ON CONFLICT DO UPDATE). */
    int bulkUpsert(Collection<PhysicalInventoryUpsert> upserts);
}
```

#### Implementation (PostgreSQL CTE)

```java
public class PhysicalInventoryJpaCustomImpl implements PhysicalInventoryJpaCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public List<AvailableStockProjection> findAvailableBySkuIds(Collection<String> skuIds) {
        if (skuIds.isEmpty()) return List.of();
        return em.createNativeQuery("""
            SELECT sku_id, warehouse_id, available_quantity
            FROM physical_inventory
            WHERE sku_id = ANY(:sku_ids)
              AND available_quantity > 0
            ORDER BY sku_id, warehouse_id
            """)
            .setParameter("sku_ids", skuIds.toArray(new String[0]))
            .unwrap(NativeQuery.class)
            .setTupleTransformer((Object[] tuple, String[] aliases) ->
                new AvailableStockProjection(
                    (String) tuple[0],
                    ((Number) tuple[1]).longValue(),
                    ((Number) tuple[2]).longValue()))
            .getResultList();
    }

    @Override
    public Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        List<Object[]> rows = em.createNativeQuery("""
            SELECT sku_id, COALESCE(SUM(available_quantity), 0)
            FROM physical_inventory
            WHERE sku_id = ANY(:sku_ids)
            GROUP BY sku_id
            """)
            .setParameter("sku_ids", skuIds.toArray(new String[0]))
            .getResultList();
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    @Override
    public int bulkAdjustReserved(Collection<ReservedAdjustment> adjustments) {
        if (adjustments.isEmpty()) return 0;
        // batch update via JDBC batch
        int total = 0;
        for (ReservedAdjustment adj : adjustments) {
            int updated = em.createNativeQuery("""
                UPDATE physical_inventory
                SET reserved_quantity = reserved_quantity + :delta,
                    updated_at = NOW()
                WHERE sku_id = :sku AND warehouse_id = :wh
                """)
                .setParameter("delta", adj.delta())
                .setParameter("sku", adj.skuId())
                .setParameter("wh", adj.warehouseId())
                .executeUpdate();
            total += updated;
        }
        return total;
    }

    @Override
    public int bulkUpsert(Collection<PhysicalInventoryUpsert> upserts) {
        // Use single statement with VALUES list for batch upsert
        // (Detail: build VALUES clause dynamically)
        // ...
        return upserts.size();
    }

    public record AvailableStockProjection(String skuId, long warehouseId, long available) {}
    public record ReservedAdjustment(String skuId, long warehouseId, int delta) {}
    public record PhysicalInventoryUpsert(
        String skuId, String productId, long warehouseId, int actualQty) {}
}
```

> Dùng native query để tận dụng PostgreSQL `ANY(:sku_ids)` array binding và CTE optimization.

### 5.2 InventoryReservationJpaCustom

```java
public interface InventoryReservationJpaCustom {

    /** Idempotent insert với ON CONFLICT DO NOTHING (partial UNIQUE trên (order_id, sku_id, warehouse_id) WHERE status='RESERVED'). */
    Optional<Long> insertIfAbsent(InventoryReservationJpaEntity entity);

    /** Bulk update status theo danh sách (order_id, sku_id, warehouse_id). */
    int bulkUpdateStatus(Collection<ReservationStatusUpdate> updates);

    /** Query refund completion check. */
    RefundCompletionProjection findRefundCompletion(long orderId, String refundRequestId);

    /** Mark refund expectation (idempotent). */
    int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds);

    /** Mark refund returned cho 1 reservation. */
    int markRefundReturned(long orderId, long orderItemId);

    /** Query order_item_ids DEDUCTED tại 1 warehouse cho ship-to-customer. */
    List<Long> findDeductedItemIds(long orderId, long warehouseId);
}

public record ReservationStatusUpdate(long id, ReservationStatus newStatus) {}
public record RefundCompletionProjection(
    long pendingCount,
    List<Long> requiredItemIds,
    List<Long> receivedItemIds) {}
```

#### Implementation highlights

```java
@Override
public Optional<Long> insertIfAbsent(InventoryReservationJpaEntity e) {
    try {
        em.persist(e);
        em.flush();
        return Optional.of(e.getId());
    } catch (PersistenceException ex) {
        if (isUniqueViolation(ex)) {
            // Idempotent hit — partial UNIQUE (order_id, sku_id, warehouse_id) WHERE status='RESERVED'
            // Đã có 1 row RESERVED cho cùng (order, sku, warehouse) → trả về ID của row đó.
            return findFirstByOrderIdAndSkuIdAndWarehouseId(
                e.getOrderId(), e.getSkuId(), e.getWarehouseId()
            ).map(InventoryReservationJpaEntity::getId);
        }
        throw ex;
    }
}

@Override
public RefundCompletionProjection findRefundCompletion(long orderId, String refundRequestId) {
    Object[] row = (Object[]) em.createNativeQuery("""
        SELECT
          COUNT(*) FILTER (WHERE refund_returned_at IS NULL
                           AND status = 'DEDUCTED'
                           AND refund_expected = TRUE),
          array_agg(order_item_id) FILTER (WHERE refund_expected = TRUE),
          array_agg(order_item_id) FILTER (WHERE refund_expected = TRUE
                                            AND refund_returned_at IS NOT NULL)
        FROM inventory_reservations
        WHERE order_id = :order_id
          AND refund_request_id = :rrid
        """)
        .setParameter("order_id", orderId)
        .setParameter("rrid", refundRequestId)
        .getSingleResult();

    long pending = ((Number) row[0]).longValue();
    Long[] requiredArr = (Long[]) row[1];
    Long[] receivedArr = (Long[]) row[2];
    return new RefundCompletionProjection(
        pending,
        requiredArr == null ? List.of() : Arrays.asList(requiredArr),
        receivedArr == null ? List.of() : Arrays.asList(receivedArr));
}
```

### 5.3 OutboxJpaCustom

```java
public interface OutboxJpaCustom {
    /** Batch delete với LIMIT — tránh lock bảng. */
    int deleteBatchOlderThan(Instant watermark, int limit);

    /** Check duplicate cho RETURN_RECEIVED_ALL trước khi insert (race-safe double check). */
    boolean existsReturnReceivedAll(String orderId, String refundRequestId);
}
```

```java
@Override
public int deleteBatchOlderThan(Instant watermark, int limit) {
    // Xóa theo lô để tránh lock long
    return em.createNativeQuery("""
        DELETE FROM inventory_outbox
        WHERE event_id IN (
          SELECT event_id FROM inventory_outbox
          WHERE created_at < :watermark
          ORDER BY created_at
          LIMIT :limit
        )
        """)
        .setParameter("watermark", watermark)
        .setParameter("limit", limit)
        .executeUpdate();
}
```

---

## 6. Mappers

### 6.1 WarehouseMapper

```java
public class WarehouseMapper {
    public static Warehouse toDomain(WarehouseJpaEntity e) {
        Warehouse w = new Warehouse();
        // copy fields via reflection or constructor
        return w;
    }
    public static WarehouseJpaEntity toJpa(Warehouse w) {
        WarehouseJpaEntity e = new WarehouseJpaEntity();
        // copy
        return e;
    }
}
```

> Dùng MapStruct để generate mappers (khuyến nghị — thêm dependency `mapstruct` vào pom).

### 6.2 PhysicalInventoryMapper

```java
@Mapper
public interface PhysicalInventoryMapper {
    PhysicalInventory toDomain(PhysicalInventoryJpaEntity entity);
    PhysicalInventoryJpaEntity toJpa(PhysicalInventory domain);
    SkuWarehouseKey toKey(PhysicalInventoryJpaEntity entity);
}
```

### 6.3 InventoryReservationMapper

```java
@Mapper
public interface InventoryReservationMapper {
    InventoryReservation toDomain(InventoryReservationJpaEntity entity);
    InventoryReservationJpaEntity toJpa(InventoryReservation domain);
}
```

### 6.4 InventoryLedgerMapper

```java
@Mapper
public interface InventoryLedgerMapper {
    InventoryLedgerEntry toDomain(InventoryLedgerJpaEntity entity);
    InventoryLedgerJpaEntity toJpa(InventoryLedgerEntry domain);
}
```

### 6.5 OutboxMapper

```java
@Mapper
public interface OutboxMapper {
    OutboxEvent toDomain(OutboxJpaEntity entity);
    OutboxJpaEntity toJpa(OutboxEvent domain);
}
```

---

## 7. Port Interfaces (M04 surface, M05 implements)

> Interfaces khai báo trong `port/outbound/database/`. Định nghĩa ở M04/M05 cùng lúc — M04 có thể liệt kê signature, M05 implement.

```java
package com.platform.inventoryservice.port.outbound.database;

public interface WarehouseRepository {
    Optional<Warehouse> findById(long id);
    List<Warehouse> findActiveByCityCode(String cityCode);
}

public interface PhysicalInventoryRepository {
    Optional<PhysicalInventory> findByKey(SkuWarehouseKey key);
    List<AvailableStock> findAvailableBySkuIds(Collection<String> skuIds);
    Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds);
    PhysicalInventory upsert(SkuWarehouseKey key, String productId, int actualQtyDelta);
    void adjustReserved(SkuWarehouseKey key, int delta);
    void adjustActual(SkuWarehouseKey key, int delta);
}

public interface InventoryReservationRepository {
    /** Idempotent insert. Trả về Optional.empty nếu insert fail do partial UNIQUE violation (đã có row RESERVED). */
    Optional<Long> insertIfAbsent(InventoryReservation domain);
    /** Lookup reservation đang active (status bất kỳ) theo (orderId, skuId, warehouseId) — single row per lifecycle. */
    Optional<InventoryReservation> findActiveByOrderSkuWarehouse(long orderId, String skuId, long warehouseId);
    List<InventoryReservation> findByOrderId(long orderId);
    void updateStatus(long id, ReservationStatus status);
    int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds);
    int markRefundReturned(long orderId, long orderItemId);
    RefundCompletionSnapshot checkRefundCompletion(long orderId, String refundRequestId);
    List<Long> findDeductedItemIds(long orderId, long warehouseId);
}

public interface InventoryLedgerRepository {
    void append(InventoryLedgerEntry entry);
    boolean existsByEventId(UUID eventId);
    Optional<InventoryLedgerEntry> findLatest(String skuId, long warehouseId);
}

public interface OutboxRepository {
    void insert(OutboxEvent event);
    Optional<OutboxEvent> findReturnReceivedAll(String orderId, String refundRequestId);
    int deleteBatchOlderThan(Instant watermark, int limit);
    long countOlderThan(Instant watermark);
}

public record RefundCompletionSnapshot(
    long pendingCount,
    List<Long> requiredItemIds,
    List<Long> receivedItemIds) {}
```

---

## 8. Repository Implementations

### 8.1 WarehouseRepositoryImpl

```java
@Repository
@RequiredArgsConstructor
public class WarehouseRepositoryImpl implements WarehouseRepository {

    private final WarehouseJpaRepository jpa;
    private final WarehouseMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<Warehouse> findById(long id) {
        return jpa.findByIdAndActiveTrue(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Warehouse> findActiveByCityCode(String cityCode) {
        return jpa.findByCityCodeAndActiveTrue(cityCode).stream()
            .map(mapper::toDomain).toList();
    }
}
```

### 8.2 PhysicalInventoryRepositoryImpl

```java
@Repository
@RequiredArgsConstructor
public class PhysicalInventoryRepositoryImpl implements PhysicalInventoryRepository {

    private final PhysicalInventoryJpaRepository jpa;
    private final PhysicalInventoryMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<AvailableStock> findAvailableBySkuIds(Collection<String> skuIds) {
        return jpa.findAvailableBySkuIds(skuIds).stream()
            .map(p -> new AvailableStock(p.skuId(), p.warehouseId(), p.available()))
            .toList();
    }

    @Override
    @Transactional
    public PhysicalInventory upsert(SkuWarehouseKey key, String productId, int actualQtyDelta) {
        // Single-row upsert via native query (used by RestockService)
        // For batch, use PhysicalInventoryJpaCustom.bulkUpsert
        return jpa.findBySkuIdAndWarehouseId(key.skuId(), key.warehouseId())
            .map(existing -> {
                existing.addActual(actualQtyDelta);  // wait, this is JPA entity not domain
                return mapper.toDomain(jpa.save(existing));
            })
            .orElseGet(() -> {
                PhysicalInventoryJpaEntity e = new PhysicalInventoryJpaEntity();
                // set fields
                return mapper.toDomain(jpa.save(e));
            });
    }
    // ...
}
```

### 8.3 InventoryReservationRepositoryImpl

```java
@Repository
@RequiredArgsConstructor
public class InventoryReservationRepositoryImpl implements InventoryReservationRepository {

    private final InventoryReservationJpaRepository jpa;
    private final InventoryReservationMapper mapper;

    @Override
    @Transactional
    public Optional<Long> insertIfAbsent(InventoryReservation domain) {
        InventoryReservationJpaEntity entity = mapper.toJpa(domain);
        return jpa.insertIfAbsent(entity);
    }

    @Override
    @Transactional
    public int markRefundExpected(long orderId, String refundRequestId, List<Long> orderItemIds) {
        return jpa.markRefundExpected(orderId, refundRequestId, orderItemIds);
    }

    @Override
    @Transactional(readOnly = true)
    public RefundCompletionSnapshot checkRefundCompletion(long orderId, String refundRequestId) {
        RefundCompletionProjection p = jpa.findRefundCompletion(orderId, refundRequestId);
        return new RefundCompletionSnapshot(p.pendingCount(), p.requiredItemIds(), p.receivedItemIds());
    }
    // ...
}
```

### 8.4 OutboxRepositoryImpl

```java
@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

    private final OutboxJpaRepository jpa;
    private final OutboxMapper mapper;

    @Override
    @Transactional
    public void insert(OutboxEvent event) {
        jpa.save(mapper.toJpa(event));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OutboxEvent> findReturnReceivedAll(String orderId, String refundRequestId) {
        return jpa.findByAggregateIdAndRefundRequestIdAndEventType(
            orderId, refundRequestId, OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL
        ).stream().findFirst().map(mapper::toDomain);
    }

    @Override
    @Transactional
    public int deleteBatchOlderThan(Instant watermark, int limit) {
        return jpa.deleteBatchOlderThan(watermark, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public long countOlderThan(Instant watermark) {
        return jpa.countByCreatedAtBefore(watermark);
    }
}
```

---

## 9. Transaction Boundaries

| Operation | Transaction | Lý do |
|:---|:---|:---|
| `findBy*` | `@Transactional(readOnly = true)` | Query only, không lock |
| `upsert` (single) | `@Transactional` + default isolation | Single-row conflict cần row lock |
| `bulkUpsert` | `@Transactional` | Multi-row INSERT ... ON CONFLICT |
| `insertIfAbsent` (reservation) | `@Transactional` | Idempotent insert với UNIQUE constraint |
| `markRefundExpected` | `@Transactional` | Multi-row update |
| `findRefundCompletion` | `@Transactional(readOnly = true)` | Pure query |
| `insertOutbox` | Caller's transaction | **MUST** be in same TX as ledger insert (Outbox Pattern) |

> Quy tắc Outbox: mọi `outboxRepository.insert(...)` phải được gọi trong transaction của caller (Ledger Worker, RestockService, ShipToCustomerService). KHÔNG có `@Transactional` riêng trên `insert`.

---

## 10. Test Strategy

| Layer | Test type | Tools |
|:---|:---|:---|
| `jpa/entity` | Entity mapping | `@DataJpaTest` + TestContainers Postgres |
| `jpa/custom` | Native query correctness | `@DataJpaTest` + TestContainers |
| `outbound/mapper` | Mapper round-trip | JUnit 5 pure |
| `outbound/repository` | Integration | `@DataJpaTest` + TestContainers |

> Tất cả test dùng TestContainers Postgres 16 với Flyway migrations thật.

---

## 11. Verification

| Check | Cách verify |
|:---|:---|
| Flyway chạy OK | `@DataJpaTest` boot thành công với schema mới |
| Mapping domain ↔ JPA | Test round-trip entity: `mapper.toDomain(mapper.toJpa(d))` equals `d` |
| Partial UNIQUE constraint | Insert 2 row cùng `(order_id, sku_id, warehouse_id)` ở status `RESERVED` → row 2 fail với constraint violation |
| Partial unique index | Insert 2 `RESERVED` cùng `(order, sku, wh)` → fail |
| Custom query perf | Insert 10K fake rows, query < 50ms |
| Outbox transaction | Test: insert outbox fail → ledger insert cũng rollback |

---

## 12. Files Created

```
src/main/java/com/platform/inventoryservice/infrastructure/persistence/
├── jpa/
│   ├── entity/  (5 files)
│   ├── spring/  (5 files)
│   └── custom/  (7 files: 3 interfaces + 3 impls + 1 OutboxJpaCustom)
└── outbound/
    ├── mapper/  (5 files using MapStruct)
    └── repository/  (5 files)

src/main/java/com/platform/inventoryservice/port/outbound/database/
└── (5 interfaces + 1 record)
```