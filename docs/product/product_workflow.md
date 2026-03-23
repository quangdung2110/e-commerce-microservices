# Product Service - Trình Tự Nghiệp Vụ API (Workflows)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tài liệu này mô tả chi tiết trình tự các bước thực thi từ lúc Seller/Admin gọi API cho đến khi dữ liệu chạy qua **Redis (Validation Cache)** → **PostgreSQL (Source of Truth)** → **Outbox Stream (Event)**.

Khác với Cart (Redis-First), Product Service mang tư tưởng **PostgreSQL-First (Command Model)** nhưng Cache lại đóng vai trò là "Người Gác Cổng" (Validation Gateway).

> **Kiến trúc tổng quan:**
> - [Thẩm định Dữ liệu (Validation Cache)](product_caching_schema.md) — Cache-Aside, Caffeine + Redis, Idempotency bằng SETNX.
> - [Nguồn Sự Thật (PostgreSQL)](product_db_design.md) — Lưu trữ gốc cho Command Write Model.
> - [Biến Trạng thái (State Machine)](product_state_machine.md) — Cấu hình FSM thay đổi trạng thái sản phẩm.
> - [Luồng Đồng Bộ Ngầm (Background Workers)](product_workers.md) — Debezium Outbox + các worker đồng bộ nghiệp vụ.
> - [Cấu trúc Event Chi tiết (Events)](product_events.md) — Payload schema cho tất cả messages.

## 1. Gợi ý cấu trúc Base API Path
`[POST/GET/PUT/DELETE] /api/v1/products/...`

## 2. API: Tạo mới Sản Phẩm Bản Nháp (Create Concept)
**Endpoint:** `POST /api/v1/products`

Được gọi khi Seller bắt đầu quá trình list thông tin sản phẩm mới (Có thể chưa bán ngay).

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Idempotency Check (Chống chập):**
   - API lấy `Idempotency-Key` từ Header.
   - Thử `SETNX product:idempotency:{key}` với TTL ngắn (VD: 5 phút thay vì 24h để tránh kẹt khóa khi OOM/Crash).
   - Nếu tồn tại (Duplicate):
     - Kiểm tra xem Payload Response đã được lưu kèm hay chưa (trường hợp App Crash sau bước DB Commit nhưng trước khi SET XX response).
     - Nếu đã có response -> Trả về lập tức (`200 OK` hoặc theo cached status).
     - Nếu key có giá trị `processing` (không có body) -> Query xuống PostgreSQL bằng cột `idempotency_key` (trong bảng `products`). Nếu record thực sự đã tạo (khôi phục được `product_id`), phục hồi Response từ CSDL, cache lại bằng `SET XX` với TTL 24h, và trả về cho user. Nếu Record không tốn tại, báo `409 Conflict`.
   - **Lưu ý vòng đời (Sửa lỗi Race Condition Kinh Điển):** KHÔNG ĐƯỢC đặt tác vụ giải phóng (`DEL`) trong khối `finally { }` vì lỗi mạng sẽ lập tức xóa token, để cho request rác đi tiếp vào tạo Record trùng lặp.
   - 🔴 **CẢNH BÁO QUAN TRỌNG:** Trong khối `catch { }`, TUYỆT ĐỐI KHÔNG `DEL` khóa Idempotency một cách mù quáng (ví dụ khi gọi HTTP timeout nhưng DB đã commit). Việc giải phóng token chỉ được phép xảy ra trước vòng `BEGIN` của Database hoặc khi DB đã Rollback dứt điểm. Nếu không, seller Retry sẽ sinh ra 2 sản phẩm giống hệt nhau.
   - Khi thành công, sử dụng lệnh thay thế (`SET XX`) để cập nhật HTTP 201 Response vào token (bao gồm cả `product_id`) và kéo dài TTL lên 24h. TTL 5 phút ban đầu là chốt chặn tự hủy (Lease Time) nếu Pod bị Crash vật lý.
2. **[Redis/LocalCache] - Trích xuất & Xác minh Dữ Liệu Tham Khảo (Validation):**
   - Tìm kiếm `category_id` trong cây thư mục `product:categories:tree` (Caffeine Local Cache cực nhanh).
   - Kiểm tra xem Payload.Attributes gửi lên có chứa đủ các thuộc tính `is_required = true` theo Category đó không (Tra theo `product:category:{id}:attributes` trong Redis).
   - Nếu dữ liệu không hợp lệ → Return 400 Bad Request ngay mà không chạm DB.
3. **[PostgreSQL] - Ghi dữ liệu lõi & Outbox trong cùng Transaction:**
   - Mở Giao Dịch (`@Transactional`).
   - `INSERT INTO products` (Trạng thái: DRAFT, lưu kèm mã `idempotency_key` từ Header request).
   - `INSERT INTO product_skus` (Thêm các SKU con, `status` = ACTIVE).
   - `INSERT INTO product_outbox_events` (Sự kiện: [`ProductCreatedEvent`](product_events.md#31-product_created)). Đảm bảo `ProductCreatedEvent` được Promotion Service consume để gài đè luật Markdown nếu có chiến dịch đang chạy, lấp lỗ hổng giá ảo.
   - Đóng Giao Dịch (Commit).
4. **[Redis] - Lưu lại Cache cho API khác / Idempotency:**
   - Cập nhật Payload trả về vào key `product:idempotency:{key}`.
   - (Tùy chọn) Ghi tức thì `product:sku:{id}` vào Redis để sẵn sàng cho tác vụ Đọc Point-Read nội bộ.
5. **[Backend] - Trả HTTP 201 Created:**
   - Phản hồi JSON cho Seller.
6. **[Background] - Phát Event Đồng Bộ CQRS:**
   - **Debezium CDC Connector** thọc thẳng vào Write-Ahead Log (WAL) của PostgreSQL, chắt lọc dữ liệu table `product_outbox_events` tự động bắn tắp lự sang Kafka `t.product.events` cho Search Service. Loại bỏ hoàn toàn bottleneck của Outbox Polling Worker.

## 3. API: Sửa Đổi Thông Tin Sản Phẩm
**Endpoint:** `PUT /api/v1/products/{id}`

Sửa tên, giá tiền, hình ảnh, cập nhật tồn kho tĩnh, hoặc sửa SKU. Các bước xác thực tương tự như API POST (Check Idempotency, Check Validation Redis Cache).

**Điểm khác biệt:**
- Tiến trình trong Transaction:
   - `UPDATE products SET version = version + 1...` (Sử dụng Optimistic Locking, nếu `version` không khớp do Race Condition, throw Exception và huỷ giao dịch).
   - Tiến hành Upsert/Delete vào bảng `product_skus` (không có cột version độc lập).
   - Nếu Seller sửa `original_price`, **bắt buộc phải thực hiện truy vấn đồng bộ** (Synchronous Trigger) để đánh giá bảng `product_markdown_applied` trong cùng 1 Transaction. KÈM THEO: Bắt buộc lock theo đúng thứ tự chống Deadlock: Gọi `SELECT 1 FROM products WHERE id = ? FOR NO KEY UPDATE`, sau đó mới gọi `SELECT original_price FROM product_skus WHERE sku_id = ? FOR UPDATE` (Pessimistic Locking) để tránh Race Condition với Markdown Worker lúc tính toán lại giá. Nếu SKU đang có rule giảm giá active, hệ thống phải tự động tính toán lại `promotional_price` = `new_original_price * (1 - discount)` ngay lập tức để tránh lộ liễu giá sai. Xem [Markdown Integration](product_db_design.md#13-nhóm-tích-hợp-giá-khuyến-mãi-markdown-integration).
   - `INSERT` event [`ProductUpdatedEvent`](product_events.md#32-product_updated) vào Outbox.
   - *Lưu ý Markdown (Khuyến mãi):* Ngay sau khi lưu thành công sản phẩm hoặc SKU mới, Promotion Service sẽ consume event tạo mới để cấp lại event áp dụng Markdown cho SKU mới sinh ra (nếu category đang trong thời gian sale), gỡ bỏ lỗ hổng Missed Markdown.
   - Nếu giá thay đổi, `INSERT` thêm event [`ProductPriceUpdatedEvent`](product_events.md#34-product_price_updated) vào Outbox.
- **Tiến trình Invalidate Cache (Soft Invalidation - Chống Thundering Herd Strict):**
  - ⚠️ **NEVER use Hard DELETE (`DEL product:sku:{id}`)** - nó gây Cache Stampede khi hàng nghìn request đổ vào cùng lúc. 
  - **Thay thế bằng SET (Overwrite):** `SET product:sku:{sku_id} {fresh_json_value}` để đảm bảo luôn có dữ liệu, ngay cả khi Elasticsearch tạm sập.
  - **Fallback Safety:** Nếu Redis GET return null (cache bị evict), Application BUỘC phải:  
    1. Gọi DB SELECT (không được gọi ES vì có thể stale)
    2. Transform dữ liệu ở RAM
    3. SET lại Redis ngay lập tức với TTL 5 phút
  - **Không được phép Fallback → ES** vì dữ liệu đó lệch (Event có thể delay vài giây). Chỉ fallback → PostgreSQL (Source of Truth).
   - Thay vì gọi API xóa (`DEL`), [Backend] sẽ gửi tin nhắn đến **Invalidation Worker** để làm mới cache ngầm (Out-of-band).
   - [Trường hợp In-band] Tuyệt đối không dùng `DEL`. Worker/API sẽ chủ động trích xuất kết quả sau cập nhật, nén Snappy/Zstd và **ghi đè (`SET` / Overwrite)** lại khoá Redis bằng dữ liệu mới. Điều này duy trì trạng thái "Always-Warm" (Luôn Nóng) cho Cache, chặn nguy cơ Cache Stampede.

## 4. API: Chuyển Trạng Thái Sản Phẩm (Gửi Duyệt / Duyệt / Khoá Bài)
**Endpoint:** `PATCH /api/v1/products/{id}/status` hoặc `POST /api/v1/products/{id}/events`

Được gọi bởi Seller (DRAFT -> PENDING_REVIEW) hoặc Admin/System (PENDING_REVIEW -> ACTIVE). Áp dụng Spring State Machine.

**Quy trình chuẩn kiện (Workflow):**
1. **[Khoá Song Song Lock-Free] - LUA Scripting:**
   - Thay vì dùng Distributed Lock Redisson chiếm băng thông mạng, chuyển đổi logic kiểm tra Guard (VD: check trạng thái hiện tại phải là DRAFT) và cập nhật qua **LUA Script trên Redis**, hoặc phó thác hoàn toàn cho **Optimistic Locking cấp Database** `UPDATE status = newStatus WHERE code = :id AND version = :v`. Không cần dùng khóa mềm Redisson dễ deadlock.
2. **[Database & FSM] - Kích hoạt Logic Lõi:**
   - Lấy Record `products` từ Postgres (Theo ID).
   - Truyền Command vào State Machine (`productStateMachine.sendEvent(command, product)`).
   - FSM kiểm tra điều kiện rẽ nhánh (Guard Conditions) (VD: nếu `command=APPROVE` thì state hiện tại bắt buộc phải là `PENDING_REVIEW`). Xem [State Machine Docs](product_state_machine.md).
   - Nếu thỏa mãn, chuyển sang State mới.
3. **[Database Giao Dịch Đồng Thời] - Lưu Trữ Tình Trạng:**
   - `@Transactional`: `UPDATE products SET status = newStatus, version = version + 1`.
   - Nếu command là `RE_ACTIVATE`, xử lý bằng 1 nhánh atomic:
     - `UPDATE products SET seller_intention_hidden = false WHERE id = :id`.
     - `SELECT COALESCE(SUM(display_quantity), 0) FROM product_skus WHERE product_id = :id FOR UPDATE`.
     - Chỉ đổi `status = ACTIVE` khi tổng tồn > 0. Nếu tổng tồn = 0 thì giữ nguyên `status = HIDDEN` nhưng vẫn lưu `seller_intention_hidden = false` để chờ inventory event hoặc worker reevaluate.
     - Đồng thời phát event nội bộ `PRODUCT_VISIBILITY_REEVALUATE_REQUESTED` để worker đánh thức lại Product nếu dữ liệu tồn kho đã có sẵn nhưng chưa có event mới.
   - Lệnh lưu log kiểm toán vào `product_status_history`.
   - Phát sinh Outbox Event: [`ProductStateChangedEvent`](product_events.md#33-product_state_changed).
4. **[Khoá] - Giải phóng Lock:**
   - (Bỏ qua nếu đã dùng Lua Script hoặc Optimistic Locking).
5. **[Background] - CQRS & Cảnh Báo:**
   - Search Service nhận `ProductStateChangedEvent` -> Ẩn sp khỏi Elasticsearch nếu `status` -> `BANNED`.
   - Notification Service gửi Mail cho Seller.

## 5. API: Point-Read (Đọc Chính Xác / Nhanh)
**Endpoint / Giao thức:** Thực hiện qua **gRPC (Protocol Buffers)** thay vì REST/HTTP JSON tốn kém. Tên hàm gRPC: `GetProductSku`.

Dành riêng cho Mạng Lưới Nội Bộ nội vùng (Cart Service, Order Service, Promotion Service). Đòi hỏi tính chính xác tột độ liên quan đến Giá (`original_price`, `promotional_price`) hiện tại và có mua được không (`status` ACTIVE). Tuyệt đối **KHÔNG** đọc từ Search Service vì Event có thể delay vài giây.

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Fetch Data (Primary Read):**
   - Gọi `GET product:sku:{id}` (Dữ liệu đã được nén Snappy/Zstd).
   - Áp dụng **Probabilistic Early Expiration (PER)**: Thuật toán xổ số ngẫu nhiên. Nếu TTL còn < 5%, 1 HTTP Request hên xui duy nhất sẽ tự nguyện ở lại background tải dữ liệu mới từ Database và ghi đè khoá, giúp refresh Cache êm ái mà không đợi nó quá hạn. Trả về kết quả cho Client ngay.
2. **[Fallback] - Xử lý Cache Miss & Singleflight (Request Coalescing):**
   - Nếu `GET` trả null (Hoặc Server mới boot):
   - **[Chống Cache Stampede bằng Singleflight]**: Hàng nghìn concurrent requests đòi lấy 1 `sku_id` sẽ bị giữ lại (holding) ở RAM của Node ứng dụng. Chỉ cho phép **Đúng 1 Request** được mở kết nối DB hoặc Redis Lock.
   - 1 Request tiên phong sẽ query DB Postgres bảng `product_skus` JOIN `products`.
   - Nạp ngược lại `SET product:sku:{id} compressed_payload EX 3600` lên Redis.
   - Ném kết quả trả về cho hàng nghìn requests đang bị giữ chân ở block Singleflight.

**Endpoint Batch:** `gRPC GetBatchProductSkus`

Dành cho Cart Service cần lấy N SKU cùng lúc (hiển thị giỏ hàng).

**Quy trình:**
1. Nhận danh sách `sku_ids` từ Protobuf Request.
2. Lên Redis gọi `MGET product:sku:{id1} product:sku:{id2} ...` — lấy tất cả trong 1 round-trip. Khử nén đồng loạt.
3. Chạy Singleflight cho những SKU bị cache miss (nếu có), query bù trên DB.
4. Nạp ngược cache bằng Soft Invalidation.
5. Serialize Protobuf và rẩy về cho Cart Service.

## 6. Workflow: Xử Lý Markdown Event từ Promotion Service
**Trigger:** Kafka Event [`MARKDOWN_CREATED`](product_events.md#41-markdown_created-từ-promotion-service) từ Topic `t.promotion.events`.
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#5-markdown-price-calculator-worker).

Đây là luồng **không đi qua API** mà chạy hoàn toàn ngầm (Background). Xem chi tiết kiến trúc tại [Promotion DB Design - Zero-Latency](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).

**Quy trình chuẩn kiện (Workflow):**
1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-markdown-group`) nhận `MARKDOWN_CREATED` event.
   - Parse payload: `rule_id`, `scope_type`, `ref_id`, `discount_type`, `discount_value`, `max_discount_amount`, `end_time`.
2. **[PostgreSQL] - Trích xuất SKU bị ảnh hưởng:**
   - Nếu `scope_type = CATEGORY`: `SELECT sku_id, original_price FROM product_skus ps JOIN products p ON ps.product_id = p.product_id WHERE p.category_id = :ref_id AND p.status = 'ACTIVE' AND ps.status = 'ACTIVE'`.
   - Nếu `scope_type = PRODUCT`: Query theo `product_id = ref_id`.
3. **[RAM] - Tính toán Batch giá khuyến mãi:**
   - Với mỗi SKU: `promotional_price = original_price * (1 - percentage / 100)` hoặc `original_price - fixed_amount`.
   - Áp dụng `max_discount_amount` nếu có.
4. **[PostgreSQL] - Ghi dữ liệu & Tránh Deadlock Liên Bảng (Concurrency Guard):**
   - **Cảnh báo Race Condition:** Tuyệt đối không tung một lệnh `UPDATE product_skus` khổng lồ theo diện rộng (Category). Nếu đụng độ với luồng Seller Update API (Lock `products` -> `product_skus`), sẽ lập tức gây Deadlock chéo.
   - Worker BẮT BUỘC phải chia theo nhóm `product_id` và ORDER BY `product_id` ASC. Với mỗi khối `product_id`, mở `@Transactional` độc lập:
     - Khóa cha: `SELECT 1 FROM products WHERE id = :product_id FOR NO KEY UPDATE`.
     - **UPDATE PRODUCTS**: `UPDATE products SET version = version + 1 WHERE id = :product_id`. Cập nhật `version` của Aggregation Root trước để trigger event bắt chính xác.
     - Cập nhật con: `UPDATE product_skus SET promotional_price = :calc, updated_at = NOW() WHERE product_id = :product_id AND sku_id IN (...)`.
     - Tracking: `INSERT INTO product_markdown_applied` (tracking rule → SKU mapping).
     - Outbox: `INSERT INTO product_outbox_events` cho mỗi SKU: [`ProductPriceUpdatedEvent`](product_events.md#34-product_price_updated) với `change_source = MARKDOWN_APPLIED`.
     - COMMIT Transaction để nhả cọc cho Product kế tiếp. Mất thêm milisecond vòng lặp nhưng an toàn hệ thống tuyệt đối 100%.
5. **[Worker] - Invalidate Cache (Soft Invalidation):**
   - Thông qua Outbox Event, Worker thực hiện bóc tách payload khuyến mãi mới cập nhật, và chủ động OVERWRITE (ghi đè mềm) bằng lệnh `SET product:sku:{id}` dữ liệu (đã compress Snappy) thay vì ngây ngô gọi `DEL` để tránh tạo lỗ hổng Cache Stampede.
   - `SET product:markdown:active:{sku_id}` với TTL = thời gian còn lại của rule.
6. **[Background] - Phát Event CQRS:**
   - Outbox Worker gắp `ProductPriceUpdatedEvent` → Kafka → Search Service cập nhật giá hiển thị trên Elasticsearch.

> **Lưu ý khi `MARKDOWN_ENDED`:** Luồng tương tự nhưng ngược chiều — query `product_markdown_applied WHERE rule_id = :id` → reset `promotional_price = NULL` → delete mapping → phát event.

## 7. Workflow: Xử Lý Inventory Sync Event
**Trigger:** Kafka Event [`INVENTORY_QUANTITY_CHANGED`](product_events.md#43-inventory_quantity_changed-từ-inventory-service) từ Topic `t.inventory.events`.
**Xử lý bởi:** [Inventory Sync Worker](product_workers.md#6-inventory-sync-worker).

**Quy trình chuẩn kiện (Workflow):**
1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-inventory-sync-group`) nhận `INVENTORY_QUANTITY_CHANGED`.
   - Parse payload: `sku_id`, `available_quantity`.
2. **[PostgreSQL] - Cập nhật Display Quantity:**
   - `@Transactional`:
       - Bước 1: `SELECT 1 FROM products WHERE id = :product_id FOR NO KEY UPDATE`.
       - Bước 2: `SELECT sku_id, status, display_quantity FROM product_skus WHERE product_id = :product_id FOR UPDATE`.
       - Bước 3: `UPDATE product_skus SET display_quantity = :available_quantity WHERE sku_id = :sku_id`.
       - Bước 4: Chỉ tăng `products.version` khi có thay đổi trạng thái SKU hoặc trạng thái Product (tránh bơm version rác).
3. **[Logic] - Kiểm tra Auto-Hide / Auto-Reactivate chống Race Condition:**
    - **BẮT BUỘC sử dụng Row-Level Lock** kiểu Pessimistic (`FOR NO KEY UPDATE` + `FOR UPDATE`) và giữ lock xuyên suốt toàn bộ chuỗi Đọc SKU -> Tính toán -> Update, tuyệt đối không tách thành nhiều transaction con.
   - Nếu `available_quantity = 0` và SKU đang `ACTIVE`:
     - Chuyển `product_skus.status = 'OUT_OF_STOCK'`. Đồng thời BẮT BUỘC phải thực thi lệnh `UPDATE products SET version = version + 1 WHERE id = :product_id` để tăng version của bảng cha, nếu không Search Service sẽ từ chối Event này do phiên bản cũ.
     - Phát [`ProductSkuStatusChangedEvent`](product_events.md#35-product_sku_status_changed) vào Outbox.
     - Sau khi lưu trạng thái SKU vào DB, kiểm tra lại tổng: **Tất cả** SKU của Product này đều đã mang `status = OUT_OF_STOCK`? → Nếu đúng, Trigger [AUTO_HIDE_OUT_OF_STOCK](product_state_machine.md#22-transitions-do-systemworker-tự-động-trigger) trên Product cha.
   - Nếu `available_quantity > 0` và SKU đang `OUT_OF_STOCK`:
     - Chuyển `product_skus.status = 'ACTIVE'`. Đồng thời BẮT BUỘC phải thực thi lệnh `UPDATE products SET version = version + 1 WHERE id = :product_id` để tăng version của bảng cha.
     - Phát `ProductSkuStatusChangedEvent` vào Outbox.
       - **PHÒNG CHỐNG Lỗ hổng TOCTOU (Time-of-Check to Time-of-Use) & Override Ý định Seller (ATOMIC):** Khi SKU chuyển từ `OUT_OF_STOCK` → `ACTIVE`, cần kiểm tra kết hợp `status = 'HIDDEN' AND seller_intention_hidden = false` (MỆNH ĐỀ KÉP). **Cơ chế Thực thi (Atomicity Guarantee):** Phải sử dụng SQL Query đơn nguyên: `SELECT 1 FROM products WHERE id = :product_id AND status = 'HIDDEN' AND seller_intention_hidden = false FOR NO KEY UPDATE;` TRƯỚC trigger AUTO_REACTIVATE_IN_STOCK. Query check này BẮT BUỘC:
  - Đặt SAU Row-Level Lock đã acquire ở Bước 1 (cùng Transaction)
  - Chạy NGAY TRONG Transaction để lock toàn bộ read + write của cả hai cột
  - Nếu query không return row, hủy trigger (Seller ý định ẩn)
  - Nếu return row, trigger AUTO_REACTIVATE_IN_STOCK kèm tăng `version`
  - Điều này triệt tiêu Race Condition nơi Seller đổi `seller_intention_hidden` giữa thời điểm check và update.
   - **Đồng bộ hóa phiên bản:** Giữ cho State luôn nhất quán. Giải phóng Pessimistic Lock tự động khi hoàn tất DB Transaction (Commit).
   - **Chống Hố Đen sau khi Seller mở cờ thủ công:** Khi có event nội bộ `PRODUCT_VISIBILITY_REEVALUATE_REQUESTED`, worker bắt buộc chạy lại guard `status = HIDDEN AND seller_intention_hidden = false AND EXISTS(sku in_stock)` trong 1 transaction. Nếu đúng thì trigger `AUTO_REACTIVATE_IN_STOCK` dù không có inventory event mới.
4. **[Redis] - Update Cache (Chống Read-After-Write Inconsistency):**
   - Không được dùng một lệnh cắm `SELECT` rời rạc khác sau khi Commit vì sẽ làm tăng kết nối và sinh rủi ro đọc nhầm State do Replicate-lag (nếu trúng Read-Replica). Worker nên tự tổng hợp hoặc dùng `RETURNING` để có kết quả vừa Commit.
   - Bắn message thay đổi bằng lệnh Partial Update (VD: `JSON.SET product:sku:{sku_id} .display_quantity 40` hoặc `HSET`) sang Redis.
   - Tuyệt đối TRÁNH mô hình Read-Modify-Write nguyên thủy trên Redis Client (`GET json -> sửa ram -> SETXX json`) vì nếu 2 worker chạy đè lên nhau sẽ sinh ra "Stale Cache" (Tồn kho Redis khác Tồn kho DB).

## 8. API: Xóa Mềm Sản Phẩm (Soft Delete)
**Endpoint:** `DELETE /api/v1/products/{id}`

Chỉ cho phép xoá sản phẩm ở trạng thái `DRAFT`, `HIDDEN`, hoặc `REJECTED`. Tuyệt đối KHÔNG xóa `ACTIVE` (phải HIDE trước).

**Quy trình chuẩn kiện (Workflow):**
1. **[Validation] - Kiểm tra trạng thái hiện tại:**
   - Nếu `status` không thuộc `{DRAFT, HIDDEN, REJECTED}` → Return 400 Bad Request.
2. **[Database] - Đánh cờ xóa mềm và Dọn dẹp rác (Cascade Cleanup):**
   - Sử dụng **Optimistic Locking** ở tầng Database để đồng nhất kiến trúc (Lock-Free) với APIs chuyển trạng thái, thay vì sử dụng Distributed Lock.
   - `@Transactional`:
     - Cập nhật cờ khóa mềm: `UPDATE products SET is_deleted = true, version = version + 1 WHERE id = :id AND version = :current_version`. (Lưu ý, thư viện FSM không có trạng thái `DELETED`, chỉ lưu cờ `is_deleted` để giữ nguyên trạng thái cũ (ví dụ đang HIDDEN mà bị xóa), dễ phục vụ luồng Restore sau này).
     - Nếu số dòng update = 0, ném `StaleObjectStateException` để Controller retry.
     - **Dọn dẹp Orphan Data**: `DELETE FROM product_markdown_applied WHERE product_id = :id` để cắt đứt mọi khuyến mãi đang dính vào sản phẩm này, tránh rác dữ liệu tính toán.
     - `INSERT INTO product_outbox_events` ([`ProductDeletedEvent`](product_events.md#36-product_deleted)) để trigger quá trình async cleanup.
3. **[Worker] - Soft Invalidate Cache:**
   - Thông qua In-house worker, bắt giữ DB event và bắn ngầm lệnh `SET product:sku:{sku_id}` cập nhật đè trạng thái `is_deleted = true` cho tất cả SKU thuộc Product để duy trì triết lý Always-Warm. Không được dùng `DEL` sinh bão truy vấn DB.
   - Thao tác Overwrite tương tự cho `SET product:master:{product_id}`.
4. **[Background] - CQRS Cleanup:**
   - Search Service nhận event → xoá Product khỏi Elasticsearch index.
   - Cart Service nhận event → xoá sản phẩm khỏi giỏ hàng đang active.
