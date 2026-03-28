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
3. Service khác (Cart, Checkout, Order, Promotion) chỉ đọc dữ liệu sản phẩm qua gRPC `GetProductSku`/`GetBatchProductSkus`.

## 2. Eventing & Ordering

1. Dùng Debezium CDC + outbox table để phát event, không polling thủ công theo trạng thái hàng.
2. Tách topic:
	- `t.product.events` cho product/sku events.
	- `t.category.events` cho category events.
3. Với `PRODUCT_PRICE_UPDATED`, bắt buộc phát `event_sequence_id` đơn điệu tăng để downstream guard đúng ở cấp SKU event.
4. Consumer downstream phải idempotent, ignore stale event và retry/DLQ rõ ràng.

## 3. Consistency & Concurrency

1. Mọi cập nhật SKU ảnh hưởng business state phải tăng `products.version`.
2. Chuẩn hóa lock order trong transaction: lock Product cha trước, rồi lock SKU con.
3. Inventory sync nhận `product_id` trực tiếp trong payload `INVENTORY_QUANTITY_CHANGED`, sau đó vẫn phải validate cặp `product_id/sku_id` trong DB local trước khi lock/update.
4. Luồng auto-reactivate chỉ chạy khi `status = HIDDEN` và `seller_intention_hidden = false` trong cùng transaction.

## 4. Caching Strategy

1. Soft invalidation là chuẩn mặc định (`SET`/partial update), hạn chế `DEL` để tránh cache stampede.
2. Point-read cache `product:sku:{sku_id}` chỉ là implementation detail nội bộ Product Service.
3. Validation cache category/attribute dùng 2 tầng L1/L2 + Pub/Sub invalidation + TTL fallback.

## 5. Contract Governance

1. Event schema chỉ thay đổi theo hướng additive.
2. Nếu có breaking change, tạo event type/version mới và rollout theo kế hoạch tương thích ngược.
3. Định kỳ chạy contract test liên service cho các payload chính:
	- `PRODUCT_UPDATED`
	- `PRODUCT_PRICE_UPDATED`
	- `INVENTORY_QUANTITY_CHANGED`
	- `CATEGORY_UPDATED` / `CATEGORY_DELETED`

## 6. Operability

1. Bắt buộc theo dõi metric:
	- outbox lag
	- consumer lag
	- cache hit ratio
	- stale-event drop rate
2. Có runbook rõ cho DLQ replay và reconciliation (Product ↔ Search).
3. Duy trì job retention cho outbox partitions để kiểm soát dung lượng lưu trữ.

