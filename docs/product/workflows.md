---
doc_id: product-workflows
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Workflows

## 1. Tạo hoặc cập nhật Product

1. Validate category lá, attribute và input SKU.
2. Mở transaction; lock Product nếu đã tồn tại, sau đó lock SKU bị chạm.
3. Insert/update Product, SKU và media; nếu base price đổi khi markdown active thì tính
   lại `selling_price_vnd`.
4. Tăng `catalog_revision` đúng một lần, build full catalog snapshot và insert outbox.
5. Commit, rồi trả response.

Không ghi Redis/cache và không gọi Inventory trong luồng này.

## 2. Lifecycle và hard delete

Admin activate khi Product có ít nhất một SKU `ACTIVE`; hide/show không kiểm tra stock.
Khi xóa SKU, application từ chối SKU cuối cùng. Khi xóa Product hoặc SKU, transaction
chụp các ID liên quan, insert tombstone outbox trước, rồi hard delete row; FK cascade
dọn media/projection liên quan.

## 3. Category và attribute

Category chỉ xóa khi không có con và không có Product. Không có worker self-consume để
cascade hide Product. Application validate cycle và category lá trong transaction.

## 4. gRPC point-read

`GetSku`/`GetBatchSkus` query PostgreSQL, join Product/SKU/media cần thiết và trả
catalog saleability cùng giá công bố. Không dùng cache, không trả stock. Caller Checkout
reserve trực tiếp tại Inventory sau khi đã xác thực catalog/giá.

## 5. Markdown snapshot

Consumer nhận snapshot Promotion, lock Product, so `source_revision`, rồi upsert
projection và reprice tất cả SKU trong cùng transaction. Nếu revision cũ/trùng, ack
no-op. Sau commit, outbox phát snapshot catalog với `catalog_revision` mới.

Scheduled safety check quét projection `ACTIVE` quá `ends_at`; nó dùng cùng logic
revision/transaction và chỉ là safety net khi event deactivate bị trễ.

## 6. Outbox và retention

Debezium đọc WAL của `product_outbox_events` và phát event với `partition_key` đã lưu.
Consumer downstream dedupe theo `event_id` và guard catalog bằng `catalog_revision`.
Retention job xóa outbox quá hạn theo lô sau khi xác nhận connector/consumer monitoring
an toàn.
