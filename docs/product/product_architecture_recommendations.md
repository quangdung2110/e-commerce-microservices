# Product Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
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
5. `product-invalidation-group` bắt buộc consume `PRODUCT_SKU_STATUS_CHANGED` để duy trì đường self-healing cache khi luồng queue in-memory của Inventory Worker bị gián đoạn.
6. Với luồng invalidation của `product:master`, nếu trong cùng poll-batch có nhiều event của một `product_id`, chỉ xử lý bản mới nhất theo `(version, updated_at, kafka_offset)`.
7. Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`, mỗi phần tử `skus[]` bắt buộc có `best_price` và `has_promotion` để Search phản chiếu giá trực tiếp, không tự tính lại business pricing.

## 3. Consistency & Concurrency

1. Mọi cập nhật SKU ảnh hưởng business state phải tăng `products.version`.
2. Chuẩn hóa lock order trong transaction: lock Product cha trước, rồi lock SKU con.
3. Inventory sync nhận `product_id` trực tiếp trong payload `INVENTORY_QUANTITY_CHANGED`, sau đó vẫn phải validate cặp `product_id/sku_id` trong DB local trước khi lock/update.
4. Luồng auto-reactivate chỉ chạy khi `status = HIDDEN`, `seller_intention_hidden = false` và `is_deleted = false` trong cùng transaction.
5. Với Markdown batch worker chạy nhiều instance, cần giữ xử lý tuần tự theo `product_id` bằng lock row chuẩn (`FOR NO KEY UPDATE`) + `lock_timeout`/retry policy rõ ràng để tránh hai pod cùng mutate một product trong cùng thời điểm.
6. Với guard TOCTOU của auto-reactivate, không được check ngoài transaction. Có thể dùng row Product đã lock sẵn hoặc chạy query predicate tường minh trong cùng transaction; cả hai cách đều phải giữ atomicity check+update.

## 4. Caching Strategy

1. Soft invalidation là chuẩn mặc định (`SET`/partial update), hạn chế `DEL` để tránh cache stampede.
2. Chống stampede chuẩn mặc định là cross-pod distributed lock (`SETNX product:lock:fetch:{sku_id}` với lease ngắn), có thể kết hợp in-process singleflight trong từng pod.
3. Point-read cache `product:sku:{sku_id}` chỉ là implementation detail nội bộ Product Service.
4. Validation cache category/attribute dùng 2 tầng L1/L2 + Pub/Sub invalidation + TTL fallback.
5. Mỗi nguyên nhân cập nhật cache phải có một writer async chuẩn duy nhất. Với giá Markdown, `product:sku:{sku_id}` và `product:master:{product_id}` do Invalidation Consumer ghi đè theo `PRODUCT_PRICE_UPDATED`; Markdown Worker chỉ set/del `product:markdown:active:{sku_id}`.
6. `product:master:{product_id}` phải có stale-write guard `(version, updated_at)` và cơ chế giảm write amplification (dedupe theo `product_id`, debounce ngắn 50-100ms liên batch).

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

## 6. Operability

1. Bắt buộc theo dõi metric:
	- outbox lag
	- consumer lag
	- cache hit ratio
	- stale-event drop rate
2. Có runbook rõ cho DLQ replay và reconciliation (Product ↔ Search).
3. Duy trì retention job theo cửa sổ thời gian cho outbox để kiểm soát dung lượng lưu trữ.

