# Module 14: Outbox Cleanup Cron Job

> **Depends on:** M05 (Repository — OutboxRepository)
> **Depended by:** (none)

---

## 1. Goal

Triển khai scheduled job xóa các row `inventory_outbox` đã được Debezium CDC publish thành công:

- Cron `0 0 2 * * *` (02:00 UTC mỗi ngày).
- Watermark = `NOW() - 24h` (an toàn cho Debezium CDC lag < 1 phút).
- Batch delete với LIMIT 10000 / lần để tránh lock bảng.
- Monitoring + alerting khi pending quá nhiều.

> Tham chiếu: `inventory_workers.md` §4.

---

## 2. Package Layout

```
com.platform.inventoryservice.scheduled/
└── OutboxCleanupJob.java
```

---

## 3. OutboxCleanupJob

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupJob {

    private final OutboxRepository outboxRepository;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;

    /**
     * Cleanup job chạy đúng 1 lần / ngày lúc 02:00 UTC.
     * Xóa row đã được Debezium publish (watermark = NOW - 24h).
     */
    @Scheduled(cron = "${inventory.cleanup.cron:0 0 2 * * *}", zone = "UTC")
    public void cleanup() {
        long start = System.currentTimeMillis();
        Instant watermark = Instant.now()
            .minus(Duration.ofHours(props.getCleanup().getWatermarkHours()));
        int batchSize = props.getCleanup().getBatchSize();

        log.info("Outbox cleanup started: watermark={} batchSize={}", watermark, batchSize);

        int totalDeleted = 0;
        int batchDeleted;
        int iterations = 0;

        do {
            batchDeleted = outboxRepository.deleteBatchOlderThan(watermark, batchSize);
            totalDeleted += batchDeleted;
            iterations++;
            log.debug("Cleanup batch {}: deleted {} rows (cumulative {})",
                      iterations, batchDeleted, totalDeleted);
        } while (batchDeleted == batchSize && iterations < 1000);   // safety cap

        long durationMs = System.currentTimeMillis() - start;
        metrics.recordOutboxCleanup(totalDeleted, durationMs);

        log.info("Outbox cleanup finished: deleted {} rows in {} ms ({} iterations)",
                 totalDeleted, durationMs, iterations);
    }

    /**
     * Health check — gọi mỗi 5 phút để monitor pending count.
     * Không xóa, chỉ ghi metric + log warn nếu quá nhiều.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void monitorPending() {
        Instant watermark = Instant.now()
            .minus(Duration.ofHours(props.getCleanup().getWatermarkHours()));
        long pendingCount = outboxRepository.countOlderThan(watermark);
        metrics.recordOutboxPending(pendingCount);

        if (pendingCount > 1_000_000) {
            log.warn("Outbox pending count HIGH: {} rows older than watermark {}",
                     pendingCount, watermark);
        } else if (pendingCount > 100_000) {
            log.info("Outbox pending count elevated: {} rows older than watermark {}",
                     pendingCount, watermark);
        }
    }
}
```

---

## 4. Configuration

```yaml
inventory:
  cleanup:
    cron: "0 0 2 * * *"        # 02:00 UTC daily
    watermark-hours: 24         # Conservative: CDC lag < 1 min, watermark 24h safe
    batch-size: 10000           # DELETE per iteration
```

> Override qua env var:
> ```bash
> INVENTORY_CLEANUP_CRON="0 0 2 * * *"
> INVENTORY_CLEANUP_WATERMARK_HOURS=24
> INVENTORY_CLEANUP_BATCH_SIZE=10000
> ```

---

## 5. SQL Strategy

### 5.1 Batch DELETE (từ M05)

```sql
DELETE FROM inventory_outbox
WHERE event_id IN (
  SELECT event_id FROM inventory_outbox
  WHERE created_at < :watermark
  ORDER BY created_at
  LIMIT :limit
);
```

> Subquery với LIMIT tránh lock toàn bảng khi bảng lớn.

### 5.2 Index Usage

Query dùng:
- `idx_inventory_outbox_created_at` cho WHERE `created_at < :watermark`
- `inventory_outbox_pkey` (event_id) cho DELETE

EXPLAIN ANALYZE phải show `Index Scan using idx_inventory_outbox_created_at`.

### 5.3 Concurrency

- Chỉ 1 instance chạy cleanup tại 1 thời điểm (cron 1 lần / ngày + cluster leader election nếu multi-instance).
- Nếu nhiều instance cùng chạy → DELETE batch có thể overlap, không gây sai (DELETE là idempotent).

### 5.4 N+1 Iteration Safety

```java
do {
    batchDeleted = outboxRepository.deleteBatchOlderThan(watermark, batchSize);
    totalDeleted += batchDeleted;
    iterations++;
} while (batchDeleted == batchSize && iterations < 1000);
```

> Safety: tối đa 1000 iterations × 10000 = 10M rows. Trong prod, nếu cleanup chạm cap → log alert (tăng batch size hoặc chạy lại).

---

## 6. Edge Cases

| Scenario | Hành vi |
|:---|:---|
| Bảng rỗng | DELETE trả 0; loop exit ngay |
| Watermark chưa đến | DELETE trả 0 (no row match); loop exit |
| CDC lag > 24h | Row chưa publish vẫn bị DELETE (data loss). |
| → Mitigation | `watermark-hours` configurable; alert nếu `cdc_lag > watermark_hours` |
| CDC lag > 7 ngày | Hard cap ngăn cleanup xóa row mới hơn 7 ngày (xem §7) |

---

## 7. Hard Cap (Conservative Variant)

> Tùy chọn: nếu ngại race với CDC lag bất thường, dùng TTL 7 ngày thay vì watermark:

```java
private static final Duration HARD_CAP = Duration.ofDays(7);

private Instant computeWatermark() {
    Instant now = Instant.now();
    Instant watermarkByHours = now.minus(Duration.ofHours(props.getCleanup().getWatermarkHours()));
    Instant hardCap = now.minus(HARD_CAP);
    return watermarkByHours.isBefore(hardCap) ? hardCap : watermarkByHours;
}
```

> Cap tại 7 ngày để giữ retention tối thiểu.

---

## 8. Multi-Instance Coordination

Nếu deploy nhiều instance Inventory Service (HA), cleanup có thể chạy đồng thời. Giải pháp: dùng **database advisory lock** để chỉ một instance thực thi cleanup tại một thời điểm:

```java
@Scheduled(cron = "0 0 2 * * *")
public void cleanup() {
    if (!tryAcquireCleanupLock()) {
        log.info("Cleanup lock held by another instance, skipping");
        return;
    }
    try {
        doCleanup();
    } finally {
        releaseCleanupLock();
    }
}

private boolean tryAcquireCleanupLock() {
    // pg_try_advisory_lock(42) — return boolean
}
```

**Option C**: ShedLock library (Spring integration):

```java
@Scheduled(cron = "0 0 2 * * *")
@SchedulerLock(name = "outbox-cleanup", lockAtMostFor = "30m")
public void cleanup() { ... }
```

> Khuyến nghị: **Option C** (ShedLock) cho HA deployment.

---

## 9. Metrics

| Metric | Type | Labels | Mục đích |
|:---|:---|:---|:---|
| `inventory_outbox_cleanup_duration_seconds` | Timer | — | Cleanup job latency |
| `inventory_outbox_cleanup_deleted_total` | Counter (sum) | — | Total rows deleted per run |
| `inventory_outbox_pending` | Gauge | — | Current rows older than watermark |
| `inventory_outbox_oldest_age_seconds` | Gauge | — | Oldest row's age (alert nếu > 24h + CDC lag) |

---

## 10. Testing

| Test | Mục đích |
|:---|:---|
| Cleanup xóa row cũ | Insert 5 row `created_at = NOW() - 25h` → cleanup xóa 5 |
| Cleanup giữ row mới | Insert 5 row `created_at = NOW() - 1h` → cleanup xóa 0 |
| Batch limit | Insert 25000 row cũ → cleanup xóa 3 batch × 10000 + 5000 |
| Safety cap | Mock `deleteBatchOlderThan` always return 10000 → cleanup dừng sau 1000 iterations |
| Concurrent run | 2 instances cùng cleanup → 1 chạy, 1 skip (ShedLock) |

---

## 11. Verification

| Check | Cách verify |
|:---|:---|
| Cron registered | Log "Outbox cleanup started" vào 02:00 UTC |
| Rows deleted | `SELECT COUNT(*) FROM inventory_outbox WHERE created_at < NOW() - INTERVAL '24 hours'` |
| Pending count metric | `curl /actuator/prometheus \| grep inventory_outbox_pending` |
| No data loss | Confirm CDC read all rows trước khi cleanup bằng offset tracking |

---

## 12. Files Created

```
src/main/java/com/platform/inventoryservice/scheduled/
└── OutboxCleanupJob.java
```