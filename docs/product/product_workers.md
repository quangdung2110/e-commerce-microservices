# Product Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tại Product Service, dữ liệu là tĩnh (Command Model / Master Data) nên Service không sử dụng Redis làm cơ sở dữ liệu chính như Cart Service. Hầu hết các Workers trong Product Service đóng vai trò phục vụ việc **Đồng bộ Dữ liệu Phân Tán (Saga / CQRS)** để đảm bảo các hệ thống xung quanh như Search Service, Notification, Promotion nhận được tín hiệu cập nhật đúng thời điểm.

Dưới đây là danh sách Worker nội bộ thuộc vòng tay quản lý của cụm Máy Chủ Product Service.

---

## Tổng Hợp Kafka Topics

|| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
|| **Produce** | `t.product.events` | - | Topic chính chứa mọi event phát đi |
|| **Produce** | `t.category.events` | - | Topic category lifecycle events |
|| **Consume** | `t.product.events` | `product-event-handler-group` | Self-consume để refresh cache và xử lý category lifecycle |
|| **Consume** | `t.category.events` | `product-event-handler-group` | Self-consume `CATEGORY_DELETED` để đồng bộ lại Write Model |
|| **Consume** | `t.promotion.markdown.events` | `product-markdown-group` | Nhận đúng allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`; event lạ phải skip + metric + DLQ |
|| **Consume** | `t.inventory.events` | `product-inventory-sync-group` | Nhận tín hiệu tồn kho từ Inventory |

Xem cấu trúc payload chi tiết cho tất cả events tại [Product Events](product_events.md).

---

## 1. Debezium CDC Connector (Thay thế hoàn toàn Polling Worker)

**Loại hình:** Kafka Connect Source Connector (Đọc Real-time dưới tầng Đĩa cứng).

**Sứ mệnh:** Chuyển Event đã sinh ra từ bảng `product_outbox_events` sang Kafka Topic (`t.product.events` và `t.category.events`) với độ trễ < 5ms thông qua luồng bắt Read-Log (Write-Ahead-Log). Thay thế hoàn toàn vòng lặp CPU SELECT tốn kém của Outbox Polling cũ. Xem [Product Workflow (API Sinh Outbox)](product_workflow.md#2-api-tạo-mới-sản-phẩm-bản-nháp-create-concept).

**Chi tiết luồng hoạt động:**

1. Khi có tạo mới/sửa đổi API, nó đồng thời Insert Record vào bảng Outbox (với `event_id` là Khóa chính (UUID)). DB cập nhật WAL dưới ổ cứng.
2. Debezium Connector gắn qua cổng Logical_Replication_Slot của Postgres tiến hành đọc stream từ đĩa. Hành vi này không chiếm dữ liệu 1 chút tài nguyên CPU/Connection Pool nào của Request người dùng đập vào DB.
3. **[CRITICAL Kafka Routing]:** Debezium được cấu hình để trích xuất trực tiếp giá trị từ cột `partition_key` (đã được lưu sẵn `product_id`) làm Kafka Message Key. Điều này ghim chặt tất cả Event thay đổi của cùng 1 Sản phẩm sẽ rơi vào chung một Kafka Partition, phụ thuộc hoàn hảo nguyên tắc CQRS cho Search Service (không được dùng SMT bóc tách JSON ở cột payload vì rủi ro Crash và giảm hiệu năng).
4. Debezium đọc bản ghi và Connector tự động định dạng thành CloudEvents và phát lên Topic Kafka mong muốn.
5. **[Dead Letter Queue - DLQ]:** Mọi cấu hình DLQ, Retry, Error Handling được thực hiện trực tiếp tại Kafka Connect (Sink/Source Dead Letter Topic). Table outbox được dọn dẹp định kỳ bằng retention job theo cửa sổ thời gian (`created_at`).

## 2. Event Handler Worker (Cache Refresh & Category Lifecycle)

**Loại hình:** Kafka Consumer Lắng Nghe Topic Của Chính Nó.

**Sứ mệnh:** Xóa/Cập nhật Cache (Soft Invalidation) và xử lý Category Lifecycle thông qua Worker (Out-of-band). Đây là cơ chế **Dự phòng (Fallback & Self-Healing)** kết hợp. Chức năng cập nhật Cache ngay tức thì lúc admin Sửa Dữ liệu bắt buộc phải được đặt **In-band** (cùng luồng với lệnh trả HTTP 200 OK) để đảm bảo quyền lợi **Read-Your-Own-Writes**. Worker Kafka này lắng nghe Event ngầm để dọn sóng, tự động Overwrite làm mới lại hệ thống nếu Node API rớt mạng không kịp SET sau In-band, hoặc phục vụ luồng Cache cập nhật do System Bots (VD: Markdown Pricing đổi giá / Inventory). Thay vì API tự xóa (Hard DEL) Redis gây rủi ro Thundering Herd, Worker này luôn dùng chiều (SET để dữ liệu mới).

**Chi tiết luồng hoạt động:**

1. Worker Kafka (`groupId="product-event-handler-group"`) lắng nghe Topic `t.product.events` và `t.category.events`.
2. Mỗi vòng `poll()` lấy một `poll-batch` records và chuẩn hóa CloudEvents (`event_type`, `payload`, `partition`, `offset`, `timestamp`) trước khi xử lý.
3. Batch được tách thành các lane xử lý:
   - **Lane SKU cache keys**: xử lý theo từng record để refresh `product:sku:{sku_id}` hoặc các `sku_id` trong mảng.
4. Ma trận xử lý theo `event_type` và nguồn dữ liệu:
   - `PRODUCT_PRICE_UPDATED`: dùng payload event làm nguồn chính để xác định `sku_id`, `product_id`, sau đó refresh cache.
   - `PRODUCT_SKU_DELETED`: dùng payload tombstone (`sku_id`, `product_id`, `deleted_at`) để refresh key SKU về trạng thái đã xóa.
   - `PRODUCT_STATE_CHANGED`, `PRODUCT_DELETED`: bắt buộc có `payload.sku_ids[]`. Worker loop theo danh sách này để refresh toàn bộ `product:sku:{sku_id}`. Nếu thiếu `sku_ids[]` -> coi là contract violation, chuyển DLQ.
   - `PRODUCT_UPDATED`: dùng chiến lược **payload-first, DB-fallback**:
     - Nếu payload có **full snapshot** (`product_id`, `status`, `category_id`, `updated_at`, `skus[]` và mỗi SKU có đủ `sku_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`) thì refresh cache hoàn toàn từ payload.
     - Nếu payload thiếu bất kỳ trường bắt buộc nào, worker phải DB re-fetch snapshot đầy đủ Product + SKUs trong một lần đọc rồi ghi cache từ snapshot DB.
     - Trong cùng một event, **không được trộn nguồn** (một phần từ payload, một phần từ DB). Chọn đúng một nguồn cho toàn bộ thao tác ghi.
     - Tuyệt đối không fallback sang Search/Elasticsearch vì không đảm bảo freshness cho write model.
   - `CATEGORY_DELETED`: xử lý Write Model trước khi downstream phản chiếu:
     - Validate `category_id`
     - Xác định phạm vi tác động: nếu payload có `descendant_category_ids` thì phải xử lý cả subtree; nếu không có thì tối thiểu xử lý `category_id` gốc.
     - Xử lý theo batch nhỏ (khuyến nghị 100 product/batch), transaction-per-batch: cập nhật các product liên đới về trạng thái `HIDDEN` (Lý do: Category deleted) tại PostgreSQL.
     - Insert outbox `PRODUCT_STATE_CHANGED` cho các product bị tác động trong cùng transaction.
5. Quy tắc ownership bắt buộc: với luồng giá Markdown, `PRODUCT_PRICE_UPDATED` là tín hiệu chuẩn để Event Handler ghi đè `product:sku:{sku_id}`. Markdown Worker không được ghi đè key này trực tiếp để tránh double-write bất đồng bộ.

6. **[Execution Contract bắt buộc: Offset/Retry/DLQ/Pause-Resume]**
   - **Offset commit**: chỉ commit offset sau khi record đã xử lý xong theo một trong hai trạng thái: (a) ghi cache thành công, hoặc (b) đã đưa record sang DLQ theo nhánh non-retryable.
   - **Retry transient**: lỗi Redis timeout, lỗi DB tạm thời, lỗi network tạm thời -> không commit offset record đó, retry với exponential backoff + jitter (khuyến nghị tối đa 5 lần).
   - **DLQ non-retryable**: payload sai schema, thiếu trường bắt buộc, hoặc lỗi nghiệp vụ không thể tự hồi phục -> publish sang topic DLQ của invalidation (kèm `event_id`, `event_type`, `partition`, `offset`, `error_code`) rồi commit offset để tránh poison-message loop.
   - **Pause/Resume**: khi Redis/DB suy giảm hoặc backlog xử lý nội bộ vượt ngưỡng, worker phải `pause()` các partitions đang gán; chỉ `resume()` khi dependency hồi phục và backlog về ngưỡng an toàn.
   - **Rebalance/Shutdown**: trước khi revoke partition hoặc shutdown pod, phải flush các write đang in-flight và commit các offset đã xử lý thành công để giảm duplicate processing.
7. **Cảnh Báo lỗi Circular Dependency (Loop Vô Tận):** Worker này CHỈ ĐƯỢC PHÉP thao tác read từ Database và write vào Redis. TUYỆT ĐỐI KHÔNG gọi bất kỳ hàm Business Logic hay Entity Save (Write Database) nào trong luồng xử lý này. Nếu gọi nhầm, nó sẽ lưu Database -> sinh ra Outbox Event để ra Kafka Message mới -> tự Worker lại nhận Message đó và tiếp tục chạy -> Vòng lặp hủy diệt toàn bộ hệ thống.
8. Với các event Product làm thay đổi footprint category (tạo/sửa/xóa product hoặc đổi trạng thái hiển thị), không cần phát thêm L1 Cache Invalidate Command vì đã không còn sử dụng Caffeine L1 cho Category Tree.

## 3. Product Indexing Sync Job (Dành cho Search Service)

*Lưu ý: Job này không nằm bên trong Code của Product Service nữa, mà thuộc về **[Search Service](../search/search_workers.md#1-product-indexing-worker-kafka-consumer)**.*

Product Service là Command. Search Service là Query. Search Service có hai consumer groups chính:

- `search-product-indexer` consume `t.product.events` (`PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_PRICE_UPDATED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`).
- `search-category-indexer` consume `t.category.events` (`CATEGORY_UPDATED`, `CATEGORY_DELETED`).

Hai luồng này phối hợp để đảm bảo projection Elasticsearch luôn đúng ownership boundary. Xem chi tiết tại [Search Service - Workers](../search/search_workers.md) và [Elasticsearch Schema](../search/search_elasticsearch_schema.md).

## 4. Outbox Retention Trimmer (Time-Window Cleanup Cronjob)

**Loại hình:** Job dọn dẹp chạy lúc ít người dùng nhất (Ví dụ 3:00 Sáng Chủ Nhật).

**Sứ mệnh:** Dữ liệu trong bảng `product_outbox_events` là log append-only phục vụ Debezium CDC. Cần dọn dữ liệu cụ thể theo retention window để tránh phình dung lượng đĩa.

**Chi tiết luồng hoạt động (Time-Window Cleanup):**

1. Hệ thống Wake-up.
2. Dọn dẹp theo lô nhỏ theo `created_at` để hạn chế lock dài. Không dùng lệnh `DELETE FROM ...` toàn bảng.

```sql
WITH to_delete AS (
  SELECT event_id
  FROM product_outbox_events
  WHERE created_at < NOW() - INTERVAL '30 days'
  ORDER BY created_at ASC
  LIMIT 10000
)
DELETE FROM product_outbox_events poe
USING to_delete
WHERE poe.event_id = to_delete.event_id;
```

3. Lưu ý: Vì Product dùng Debezium CDC từ WAL (không dùng outbox polling trạng thái `FAILED/DEAD`), retention được quản trị theo age của record và trạng thái Kafka Connect offset.

## 5. Markdown Price Calculator Worker

**Loại hình:** Kafka Consumer lắng nghe Topic `t.promotion.markdown.events`.

**Sứ mệnh:** Nhận event Markdown (Giảm giá trực tiếp) từ Promotion Service.

**Chi tiết luồng hoạt động:**

1. Worker (`groupId="product-markdown-group"`) nhận event từ `t.promotion.markdown.events`.
2. Phân loại event_type (`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`).
   - Truy xuất danh sách SKU bị ảnh hưởng dưới dạng danh sách phẳng có trộn nhiều `product_id`.
   - **Group tại tầng Application:** Worker phải gom theo `product_id` (map trong RAM hoặc iterator grouping) để tạo các cụm xử lý độc lập theo Product. Với tập dữ liệu lớn, ưu tiên streaming/chunk theo `ORDER BY product_id ASC, sku_id ASC`, tránh giữ toàn bộ map quá lớn trên RAM.
3. **Transaction per Product + Batch DB Write:**
   - **CHỐNG DEADLOCK HÀNG LOẠT (BATCH DEADLOCK):** BẮT BUỘC chỉ được mở `@Transactional` cho **TỪNG `product_id`** một (Transaction Size = 1 Product). CẤM mở 1 Transaction bọc cả ngàn sản phẩm.
   - Bên trong mỗi Transaction con (trên từng product):
   - **CHỐNG VA CHẠM GIỮA NHIỀU INSTANCE WORKER:** Bước đầu tiên trong transaction phải lock Product cha bằng `FOR NO KEY UPDATE` với `lock_timeout` ngắn. Nếu lock timeout, worker `nack/requeue` theo policy retry để tránh xung đột âm thầm.
   - Bước 1 - Lock Cha: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Bước 2 - Lock Con: `SELECT sku_id, original_price FROM product_skus WHERE sku_id IN (...) AND product_id = :product_id FOR UPDATE;`. Khi nhận `MARKDOWN_UPDATED`, biến `original_price` dùng cho phép tính `%` giảm giá BẮT BUỘC phải lấy từ DB hiện tại (kết quả query này), không được lấy từ `original_price` cũ.
   - Bước 2.1 - Công thức giá chuẩn (bắt buộc):
     - `PERCENTAGE`: `discount_amount = MIN(original_price * discount_value / 100, max_discount_amount)` (nếu có cap), `promotional_price = MAX(0, ROUND(original_price - discount_amount, 2))`.
     - `FIXED_AMOUNT`: `promotional_price = MAX(0, ROUND(original_price - discount_value, 2))`.
   - Bước 3 - Batch update `product_skus` (giá markdown), batch upsert `product_markdown_applied`, batch insert `product_outbox_events` ngay trong cùng transaction.
   - Bước 4 - Insert outbox [`PRODUCT_PRICE_UPDATED`](product_events.md#33-product_price_updated) cho các SKU bị ảnh hưởng.
4. **Cache Update sau Commit (Redis Pipeline - Ownership rõ ràng):**
   - Sau mỗi lần COMMIT của từng Product, Markdown Worker KHÔNG ghi đè trực tiếp `product:sku:{sku_id}` để tránh trùng lặp với Event Handler.
   - Làm mới `product:sku:{sku_id}` được giao cho Event Handler khi self-consume `PRODUCT_PRICE_UPDATED` từ `t.product.events`.
   - Markdown Worker chỉ quản lý key phụ `product:markdown:active:{sku_id}` bằng pipeline theo batch để phản ánh rule markdown đang active.
   - Nếu nhận `MARKDOWN_ENDED` do Admin tắt thủ công (`reason = ADMIN_DISABLED`), phải `DEL product:markdown:active:{sku_id}` cho SKU bị ảnh hưởng, và nên đưa các lệnh `DEL` này vào cùng pipeline batch.

> **No-op bắt buộc cho `MARKDOWN_ENDED`:** nếu query theo `rule_id` không trả về SKU nào, worker phải ACK và kết thúc (không coi là lỗi, không phát outbox rỗng).

## 6. Inventory Sync Worker

**Loại hình:** Kafka Consumer lắng nghe Topic `t.inventory.events`.

**Sứ mệnh:** Đồng bộ dữ liệu tồn kho từ Inventory Service về Product Service. Cập nhật `display_quantity` trên `product_skus` và trigger tự động ẩn/hiện sản phẩm khi hết hàng/có hàng trở lại. Xem chi tiết tại [Workflow Inventory Sync](product_workflow.md#7-workflow-xử-lý-inventory-sync-event).

> **Chuẩn Lock Hierarchy bắt buộc:** Với mỗi transaction cùng chạm `products` và `product_skus`, Inventory Sync Worker phải lock theo thứ tự **Product -> SKU** để tránh vòng deadlock AB-BA với Markdown Worker và API Update Product.

**Chi tiết luồng hoạt động:**

1. Worker (`groupId="product-inventory-sync-group"`) nhận `INVENTORY_QUANTITY_CHANGED` event.
2. `@Transactional`:
   - Bước 1: Lock Product cha từ payload event: `SELECT product_id, status, is_deleted FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Bước 2: Lock SKU con và validate cặp khóa: `SELECT sku_id, product_id, status, last_inventory_sync_at FROM product_skus WHERE sku_id = :sku_id AND product_id = :product_id FOR UPDATE;`
   - Bước 2.1: Nếu không tìm thấy row ở Bước 2, coi là payload sai cặp `product_id/sku_id` -> không update DB, đưa DLQ/parking-lot để điều tra producer contract.
   - Bước 3: So sánh thứ tự sự kiện. Nếu `event_timestamp <= last_inventory_sync_at` thì bỏ qua message (stale).
   - Bước 4: Dùng lệnh `UPDATE product_skus SET display_quantity = :available_quantity, last_inventory_sync_at = :event_timestamp WHERE sku_id = :sku_id`.
   - Bước 5: Sử dụng Pessimistic Locking. Nếu transaction chỉ chạy SQL và không gọi FSM/JPA thì áp dụng lock row `SELECT ... FOR NO KEY UPDATE` ở đầu transaction. Nếu transaction gọi FSM/JPA thì lock được đảm bảo bởi logic rẽ nhánh FSM.
3. Logic auto-hide/reactivate (Dựa trên dữ liệu đã lock ở Bước 1 + Bước 2, chặn đúng TOCTOU):
   - **[Rào Cản Out-Of-Order]**: Trước tiên, Worker phải so sánh `event_timestamp` từ Event với trường `last_inventory_sync_at` của SKU trên database (vừa lock ở bước 2). Nếu `event_timestamp <= last_inventory_sync_at`, vứt Message và return bỏ qua để triệt tiêu lỗi Kafka Lag cập nhật tồn kho lạc hướu. Nếu hợp lệ, gán `last_inventory_sync_at = event_timestamp`.
   - Tuyệt đối không được thực hiện thêm một vòng `SELECT` mới bên ngoài Transaction để kiểm tra lại trạng thái SKU. Toàn bộ chuỗi **Đọc Product -> Đọc SKU -> Tính toán auto-hide/reactivate -> Update Product/SKU** phải nằm trong cùng Transaction đang giữ lock.
   - Nếu cần guard tường minh trước `AUTO_REACTIVATE_IN_STOCK`, được phép chạy thêm query predicate trong CHÍNH transaction hiện tại (ví dụ `SELECT 1 ... FOR NO KEY UPDATE`), hoặc dùng trực tiếp row Product đã lock ở bước 1. Cả hai cách đều hợp lệ nếu không thoát transaction.
   - Nếu `products.is_deleted = true`, worker vẫn cập nhật `display_quantity/sku_status` theo inventory nhưng **không trigger** bất kỳ transition Product-level nào.
   - `available_quantity = 0` + SKU `ACTIVE` -> chuyển SKU sang `OUT_OF_STOCK`. Cập nhật ngay snapshot SKUs trong memory của Transaction; nếu 100% SKU đều hết hàng và `is_deleted = false` thì trigger `AUTO_HIDE_OUT_OF_STOCK` trên FSM.
   - `available_quantity > 0` + SKU `OUT_OF_STOCK` -> chuyển SKU về `ACTIVE`. Cập nhật snapshot, kiểm tra Product đang `HIDDEN` và `is_deleted = false` thì trigger `AUTO_REACTIVATE_IN_STOCK`.
   - **Outbox boundary bắt buộc:** Ngay trong transaction đang xử lý logic ở bước 2-3, nếu SKU status thay đổi thì phải `INSERT` `PRODUCT_STATE_CHANGED` vào `product_outbox_events` trước khi commit. Không phát event ở ngoài transaction.
4. Commit transaction thành công rồi mới ack offset; nếu rollback thì không ack để message được retry theo policy.
5. **Cache Update (Đồng bộ - Không Queue):**
   - Sau khi Commit Database thành công, gọi Redis SET đồng bộ ngay lập tức với retry ngắn (2-3 lần).
   - Sử dụng **Redis Pipelining** để gửi các lệnh cập nhật theo batch, tối thiểu gồm: `display_quantity`, `sku_status` (nếu đổi), và `last_inventory_sync_at` trên key `product:sku:{sku_id}`.
   - **Fallback self-healing bắt buộc:** nếu Pod crash sau DB commit nhưng chưa flush Redis xong, `PRODUCT_STATE_CHANGED` đã nằm trong Outbox/Kafka sẽ được `product-event-handler-group` tiêu thụ để tự động refresh lại `product:sku:{sku_id}`.
6. **Chặn Out-of-Order khi update cache cùng SKU:**
   - Sử dụng staleness guard dựa trên `last_inventory_sync_at` đã lưu trong cache.
   - Chỉ apply cache update nếu `incoming.event_timestamp >= cached.last_inventory_sync_at`; nếu cũ hơn thì bỏ qua, chặn stale write.

## 7. Markdown Expiry Cronjob (Self-Healing)

**Loại hình:** Cron Job chạy định kỳ (**1 lần/ngày** - lúc ít người dùng nhất, ví dụ 3:00 Sáng).

**Sứ mệnh:** Dọn dẹp/Backup các trường hợp Product bị kẹt giá khuyến mãi do rớt mạng hoặc lỗi Kafka Event `MARKDOWN_ENDED` từ Promotion Service. Đảm bảo Product không bao giờ lưu giá cũ vĩnh viễn (Self-Healing).

**Chi tiết luồng hoạt động:**

> **CẢNH BÁO BẮT BUỘC (Tránh Deadlock):** CronJob quét rất nhiều dòng cùng lúc. Khi tiến hành cập nhật bảng con `product_skus`, worker PHẢI tuân thủ nguyên tắc Lock Hierarchy: Lock bảng cha `products` trước để tránh lỗi Deadlock vòng tròn `AB-BA` với luồng API Sửa sản phẩm của admin (API này đi từ Products -> SKUs).

0. **Distributed lock bắt buộc:** trước khi quét, cron phải acquire lock phân tán `product:jobs:markdown-expiry:lock` (SET NX PX, TTL khuyến nghị 60s và gia hạn khi job chạy lâu). Nếu không acquire được lock thì instance hiện tại phải skip vòng chạy để tránh nhiều pod cùng dọn một tập dữ liệu.

1. Quét DB tìm các quy tắc đã quá hạn (Bắt buộc ORDER BY để chống Deadlock và dùng LIMIT để chia Batch):

```sql
SELECT product_id, sku_id, rule_id
FROM product_markdown_applied
WHERE expires_at < NOW()
ORDER BY product_id ASC
LIMIT 100; -- Chunking theo Batch để tránh giữ Lock quá lâu
```

2. Nếu có dữ liệu mớ rỗng (Xử lý Batch theo từng `product_id`):
   - **BẮT BUỘC CHUNKING**: Cronjob có thể lấy ra hàng chục nghìn records. Tuyệt đối không được nối tất cả vào chung một Transaction. Phải xử lý xong một khối 100 records và **COMMIT Giao Diện** ngay lập tức, nhằm xả Lock cho Connection Pool và tránh Block API của admin.
   - **BẮT BUỘC LOCK THEO THỨ TỰ**: Bên trong mỗi Transaction khối, thao tác trên từng `product_id` phải duyệt theo thứ tự TĂNG DẦN (ASC) để ngăn Deadlock chéo. Lock bảng CHA `products` trước: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Reset `promotional_price = NULL` ở `product_skus`.
   - Delete dòng mapping ở bảng `product_markdown_applied`.
   - Ban Outbox trigger Event `PRODUCT_PRICE_UPDATED`.
   - Xóa Cache liên quan.
