# Promotion Service - Outbox Pattern (Reliable Event Publishing)

**Liên kết nhanh (Related Documents):**
*   [Database Schema (`promotion_outbox`)](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing)
*   [Kafka Events Schema](promotion_events.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   So sánh với: [Cart Outbox Pattern](../cart/cart_outbox_pattern.md)

---

Tài liệu này thiết kế cơ chế **Transactional Outbox Pattern** cho Promotion Service, đảm bảo mọi Event Kafka được phát đi đáng tin cậy (Reliable Publishing) mà không bao giờ mất Event khi Worker crash giữa chừng.

---

## 1. Vấn đề (Dual-Write Problem)

Khi Promotion Service cần ghi dữ liệu xuống PostgreSQL **VÀ ĐỒNG THỜI** publish Event lên Kafka, có hai rủi ro:

*   **Kịch bản 1 (DB OK, Kafka Fail):** Worker ghi DB thành công (VD: `INSERT INTO coupon_usages`) nhưng crash trước khi `producer.send(kafkaEvent)` → Event **mất vĩnh viễn** → Product Service không bao giờ biết Markdown Rule mới, giá sản phẩm bị sai.
*   **Kịch bản 2 (Kafka OK, DB Fail):** Worker publish Kafka trước, rồi ghi DB bị lỗi (VD: Constraint violation) → Event **sai sự thật** → Downstream service xử lý dữ liệu ảo.

**Giải pháp:** Thay vì ghi DB + Publish Kafka trong 2 bước riêng biệt, ta ghi Event vào một **bảng Outbox trong cùng Transaction** với dữ liệu chính. Một Worker riêng biệt (Relay) sẽ đọc Outbox và publish lên Kafka.

---

## 2. Kiến trúc Luồng

```
┌─────────────────────────────────────────────────────────────────┐
│                    PostgreSQL Transaction                       │
│                                                                 │
│  1. INSERT/UPDATE vào bảng chính (promotions, coupon_usages...) │
│  2. INSERT vào bảng promotion_outbox (status = PENDING)         │
│                                                                 │
│  → COMMIT (Cả 2 bước trên đều atomic)                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Outbox Relay Worker (Background Job)               │
│                                                                 │
│  1. Poll: SELECT * FROM promotion_outbox                        │
│           WHERE status IN ('PENDING', 'FAILED')                 │
│           AND (next_retry_at IS NULL OR next_retry_at <= NOW()) │
│           ORDER BY created_at ASC LIMIT 100                     │
│  2. Publish từng Event lên Kafka Topic tương ứng                │
│  3. UPDATE promotion_outbox SET status = 'PUBLISHED'            │
│     WHERE event_id = :event_id                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Bảng `promotion_outbox` (Tham chiếu)

Schema đầy đủ xem tại [promotion_db_design.md](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing):

| Cột | Kiểu | Mô tả |
|:---|:---|:---|
| `event_id` | UUID PK | Khóa chính, ID duy nhất của sự kiện |
| `aggregate_type` | VARCHAR(50) | VD: `PROMOTION`, `MARKDOWN_RULE`, `COUPON_USAGE` |
| `aggregate_id` | VARCHAR(255) | ID entity liên quan |
| `event_type` | VARCHAR(100) | VD: `MARKDOWN_CREATED` — xem [Events Schema](promotion_events.md) |
| `payload` | JSONB | Nội dung Event hoàn chỉnh |
| `status` | VARCHAR(20) | `PENDING` (Hoặc `PUBLISHED`, `FAILED`, `DEAD`) |
| `retry_count` | INT | Số lần thử lại (Mặc định 0) |
| `max_retries` | INT | Số lần thử lại tối đa (Mặc định 5). Quá số này thì `DEAD` |
| `next_retry_at` | TIMESTAMPTZ | Thời điểm thử lại tiếp. Cho phép Exponential Backoff |
| `error_message` | TEXT | Ghi lại lỗi cuối cùng phục vụ debug |
| `created_at` | TIMESTAMPTZ | Thời điểm Event được tạo |
| `published_at` | TIMESTAMP NULL | Thời điểm đã publish thành công |

---

## 4. Outbox Relay Worker (Chi tiết)

### 4.1. Cơ chế Polling
*   **Interval:** Relay Worker chạy poll mỗi **500ms** (có thể điều chỉnh).
*   **Batch Size:** Mỗi lần poll lấy tối đa **100 records** thỏa điều kiện:
    ```sql
    SELECT * FROM promotion_outbox 
    WHERE status IN ('PENDING', 'FAILED') 
      AND (next_retry_at IS NULL OR next_retry_at <= NOW()) 
    ORDER BY created_at ASC LIMIT 100;
    ```
*   **Concurrency:** Chỉ chạy **1 instance duy nhất** (Leader Election bằng `pg_advisory_lock` hoặc Redis Lock) để tránh duplicate publishing.

### 4.2. Quy trình Publish (Kèm Exponential Backoff)
```text
FOR mỗi outbox_record IN batch:
    1. Map event_type → Kafka Topic (Xem bảng Topic Mapping tại promotion_events.md)
    2. Lấy aggregate_id làm Kafka Partition Key
    3. kafka.producer.send(topic, partitionKey, payload)
    4. Nếu send() thành công:
         UPDATE promotion_outbox SET status = 'PUBLISHED', error_message = NULL WHERE event_id = :event_id
    5. Nếu send() thất bại:
         Tăng retry_count = retry_count + 1
         Cập nhật error_message với chi tiết lỗi (VD: "Kafka broker unreachable")
         NẾU retry_count >= max_retries:
             UPDATE promotion_outbox SET status = 'DEAD', error_message = :error_message WHERE event_id = :event_id
         NGƯỢC LẠI:
             Tính toán next_retry_at = NOW() + (5s * 3^(retry_count - 1))  -- Exponential Backoff: lần 1 = 5s, lần 2 = 15s, lần 3 = 45s...
             UPDATE promotion_outbox 
             SET status = 'FAILED', next_retry_at = :next_retry_at, error_message = :error_message, retry_count = :retry_count
             WHERE event_id = :event_id
```

### 4.3. Deduplication (Chống trùng lặp ở Downstream)
Do Relay Worker có thể crash sau khi Kafka nhận message nhưng trước khi UPDATE status → lần poll sau nó sẽ gửi lại message đó → **At-Least-Once Delivery**.

Các Consumer downstream phải tự bảo vệ bằng:
*   **Redis `SETNX` (Vòng 1):** Xem [Idempotency Kép](redis_caching_design.md#script-3-xác-nhận-mua-thành-công-commit-redeem).
*   **PostgreSQL Unique Constraint (Vòng 2):** Bảng [`promotion_event_idempotency`](promotion_db_design.md#17-promotion_event_idempotency-chống-lặp-message-kafka).

### 4.4. Dọn rác Outbox (Garbage Collection)
Background Job chạy hàng ngày xóa các records đã `PUBLISHED` quá **7 ngày**:
```sql
DELETE FROM promotion_outbox 
WHERE status = 'PUBLISHED' AND published_at < NOW() - INTERVAL '7 days';
```

---

## 5. So sánh với Cart Service Outbox

| Tiêu chí | Cart Service | Promotion Service |
|:---|:---|:---|
| **Outbox Storage** | Redis Streams (`XADD`) | PostgreSQL Table (`promotion_outbox`) |
| **Relay Mechanism** | Stream Consumer Group | Polling Worker |
| **Tại sao khác?** | Cart là Redis-First, DB là Cold Store phụ | Promotion là DB-First, Redis chỉ là Cache |
| **Đảm bảo thứ tự** | Per-stream ordering | `created_at ASC` ordering trong bảng |
| **Dedup** | Stream ID tự nhiên | `event_id` + Idempotency bảng DB |

> [!NOTE]
> Cart Service sử dụng Redis Streams làm Outbox vì nó đã chọn kiến trúc **Redis-First** (Redis là Primary Store). Promotion Service chọn **PostgreSQL Outbox** vì dữ liệu lõi (Budget, Ledger, Idempotency) đều nằm ở DB — ghi Outbox cùng Transaction đảm bảo tính nhất quán tuyệt đối.

---

## 6. Các Event Cần Đi Qua Outbox

Không phải mọi hành động đều cần Outbox. Dưới đây là phân loại:

| Hành động | Cần Outbox? | Lý do |
|:---|:---:|:---|
| Admin tạo/sửa `markdown_rules` | ✅ | Product Service cần cập nhật `promotional_price` |
| Admin tạo/sửa `promotions` | ✅ | Cache invalidation + Analytics |
| Worker xử lý `COUPON_REDEEMED` | ✅ | Analytics, Seller Settlement cần biết |
| Worker xử lý `COUPON_CANCELLED` | ✅ | Analytics |
| Worker xử lý `COUPON_REFUNDED` | ✅ | Seller Settlement cần hoàn chi phí |
| User claim mã Flash Sale | ❌ | Event claim đã được Backend produce trực tiếp sau Lua Script (không qua DB trước) |
| Lua Script Budget Reserve | ❌ | Chỉ thao tác Redis, không ghi DB tại thời điểm Reserve |
