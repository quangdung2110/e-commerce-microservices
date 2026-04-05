# Order Service - CQRS Elasticsearch Design

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workflows](order_workflow.md)
*   [Workers](order_workers.md)
*   [Temporal Saga Design](temporal_saga_design.md)

---

Tài liệu này mô tả read-model Elasticsearch cho Order Service theo CQRS.

## 1. Mục tiêu

- Tách luồng đọc truy vấn đơn hàng khỏi PostgreSQL write-model.
- Hỗ trợ truy vấn lịch sử đơn hàng theo user/seller nhanh ở tải cao.
- Giữ eventual consistency rõ SLO với dữ liệu source.

## 2. Nguồn dữ liệu

- Source of truth: PostgreSQL Order Service.
- Projection input: Kafka `t.order.events`.
- Consumer projection: `order-cqrs-projector`.

## 3. Index đề xuất

| Index | Alias | Mục đích |
|---|---|---|
| `orders_v1` | `orders_read` | Danh sách đơn theo user/seller |
| `order_parcels_v1` | `order_parcels_read` | Truy vấn tracking parcel |

## 4. Guard đồng bộ

- Idempotency theo `event_id`.
- Out-of-order guard theo `order.version`/`state_version` trong payload.
- Chỉ commit offset khi batch projection thành công hoặc đã park DLQ.

## 5. SLA / SLO

- Projection freshness mục tiêu: p95 <= 5s, p99 <= 15s.
- Alert khi consumer lag vượt ngưỡng vận hành.

## 6. Reindex không downtime

- Dùng versioned index (`orders_vN`) + alias swap atomically.
- Backfill theo snapshot + catch-up watermark trước cutover.
