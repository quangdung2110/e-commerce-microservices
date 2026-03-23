# Product Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tại Product Service, dữ liệu là tĩnh (Command Model / Master Data) nên Service không sử dụng Redis làm cơ sở dữ liệu chính như Cart Service. Hầu hết các Workers trong Product Service đóng vai trò phục vụ việc **Đồng bộ Dữ liệu Phân tán (Saga / CQRS)** để đảm bảo các hệ thống xung quanh như Search Service, Inventory, Order nhận được phiên bản Sản Phẩm mới nhất.

Dưới đây là danh sách Worker nội bộ thuộc vòng tay quản lý của cụm Máy Chủ Product Service.

---

## Tổng Hợp Kafka Topics

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| **Produce** | `t.product.events` | — | Topic chính chứa mọi event phát đi |
| **Consume** | `t.product.events` | `product-invalidation-group` | Self-consume để xoá cache |
| **Consume** | `t.promotion.markdown.events` | `product-markdown-group` | Nhận Markdown rules từ Promotion |
| **Consume** | `t.inventory.events` | `product-inventory-sync-group` | Nhận tín hiệu tồn kho từ Inventory |

Xem cấu trúc payload chi tiết cho tất cả events tại [Product Events](product_events.md).

---

## 1. Debezium CDC Connector (Thay thế hoàn toàn Polling Worker)
**Loại hình:** Kafka Connect Source Connector (Đọc Real-time dưới tầng Đĩa cứng).

**Sứ mệnh:** Chuyển Event đã sinh ra từ bảng `product_outbox_events` sang Kafka Topic (`t.product.events`) với độ trễ < 5ms thông qua luồng bắt Read-Log (Write-Ahead-Log). Thay thế hoàn toàn vòng lặp CPU SELECT tốn kém của Outbox Polling cũ. Xem [Product Workflow (API Sinh Outbox)](product_workflow.md#2-api-tạo-mới-sản-phẩm-bản-nháp-create-concept).

**Chi tiết luồng hoạt động:**
1. Khi có tạo mới/sửa đổi API, nó đồng thời Insert Record vào bảng Outbox (với `event_id` là Khóa chính (UUID)). DB cập nhật WAL dưới ổ cứng.
2. Debezium Connector gắn qua cổng Logical_Replication_Slot của Postgres tiến hành đọc stream tự động. Hành vi này Không chiếm dụng 1 chút tài nguyên CPU/Connection Pool nào của Request người dùng đập vào DB.
3. **[CRITICAL Kafka Routing]:** Debezium được cấu hình để trích xuất trực tiếp giá trị từ cột `partition_key` (đã được lưu sẵn `product_id`) làm Kafka Message Key. Điều này ghim chặt tất cả Event thay đổi của cùng 1 Sản phẩm sẽ rơi vào chung một Kafka Partition, phục tùng hoàn hảo nguyên tắc CQRS cho Search Service (không được dùng SMT bóc tách JSON ở cột payload vì rủi ro Crash và giảm hiệu năng).
4. Debezium đọc bản ghi và Connector tự động định dạng thành CloudEvents và phát lên Topic Kafka mong muốn.
5. **[Dead Letter Queue - DLQ]:** Mọi cấu hình DLQ, Retry, Error Handling được thực hiện trực tiếp tại Kafka Connect (Sink/Source Dead Letter Topic) thay vì dùng cột `status`, `retry_count` rác trong Database. Table Outbox sẽ bị xoá bỏ định kỳ thông qua Batch Job (Table Partitioning).

## 2. Invalidation Consumer Worker (Soft Invalidation - Mandatory)

**Loại hình:** Kafka Consumer Lắng Nghe Topic Của Chính Nó.

**Sứ mệnh:** Xoá/Cập nhật Cache (Soft Invalidation) thông qua Worker (Out-of-band) trở thành quy tắc **Dự phòng (Fallback & Self-Healing)**. Chức năng cập nhật Cache ngay tức thì lúc Seller Sửa Dữ Liệu bắt buộc phải được đặt **In-band** (cùng luồng với lệnh trả HTTP 200 OK) để đảm bảo quyền lợi **Read-Your-Own-Writes**. Worker Kafka Invalidation này lắng nghe Event ngầm để dọn sóng, tự động Overwrite làm mượt lại hệ thống nếu Node API rớt mạng không kịp SET sau In-band, hoặc phục vụ luồng Cache cập nhật do System Bots (VD: Markdown Pricing đổi giá / Inventory). Thay vì API tự xóa (Hard DEL) Redis gây rủi ro Thundering Herd, Worker này luôn dùng chiêu (SET đè dữ liệu mới).

**Chi tiết luồng hoạt động:**
1. Worker Kafka (`groupId="product-invalidation-group"`) lắng nghe Topic `t.product.events`.
2. Khi Event `ProductUpdatedEvent` / `ProductStateChangedEvent` / `ProductPriceUpdatedEvent` nhảy xuống:
3. Hàm xử lý: Re-fetch data và thực hiện `Redis.Set("product:sku:" + event.sku_id, new_data)` và/hoặc `Redis.Set("product:master:" + event.product_id, new_data)`.
4. Nếu liên quan Category cập nhật -> Gửi Local L1 Cache Invalidate Command qua Pub/Sub cho tất cả các Pods (Máy chủ) Product Service xóa/cập nhật Caffeine Local Cache của key `product:categories:tree`.

## 3. Product Indexing Sync Job (Dành cho Search Service)
*Lưu ý: Job này không nằm bên trong Code của Product Service nữa, mà thuộc về **[Search Service](../search/search_workers.md#1-product-indexing-worker-kafka-consumer)**.*

Product Service là Command. Search Service là Query. Search Service sở hữu một Consumer Group (`group_id=search-product-indexer`) chịu trách nhiệm hứng toàn bộ [`ProductCreatedEvent`](product_events.md#31-product_created), [`ProductUpdatedEvent`](product_events.md#32-product_updated), và [`ProductPriceUpdatedEvent`](product_events.md#34-product_price_updated) đổ về từ Kafka để nhét Data (Upsert) vào Elasticsearch Cluster, từ đó cho phép user lọc động, Text Search, Fuzzy Match hiệu năng cao. Xem chi tiết thiết kế tại [Search Service - Workers](../search/search_workers.md) và [Elasticsearch Schema](../search/search_elasticsearch_schema.md).

## 4. Old Outbox Trimmer (Batch Cronjob)
**Loại hình:** Job dọn dẹp chạy lúc ít người dùng nhất (Ví dụ 3:00 Sáng Chủ Nhật Thời Gian Cục Bộ Châu Á).

**Sứ mệnh:** Dữ liệu trong bảng `product_outbox_events` là log rác phát sinh khổng lồ sau quá trình bắn Event thành công. Không dọn sẽ làm đầy ổ cứng.

**Chi tiết luồng hoạt động (Đã Tối Ưu Hiệu Năng bằng Table Partitioning):**
1. Hệ thống Wake-up.
2. Dọn dẹp bằng cách cắt vứt Database Partition cũ. Không dùng lệnh `DELETE FROM ...` từng dòng vì sẽ làm đơ DB.
   ```sql
   DROP TABLE IF EXISTS product_outbox_events_y2025m01; -- Ví dụ xoá log của tháng cũ
   ```
3. Lưu ý: Không xóa `FAILED` quá hạn hay `DEAD` (Các message đã nằm Dead Letter Channel) mà để yên phục vụ Trace log hoặc xử lý tay từ SysAdmin Dashboard.

## 5. Markdown Price Calculator Worker
**Loại hình:** Kafka Consumer lắng nghe Topic `t.promotion.markdown.events`.

**Sứ mệnh:** Nhận event Markdown (Giảm giá trực tiếp) từ Promotion Service, tính toán `promotional_price` cho các SKU bị ảnh hưởng, và phát event `ProductPriceUpdatedEvent` để Search Service cập nhật giá hiển thị. Xem chi tiết luồng tại [Workflow Markdown](product_workflow.md#6-workflow-xử-lý-markdown-event-từ-promotion-service) và kiến trúc gốc tại [Promotion DB Design](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).

> **Ownership Clarification:**
> - Event `MARKDOWN_ENDED` (bao gồm reason `EXPIRED` và `ADMIN_DISABLED`) được **Promotion Service** produce.
> - Promotion CronJob ["Markdown Expiry Scanner"](../promotion/promotion_workflow.md#6-markdown-expiry-scanner-cronjob) quét `markdown_rules` hết hạn → phát event → Product Worker #5 consume và reset giá.
> - Product Service **KHÔNG** tự quét markdown expiry. Nó chỉ phản ứng (reactive) theo event từ Promotion — đúng nguyên tắc Service Ownership.

**Chi tiết luồng hoạt động:**
1. Worker (`groupId="product-markdown-group"`) nhận event từ `t.promotion.markdown.events`.
2. Phân loại event_type:
   - Trước khi tính toán bất kỳ giá trị nào liên quan tới giá, bắt buộc khơi mào Database Transaction. ĐỂ CHỐNG DEADLOCK, PHẢI LOCK THEO ĐÚNG THỨ TỰ CHA-CON: Đầu tiên xin khóa bi quan bằng SQL: `SELECT 1 FROM products WHERE id = ? FOR NO KEY UPDATE` (Lock Bảng Products), sau đó mới xin khóa con: `SELECT original_price FROM product_skus WHERE sku_id = ? FOR UPDATE` (Lock Bảng SKUs). Điều này chặn triệt để Race Condition "Toán Học" với Seller API khi người bán đang sửa giá gốc, đảm bảo tính tuần tự.
   - `MARKDOWN_CREATED`: Tính `promotional_price` cho các SKU theo `scope_type` + `ref_id`. Update cha `UPDATE products SET version = version + 1 WHERE id = :product_id`. Batch `UPDATE product_skus.promotional_price`. Insert `product_markdown_applied` (lưu kèm `discount_value`). Phát `ProductPriceUpdatedEvent`.
   - `MARKDOWN_UPDATED`: Re-calculate giá nếu `discount_value` thay đổi. Update cả `product_markdown_applied` lẫn `product_skus.promotional_price` (kèm tăng `version` ở `products`).
   - `MARKDOWN_ENDED`: Query `product_markdown_applied WHERE rule_id = :id`. Reset `promotional_price = NULL` và tăng `version` cho product cha tương ứng. Delete mapping. Phát `ProductPriceUpdatedEvent` với `change_source = MARKDOWN_EXPIRED`.
3. Soft Invalidate Redis cache cho các SKU bị ảnh hưởng (`SET product:sku:{id}`, `SET product:markdown:active:{sku_id}`) và BẮT BUỘC invalidate cache cha `SET product:master:{product_id}` vì thay đổi SKU sẽ ảnh hưởng đến trạng thái bundle tổng thể.

> **Idempotency:** Sử dụng `rule_id` + `sku_id` Unique Index trên `product_markdown_applied` để chống apply trùng. Nếu event được deliver lại (at-least-once), lệnh INSERT sẽ conflict và Worker skip.

## 6. Inventory Sync Worker
**Loại hình:** Kafka Consumer lắng nghe Topic `t.inventory.events`.

**Sứ mệnh:** Đồng bộ dữ liệu tồn kho từ Inventory Service về Product Service. Cập nhật `display_quantity` trên `product_skus` và trigger tự động ẩn/hiện sản phẩm khi hết hàng/có hàng trở lại. Xem chi tiết tại [Workflow Inventory Sync](product_workflow.md#7-workflow-xử-lý-inventory-sync-event).

**Chi tiết luồng hoạt động:**
1. Worker (`groupId="product-inventory-sync-group"`) nhận [`INVENTORY_QUANTITY_CHANGED`](product_events.md#44-inventory_quantity_changed-từ-inventory-service) event.
2. `@Transactional`:
   - Bước 1: Khóa bi quan toàn bộ Product bằng câu lệnh DB: `SELECT id FROM products WHERE id = :product_id FOR NO KEY UPDATE;`
   - Bước 2: Dùng lệnh `UPDATE products SET version = version + 1 WHERE id = :product_id`.
   - Bước 3: Dùng lệnh `UPDATE product_skus SET display_quantity = :available_quantity WHERE sku_id = :sku_id`.
3. Logic auto-hide/reactivate (Được đồng bộ tuyệt đối nhờ Row-Level Lock ở Bước 1, không dùng Redis Lock do rủi ro rớt mạng):
   - `available_quantity = 0` + SKU `ACTIVE` → chuyển SKU sang `OUT_OF_STOCK`. Kiểm tra tất cả SKU của Product → nếu 100% hết hàng → trigger [`AUTO_HIDE_OUT_OF_STOCK`](product_state_machine.md#22-transitions-do-systemworker-tự-động-trigger) trên FSM (kèm tăng version cho product cha). 🔴 **CẢNH BÁO BẮT BUỘC:** Lệnh Update trạng thái này của Worker tuyệt đối KHÔNG ĐƯỢC CHẠM VÀO/OVERWRITE cột `seller_intention_hidden`.
   - `available_quantity > 0` + SKU `OUT_OF_STOCK` → chuyển SKU về `ACTIVE`. Kiểm tra Product đang HIDDEN do OUT_OF_STOCK → trigger [`AUTO_REACTIVATE_IN_STOCK`](product_state_machine.md#22-transitions-do-systemworker-tự-động-trigger) (kèm tăng version). 🔴 **ĐIỀU KIỆN RÀNG BUỘC CỨNG:** Worker trước khi hồi sinh Product về ACTIVE bắt buộc phải dính kèm câu lệnh Check trong Query SQL: `AND seller_intention_hidden = FALSE`. Bất kể Worker thay đổi trạng thái nào cũng phải bỏ rơi column Intention ra ngoài Scope update.
4. Phát [`ProductSkuStatusChangedEvent`](product_events.md#35-product_sku_status_changed) nếu SKU status thay đổi.
5. Invalidate/Update Redis cache: Chuyển sang Partial Update (Ghi đè từng trường riêng rẽ) bằng cách dùng Hash Command `HSET` hoặc `JSON.SET product:sku:{sku_id} .stock_quantity 40` bên ngoài vòng Transaction, TUYỆT ĐỐI KHÔNG dùng lại cấu trúc cũ "Full DB Snapshot ghi đè nguyên một file JSON vào Cache" - nguyên nhân chính gây ghi đè bóng (Stale Cache Fallback Race Condition).


## 7. Markdown Expiry Cronjob (Self-Healing)
**Loại hình:** Cron Job chạy định kỳ (VD: mỗi 5-10 phút).

**Sứ mệnh:** Dọn dẹp/Backup các trường hợp Product bị kẹt giá khuyến mãi do rớt mạng hoặc lỗi Kafka Event `MARKDOWN_ENDED` từ Promotion Service. Đảm bảo Product không bao giờ lưu giá cũ vĩnh viễn (Self-Healing).

**Chi tiết luồng hoạt động:**
> **CẢNH BÁO BẮT BUỘC (Tránh Deadlock):** CronJob quét rất nhiều dòng cùng lúc. Khi tiến hành cập nhật bảng con `product_skus`, worker PHẢI tuân thủ nguyên tắc Lock Hierachy: Lock bảng cha `products` trước để tránh lỗi Deadlock vòng tròn `AB-BA` với luồng API Sửa sản phẩm của Seller (API này đi từ Products -> SKUs).

1. Quét DB tìm các quy tắc đã quá hạn:
   ```sql
   SELECT product_id, sku_id, rule_id
   FROM product_markdown_applied
   WHERE expires_at < NOW();
   ```
2. Nếu có dữ liệu mồ côi (Thực hiện trong 1 Transaction):
   - **(BẮT BUỘC)** Xin lock bảng CHA `products` trước: `SELECT 1 FROM products WHERE id IN (...) FOR NO KEY UPDATE;`
   - Reset `promotional_price = NULL` ở `product_skus`.
   - Cập nhật tăng `version` của bảng cha `products`.
   - Delete dòng mapping ở bảng `product_markdown_applied`.
   - Bắn Outbox trigger Event `ProductPriceUpdatedEvent` với `change_source = MARKDOWN_EXPIRED_FALLBACK`.
   - Xóa Cache liên quan. 
