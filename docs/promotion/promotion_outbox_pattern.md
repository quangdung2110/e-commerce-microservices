
# Promotion Service - Outbox Pattern (CDC-based Reliable Event Publishing)

**Liên kết nhanh (Related Documents):**
*   [Database Schema (`promotion_outbox`)](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing)
*   [Kafka Events Schema](promotion_events.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   So sánh với: [Cart Outbox Pattern](../cart/cart_outbox_pattern.md)

---

Tài liệu này thiết kế cơ chế **Transactional Outbox Pattern + Debezium CDC** cho Promotion Service, đảm bảo Event được phát ra Kafka theo mô hình At-Least-Once mà không cần polling trạng thái trong bảng outbox.

---

## 1. Vấn đề (Dual-Write Problem)

Khi Promotion Service cần ghi dữ liệu xuống PostgreSQL **VÀ ĐỒNG THỜI** publish Event lên Kafka, có hai rủi ro:

*   **Kịch bản 1 (DB OK, Kafka Fail):** Worker ghi DB thành công (VD: `INSERT INTO coupon_usages`) nhưng crash trước khi `producer.send(kafkaEvent)` -> Event **mất vĩnh viễn** -> Product Service không bao giờ biết Markdown Rule mới, giá sản phẩm bị sai.
*   **Kịch bản 2 (Kafka OK, DB Fail):** Worker publish Kafka trước, rồi ghi DB bị lỗi (VD: Constraint violation) -> Event **sai sự thật** -> Downstream service xử lý dữ liệu ảo.

**Giải pháp:** Ghi Event vào một **bảng Outbox trong cùng Transaction** với dữ liệu chính. Sau đó Debezium CDC đọc WAL và đẩy bản ghi outbox lên Kafka.

---

## 2. Kiến trúc Luồng

```
┌─────────────────────────────────────────────────────────────────┐
│                    PostgreSQL Transaction                       │
│                                                                 │
│  1. INSERT/UPDATE vào bảng chính (promotions, coupon_usages...) │
│  2. INSERT vào bảng promotion_outbox (append-only)              │
│                                                                 │
│  -> COMMIT (Cả 2 bước trên đều atomic)                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│            Debezium CDC Connector (Kafka Connect)              │
│                                                                 │
│  1. Đọc WAL qua replication slot                                │
│  2. Lọc bản ghi INSERT từ bảng promotion_outbox                 │
│  3. Route event_type -> Kafka topic đích                        │
│  4. Dùng partition_key làm Kafka message key                    │
│  5. Commit connector offsets                                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Bảng `promotion_outbox` (Tham chiếu)

Schema đầy đủ xem tại [promotion_db_design.md](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing):

| Cột | Kiểu | Mô tả |
|:---|:---|:---|
| `event_id` | UUID PK | Khóa chính, ID duy nhất của sự kiện |
| `schema_version` | INT | Version schema event top-level |
| `aggregate_type` | VARCHAR(50) | VD: `Promotion`, `MarkdownRule`, `CouponUsage` |
| `aggregate_id` | VARCHAR(255) | ID entity liên quan |
| `event_type` | VARCHAR(100) | VD: `MARKDOWN_CREATED` - xem [Events Schema](promotion_events.md) |
| `partition_key` | VARCHAR(255) | Kafka key được chốt ngay lúc ghi outbox |
| `payload` | JSONB | Nội dung Event hoàn chỉnh |
| `created_at` | TIMESTAMPTZ | Thời điểm Event được tạo |

> [!IMPORTANT]
> Với mô hình CDC, bảng outbox **không chứa** các cột polling-specific như `status`, `retry_count`, `max_retries`, `next_retry_at`, `published_at`, `error_message`.
> Retry/DLQ được quản trị bởi Kafka Connect runtime.

---

## 4. Outbox CDC Connector (Chi tiết)

### 4.1. Cơ chế CDC
*   Connector chạy dưới Kafka Connect Source, đọc trực tiếp WAL PostgreSQL bằng replication slot.
*   Chỉ theo dõi `INSERT` trên `promotion_outbox` (append-only).
*   Tách route topic theo `event_type`:
    *   `MARKDOWN_*` -> `t.promotion.markdown.events`
    *   `COUPON_*` -> `t.promotion.coupon.events`
    *   `BUDGET_EXHAUSTED` -> `t.promotion.budget.events`
*   Kafka message key phải lấy từ cột `partition_key`, không suy luận lại từ JSON payload.

### 4.2. Trách nhiệm Retry và DLQ
Retry, backoff, DLQ và error handling thuộc Kafka Connect:
*   Retry connector task (transient broker/network errors).
*   Dead Letter Topic cho record lỗi mapping/serialization.
*   Alerting theo connector lag, task failed state, DLQ growth.

> [!NOTE]
> Thứ tự chỉ được đảm bảo nghiêm ngặt theo cùng `partition_key` (ví dụ cùng `order_id` hoặc cùng `rule_id`). Hệ thống không cam kết global total order giữa các key khác nhau.
>
> `COUPON_CLAIMED` không đi qua outbox CDC này. Event claim thuộc luồng direct-produce (partition key = `claim_id`, `business_id = claim_id`) và được rescue bởi Pending Claims Sweeper khi gặp crash window.
> Mỗi lần republish phải tạo `event_id` mới, giữ nguyên `claim_id`, gắn `payload.production_source = SWEEPER_RESCUE`.
> Downstream bắt buộc dedup hai tầng: `event_id` (delivery-level) + `claim_id` (business-level).
>
> `COUPON_RESERVED` chỉ là optional telemetry event (nếu bật), không thuộc default outbox contract liên service.

### 4.3. Deduplication (Chống trùng lặp ở Downstream)
CDC pipeline vẫn là **At-Least-Once Delivery**: khi connector restart/rebalance có thể phát lại một phần bản ghi trước khi offset commit ổn định.

Các Consumer downstream phải tự bảo vệ bằng:
*   **Redis `SETNX` (Vòng 1):** Xem [Idempotency Kép](redis_caching_design.md#script-3-xác-nhận-mua-thành-công-commit-redeem).
*   **PostgreSQL Unique Constraint (Vòng 2):** Bảng [`promotion_event_idempotency`](promotion_db_design.md#17-promotion_event_idempotency-chống-lặp-message-kafka).

### 4.4. Dọn rác Outbox (Garbage Collection)
Background Job chạy hàng ngày xóa bản ghi outbox quá hạn theo `created_at`, nhưng phải chặn bằng safe watermark của connector:

```sql
DELETE FROM promotion_outbox
WHERE created_at < NOW() - INTERVAL '7 days'
  AND created_at < :connector_safe_watermark;
```

`connector_safe_watermark` là mốc thời gian đã được connector consume an toàn (từ offsets/monitoring), có thêm safety window để tránh xóa sớm record chưa được ship.

---

## 5. So sánh với Cart Service Outbox

| Tiêu chí | Cart Service | Promotion Service |
|:---|:---|:---|
| **Outbox Storage** | Redis Streams (`XADD`) | PostgreSQL Table (`promotion_outbox`) |
| **Relay Mechanism** | Stream Consumer Group | Debezium CDC (Kafka Connect Source) |
| **Tại sao khác?** | Cart là Redis-First, DB là Cold Store phụ | Promotion là DB-First, Redis chỉ là Cache |
| **Đảm bảo thứ tự** | Per-stream ordering | Strict ordering theo `partition_key` |
| **Dedup** | Stream ID tự nhiên | `event_id` + Idempotency bảng DB |

> [!NOTE]
> Cart Service dùng Redis Streams vì kiến trúc Redis-First. Promotion Service dùng PostgreSQL Outbox + CDC vì dữ liệu lõi (Budget, Ledger, Idempotency) nằm ở DB và cần commit cùng transaction nghiệp vụ.

---

## 6. Các Event Cần Đi Qua Outbox

Không phải mọi hành động đều cần Outbox. Dưới đây là phân loại:

| Hành động | Cần Outbox? | Lý do |
|:---|:---:|:---|
| Admin tạo/sửa `markdown_rules` và Scheduler kết thúc rule (`EXPIRED`) | ✅ | Product Service cần cập nhật `promotional_price` khi rule bắt đầu/thay đổi/kết thúc |
| Admin tạo/sửa `promotions` | ❌ (mặc định) | Mặc định chỉ dùng Redis Pub/Sub `PROMO_EVICT`; hiện không có contract Kafka liên service chính thức cho nhóm event này |
| Worker xử lý `COUPON_REDEEMED` | ✅ | Analytics, admin Settlement cần biết |
| Worker xử lý `COUPON_CANCELLED` | ✅ | Analytics |
| Worker xử lý `COUPON_REFUNDED` | ✅ | admin Settlement cần hoàn chi phí |
| User claim mã Flash Sale | ❌ | Event claim được produce trực tiếp sau Lua Script (không qua DB trước), rescue bằng Sweeper; consumer dedup theo `event_id` + `claim_id` |
| Lua Script Budget Reserve | ❌ | Chỉ thao tác Redis, không ghi DB tại thời điểm Reserve (`COUPON_RESERVED` chỉ bật khi cần telemetry nội bộ) |
