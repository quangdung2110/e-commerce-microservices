# Product Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
*   [API Endpoints](product_endpoints.md)
*   [Background Workers Workflows](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Inventory Sync Optimization](inventory_sync_optimization.md)

---

Tài liệu này tổng hợp các khuyến nghị kiến trúc cốt lõi để Product Service vận hành ổn định ở quy mô lớn và tránh lệch dữ liệu liên service.

## 1. Boundary & Ownership

1. Product Service là **source of truth** cho Product/SKU/Price snapshot.
2. Search Service chỉ là read model, không tự tính giá và không tự sửa business state.
3. Service khác (Cart, Checkout, Order, Promotion) chỉ đọc dữ liệu sản phẩm qua gRPC `GetProductSku`/`GetBatchProductSkus`; các consumer event (Promotion/Notification/Search) chỉ dùng event cho phản ứng nghiệp vụ, không thay thế point-read contract.

## 2. Eventing & Ordering

1. Dùng Debezium CDC + outbox table để phát event, không polling thủ công theo trạng thái hàng.
2. Tách topic:
   - `t.product.events` cho product/sku events.
   - `t.category.events` cho category events.
3. `PRODUCT_STATUS_CHANGED` và `SKU_UPDATED` (thay thế cũ: `SKU_PRICE_UPDATED` + `SKU_STATUS_CHANGED`). **Chỉ dùng `SKU_UPDATED` cho mọi thay đổi cấp SKU** — event gộp bao gồm cả price change và status change thông qua `changed_fields`. **Search Service dùng DUY NHẤT `updated_at` timestamp làm guard ordering**. Các event khác (PRODUCT_CREATED, PRODUCT_UPDATED...) dùng `updated_at` timestamp.
4. `product-event-handler-group` chỉ xử lý `CATEGORY_DELETED` (Write Model: update PostgreSQL `product.status = HIDDEN`), `SKU_CREATED` (cache refresh từ endpoint Thêm SKU), và `SKU_UPDATED` (cache refresh cho mọi thay đổi SKU: giá từ Markdown Worker, trạng thái từ Inventory Phase 2, thay đổi từ Admin API). **Event Handler là writer DUY NHẤT cho `product:sku:{sku_id}` từ SKU events.** Cache refresh cho PRODUCT_STATUS_CHANGED, PRODUCT_DELETED, PRODUCT_UPDATED đã được xử lý In-band trong API.
5. Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`, mỗi phần tử `skus[]` bắt buộc có `best_price` và `has_promotion` để Search phản chiếu giá trực tiếp, không tự tính lại business pricing.
6. `PRODUCT_UPDATED` phải ưu tiên payload full snapshot; nếu payload thiếu field bắt buộc thì invalidation consumer fallback đọc snapshot PostgreSQL. Không trộn payload + DB trong cùng một event.
7. Event Handler Worker phải có execution contract tường minh: commit offset sau success/DLQ, retry có giới hạn cho lỗi transient, pause/resume partition khi dependency suy giảm.
8. **Inventory Sync (Two-Phase Lock-Free)**: Event `INVENTORY_QUANTITY_CHANGED` được xử lý qua Phase 1 (Lock-Free: Kafka → Redis Lua CAS) và Phase 2 (Async DB Sync: Redis ZSET → PostgreSQL → phát `SKU_UPDATED` với `changed_fields` chứa `sku_status`/`product_status`). Xem [Inventory Sync Optimization](inventory_sync_optimization.md).
9. Cache key `product:sku:{sku_id}` bắt buộc lưu `price_updated_at` (ISO 8601 timestamp) để Event Handler guard khi refresh từ `SKU_UPDATED`. Phase 1 không ghi đè `product_status` và `price_updated_at` — chỉ Event Handler được phép ghi đè. `SKU_UPDATED` do Phase 2 phát sau DB commit cũng không ghi đè `price_updated_at` (vì nó thuộc về giá).

## 3. Consistency & Concurrency

1. **API và Markdown Worker**: Sử dụng Pessimistic Locking theo thứ tự **Product -> SKU** để đảm bảo atomicity và chống deadlock.
2. Chuẩn hóa lock order trong transaction: lock Product cha trước, rồi lock SKU con.
3. **Inventory Sync Worker**: Áp dụng **Two-Phase Lock-Free Architecture** — Phase 1 lock-free trên Redis, Phase 2 batch Optimistic Locking trên PostgreSQL. Không dùng Pessimistic Locking cho từng event vì hot SKU contention quá cao. Xem [Inventory Sync Optimization](inventory_sync_optimization.md).
4. Luồng auto-reactivate chỉ chạy khi `status = HIDDEN`, `admin_hidden = false` và `is_deleted = false` trong cùng transaction (Phase 2).
5. Với Markdown batch worker chạy nhiều instance, cần giữ xử lý tuần tự theo `product_id` bằng lock row chuẩn (`FOR NO KEY UPDATE`) + `lock_timeout`/retry policy rõ ràng để tránh hai pod cùng mutate một product trong cùng thời điểm.
6. Với guard TOCTOU của auto-reactivate, không được check ngoài transaction. Có thể dùng row Product đã lock sẵn hoặc chạy query predicate tường minh trong cùng transaction; cả hai cách đều phải giữ atomicity check+update.

## 4. Caching Strategy

1. Soft invalidation là chuẩn mặc định (`SET`/partial update), hạn chế `DEL` để tránh cache stampede.
2. Chống stampede chuẩn mặc định là cross-pod distributed lock (`SETNX product:lock:fetch:{sku_id}` với lease ngắn), có thể kết hợp in-process singleflight trong từng pod.
3. Point-read cache `product:sku:{sku_id}` chỉ là implementation detail nội bộ Product Service.
4. Validation category/attribute được thực hiện trực tiếp từ PostgreSQL mà không cần cache trung gian.
5. Mỗi nguyên nhân cập nhật cache phải có một writer async chuẩn duy nhất. **`product:sku:{sku_id}` do Event Handler Worker ghi đè** cho mọi SKU event (`SKU_CREATED`, `SKU_UPDATED`). Markdown Worker chỉ set/del `product:markdown:active:{sku_id}`. Phase 1 (Lua CAS) không ghi `product:sku:{sku_id}`. In-band API đã refresh cache ngay trong request cho PRODUCT_STATUS_CHANGED, PRODUCT_DELETED, PRODUCT_UPDATED.
6. Nguồn dữ liệu refresh cache ưu tiên event payload; DB fallback chỉ dùng khi payload không đủ contract. Không dùng Search/Elasticsearch làm nguồn rebuild cache point-read.

## 5. Contract Governance

1. Event schema chỉ thay đổi theo hướng additive.
2. Nếu có breaking change, tạo event type/version mới và rollout theo kế hoạch tương thích ngược.
3. Định kỳ chạy contract test liên service cho các payload chính:
    - `PRODUCT_CREATED`
    - `PRODUCT_UPDATED`
    - `PRODUCT_STATUS_CHANGED`
    - `SKU_UPDATED` — **đặc biệt chú ý**: Search Service và Event Handler phải xử lý đúng `changed_fields[]` và `change_source` để phân biệt loại thay đổi (price vs status vs media). Contract test phải verify rằng:
        - `changed_fields` chứa đúng field thay đổi (không thiếu, không thừa)
        - `change_source` bắt buộc khi `sku_status` trong `changed_fields`
        - downstream không throw exception khi nhận unknown value trong `changed_fields`
    - `SKU_CREATED`
    - `SKU_DELETED`
    - `INVENTORY_QUANTITY_CHANGED`
    - `CATEGORY_UPDATED` / `CATEGORY_DELETED`
