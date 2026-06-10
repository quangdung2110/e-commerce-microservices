# Product Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)

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
3. Với `PRODUCT_PRICE_UPDATED`, bắt buộc phát `event_sequence_id` đơn điệu tăng để downstream guard đúng ở cấp SKU event.
4. Consumer downstream phải idempotent, ignore stale event và retry/DLQ rõ ràng.
5. `product-event-handler-group` bắt buộc consume `PRODUCT_SKU_STATUS_CHANGED` để duy trì đường self-healing cache khi luồng queue in-memory của Inventory Worker bị gián đoạn.
6. Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`, mỗi phần tử `skus[]` bắt buộc có `best_price` và `has_promotion` để Search phản chiếu giá trực tiếp, không tự tính lại business pricing.
7. `PRODUCT_UPDATED` phải ưu tiên payload full snapshot; nếu payload thiếu field bắt buộc thì invalidation consumer fallback đọc snapshot PostgreSQL. Không trộn payload + DB trong cùng một event.
8. Invalidation consumer phải có execution contract tường minh: commit offset sau success/DLQ, retry có giới hạn cho lỗi transient, pause/resume partition khi dependency suy giảm.
9. **Inventory Sync (Two-Phase Lock-Free)**: Event `INVENTORY_QUANTITY_CHANGED` được xử lý qua Phase 1 (Lock-Free: Kafka → Redis Lua CAS) và Phase 2 (Async DB Sync: Redis ZSET → PostgreSQL). Xem [Inventory Sync Optimization](inventory_sync_optimization.md).

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
5. Mỗi nguyên nhân cập nhật cache phải có một writer async chuẩn duy nhất. Với giá Markdown, `product:sku:{sku_id}` do Invalidation Consumer ghi đè theo `PRODUCT_PRICE_UPDATED`; Markdown Worker chỉ set/del `product:markdown:active:{sku_id}`.
6. Nguồn dữ liệu refresh cache ưu tiên event payload; DB fallback chỉ dùng khi payload không đủ contract. Không dùng Search/Elasticsearch làm nguồn rebuild cache point-read.

## 5. Contract Governance

1. Event schema chỉ thay đổi theo hướng additive.
2. Nếu có breaking change, tạo event type/version mới và rollout theo kế hoạch tương thích ngược.
3. Định kỳ chạy contract test liên service cho các payload chính:
	- `PRODUCT_CREATED`
	- `PRODUCT_UPDATED`
	- `PRODUCT_PRICE_UPDATED`
	- `PRODUCT_SKU_DELETED`
	- `INVENTORY_QUANTITY_CHANGED`
	- `CATEGORY_UPDATED` / `CATEGORY_DELETED`


