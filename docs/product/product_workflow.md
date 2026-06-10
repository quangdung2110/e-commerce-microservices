# Product Service - Trình Tự Nghiệp Vụ cho Background Workers

**Liên kết nhanh (Related Documents):**
*   [API Endpoints](product_endpoints.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---

Tài liệu này mô tả chi tiết trình tự các bước thực thi cho **Background Workers** — luồng xử lý ngầm chạy bất đồng bộ bên trong Product Service. Các API endpoints được mô tả chi tiết tại [API Endpoints](product_endpoints.md).



## 1. Workflow: Xử Lý Markdown Event từ Promotion Service
**Trigger:** Kafka Event `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` từ Topic `t.promotion.markdown.events`.
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#4-markdown-price-calculator-worker).

Đây là luồng **không đi qua API** mà chạy hoàn toàn ngầm (Background). Xem chi tiết kiến trúc tại [Promotion DB Design - Zero-Latency](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).

**Quy trình chuẩn kiện (Workflow):**
1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-markdown-group`) nhận sự kiện Cập Nhật.
   - Parse payload phù hợp cho allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`; event ngoài allowlist phải skip + metric + DLQ.
   - Chuẩn hóa `rule_id` về string ngay tại consumer boundary trước khi truy vấn/ghi DB.
   - **Start-time gate cho `MARKDOWN_CREATED`:** Nếu `now < payload.start_time`, worker persist job vào Redis ZSET `product:markdown:pending` (score = `start_time_epoch_ms`, member = `{rule_id}:{scope_type}:{ref_id}`). Redis ZSET này tự động sắp xếp job theo thời gian. Một Scheduled Worker độc lập (chạy mỗi 1-5 phút) sẽ drain ZSET này: `ZRANGEBYSCORE product:markdown:pending -inf {now_epoch_ms}` để lấy các job đã đến hạn, gọi xử lý, rồi `ZREM` khỏi ZSET. Đảm bảo retry/rebalance không làm mất pending job.
2. **[PostgreSQL] - Trích xuất SKU bị ảnh hưởng:**
   - Nếu `scope_type = CATEGORY`: `SELECT ps.sku_id, ps.product_id FROM product_skus ps JOIN products p ON ps.product_id = p.product_id WHERE p.category_id = :ref_id AND p.status = 'ACTIVE' AND ps.status = 'ACTIVE'`.
   - Nếu `scope_type = PRODUCT`: Query theo `product_id = ref_id` để lấy tập `sku_id` cần xử lý.
   - Bước này chỉ để xác định phạm vi bị ảnh hưởng. Giá `original_price` dùng cho phép tính markdown phải lấy lại từ các row SKU đã lock trong transaction per-product ở bước 4.
3. **[RAM] - Tính toán Batch giá khuyến mãi:**
   - Dữ liệu query ở bước 2 là danh sách phẳng có thể lẫn SKU của nhiều Product, vì vậy Worker phải group theo `product_id` ở RAM (map hoặc iterator grouping).
   - Với tập lớn, ưu tiên streaming/chunk theo `ORDER BY product_id ASC, sku_id ASC` để không phình bộ nhớ.
    - Với mỗi SKU, công thức chuẩn:
       - `PERCENTAGE`: `discount_amount = MIN(original_price * discount_value / 100, max_discount_amount)` (nếu có cap), `promotional_price = MAX(0, ROUND(original_price - discount_amount, 2))`.
       - `FIXED_AMOUNT`: `promotional_price = MAX(0, ROUND(original_price - discount_value, 2))`.
    - **`best_price`** = Giá thấp nhất đã được tính sẵn: `best_price = LEAST(original_price, COALESCE(promotional_price, original_price))`. Đây là giá hiển thị cho end-user (giá gốc nếu không có khuyến mãi, giá khuyến mãi nếu có). Downstream (Search, Cart, Checkout) chỉ đọc `best_price`, không tự tính lại.
4. **[PostgreSQL] - Ghi dữ liệu & Tránh Deadlock Liên Bảng (Concurrency Guard):**
   - **Cảnh báo Race Condition:** Tuyệt đối không tung một lệnh `UPDATE product_skus` khổng lồ theo diện rộng (Category). Nếu đụng độ với luồng admin Update API (Lock `products` -> `product_skus`), sẽ lập tức gây Deadlock chéo.
   - Worker BẮT BUỘC phải chia theo nhóm `product_id` và ORDER BY `product_id` ASC. Với mỗi khối `product_id`, mở `@Transactional` độc lập:
   - **Chống va chạm đa instance worker:** ngay đầu transaction lock Product cha bằng `FOR NO KEY UPDATE` với `lock_timeout` ngắn. Nếu timeout thì `nack/requeue` theo retry policy, không được skip im lặng.
       - Khóa cha: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE`.
       - Khóa con + lấy giá live: `SELECT sku_id, original_price FROM product_skus WHERE sku_id IN (...) AND product_id = :product_id FOR UPDATE`.
       - Tính giá theo tập row đã lock, sau đó batch `UPDATE product_skus SET promotional_price = :calc, updated_at = NOW() WHERE product_id = :product_id AND sku_id IN (...)`.
       - Tracking: batch upsert `product_markdown_applied` (tracking rule → SKU mapping).
      - Outbox: batch insert `product_outbox_events` cho các SKU bị ảnh hưởng, chứa [`SKU_PRICE_UPDATED`](product_events.md#33-SKU_PRICE_UPDATED); payload phải dùng `updated_at` timestamp để downstream guard out-of-order.
     - COMMIT Transaction để nhả cọc cho Product kế tiếp. Mất thêm milisecond vòng lặp nhưng an toàn hệ thống tuyệt đối 100%.
5. **[Worker] - Invalidate Cache (Soft Invalidation - Ownership tách bạch):**
   - Sau mỗi lần commit của từng Product, Markdown Worker chỉ quản lý key phụ markdown theo batch Redis pipeline.
   - Markdown Worker **không** ghi đè trực tiếp `product:sku:{id}` để tránh double-write với luồng Kafka invalidation.
    - Invalidation Consumer (`product-event-handler-group`) sẽ self-consume `SKU_PRICE_UPDATED` từ outbox để refresh `product:sku:{id}` theo một đường ghi bất đồng bộ duy nhất.
   - Để tránh write amplification, Invalidation Consumer phải dedupe theo `product_id` trong từng poll-batch.
   - Trước khi overwrite `product:sku:{id}`, áp dụng stale guard theo `updated_at`: chỉ ghi khi event mới hơn cache hiện tại; event bằng/cũ hơn phải bỏ qua.
   - Với `MARKDOWN_ENDED` do tắt thủ công, đưa các lệnh `DEL product:markdown:active:{sku_id}` vào cùng pipeline batch để đồng bộ tức thời.
6. **[Background] - Phát Event CQRS:**
   - Debezium CDC Connector forward `SKU_PRICE_UPDATED` từ outbox sang Kafka → Search Service cập nhật giá hiển thị trên Elasticsearch.

> **Lưu ý khi `MARKDOWN_UPDATED` / `MARKDOWN_ENDED`:** Luồng tương tự áp dụng cho việc lấy danh sách SKU từ bảng `product_markdown_applied`. Đặc thù với UPDATE, Worker BẮT BUỘC query lại `original_price` live từ bảng `product_skus`, KHÔNG DÙNG snapshot rác cũ; tiếp theo tính toán sửa lại DB rồi tung outbox bình thường. Reset Null Price nếu là event ENDED.
>
> **No-op idempotent cho ENDED:** nếu không còn bản ghi mapping theo `rule_id` thì ACK và kết thúc, không phát outbox rỗng.

## 2. Workflow: Xử Lý Inventory Sync Event (Two-Phase Lock-Free)

> **Kiến trúc mới:** Luồng này đã được tối ưu hóa với **Two-Phase Lock-Free Architecture**. Xem chi tiết tại [Inventory Sync Optimization](inventory_sync_optimization.md) và [Inventory Sync Worker](product_workers.md#5-inventory-sync-worker-two-phase-lock-free-architecture).

**Trigger:** Kafka Event [`INVENTORY_QUANTITY_CHANGED`](product_events.md#44-inventory_quantity_changed-từ-inventory-service) từ Topic `t.inventory.events`.

**Quy trình chuẩn kiện (Workflow):**

### Phase 1: Fast Path (Lock-Free, < 5ms)

1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-inventory-fast-sync-group`) nhận `INVENTORY_QUANTITY_CHANGED`.
   - Parse payload bắt buộc: `product_id`, `sku_id`, `available_quantity`, `event_timestamp`.
2. **[Lua Script] - Atomic Compare-and-Swap trên Redis:**
   - Staleness guard: chỉ apply nếu `event_timestamp > cached.last_inventory_sync_at`.
   - Atomic write `display_quantity`, `sku_status`, `last_inventory_sync_at`, `is_available`.
   - Preserve existing non-inventory fields (price, name, image...).
3. **[Redis Pipelining] - Ghi Cache + Pending Sync:**
   - `JSON.SET product:sku:{sku_id}` — immediate cache warm.
   - `ZADD product:inventory:pending {event_timestamp} {sku_id}:{event_timestamp}` — đăng ký pending sync.
4. **[Kafka ACK]** — offset commit ngay lập tức, không chờ DB.

### Phase 2: Slow Path (Async DB Sync, 500ms - 2s)

5. **[Scheduled Worker] - Drain Pending Sync:**
   - Chạy mỗi 500ms - 1s.
   - `ZRANGE` Redis ZSET với pagination (100-500 items mỗi run).
   - Group by `product_id` trong RAM.
6. **[PostgreSQL] - Batch Update với Optimistic Locking:**
   - Mỗi product: 1 transaction, lock Product cha (1 row lock cho cả batch SKU).
   - Batch update `product_skus`: `display_quantity`, `sku_status`, `last_inventory_sync_at`.
   - FSM triggers: `AUTO_HIDE_OUT_OF_STOCK` hoặc `AUTO_REACTIVATE_IN_STOCK` nếu SKU status thay đổi.
   - Batch insert `product_outbox_events` (`PRODUCT_STATUS_CHANGED`) nếu có state change.
7. **[Redis] - Ack Pending Sync:**
   - `ZREM` khỏi Redis ZSET sau DB commit.
   - Cache `product:sku:{sku_id}` được refresh bởi **Event Handler** khi self-consume `PRODUCT_STATUS_CHANGED`.

### Đảm Bảo Consistency

- **Eventual Consistency**: Cache immediate → DB 500ms-2s → Search 2-5s.
- **Staleness Guard đa tầng**: Layer 1 (Lua CAS), Layer 2 (Phase 2 DB), Layer 3 (Outbox event).
- **Self-Healing**: Nếu Phase 1 tạm dừng, Phase 2 vẫn drain pending → DB eventually consistent.
