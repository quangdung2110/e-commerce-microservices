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
     - **[Xử lý Data Race]:** Nếu key có giá trị `processing` (Transaction thứ nhất chưa flush xong DB), luồng thứ 2 **TUYỆT ĐỐI CẤM** lao xuống PostgreSQL để đọc bảng `products` bằng cột `idempotency_key`, vì ở Isolation Level là Read-Committed, query DB sẽ nhận được bảng RỖNG và lập tức sinh ra Miss/409 Conflict. Giải pháp chuẩn xác là luồng thứ hai phải BẮT BUỘC thực hiện thủ thuật `Thread.sleep(150ms)` và vào lại vòng lặp `GET Redis`. Vòng lặp Retry này kéo dài tối đa 500ms trước khi timeout, đủ để Transaction 1 kết thúc và Flush dữ liệu an toàn.
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
   - `INSERT INTO product_outbox_events` (Sự kiện: [`ProductCreatedEvent`](product_events.md#31-product_created)). **[Sửa Đổi Kiến Trúc CQRS]:** Tuyệt đối KHÔNG ĐƯỢC để Promotion Service consume sự kiện `ProductCreatedEvent` này. Sản phẩm vừa tạo ở trạng thái DRAFT hoàn toàn chưa được xuất hiện hệ thống, việc gài đè luật Markdown lúc này là phi lý. Chờ khi sản phẩm lật sang trạng thái `ACTIVE` qua sự kiện FSM, hệ thống mới bắt đầu kết dính giá trị Markdown.
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
   - Tiến hành Upsert/Delete vào bảng `product_skus` (không có cột version độc lập). **[M6 Bổ sung]**: Nếu thao tác là Xóa/Delete 1 SKU khỏi Product chứa nhiều SKUs khác, BẮT BUỘC Update cờ `is_deleted = true`, cập nhật `deleted_at`, và `INSERT` thêm event [`ProductSkuDeletedEvent`](product_events.md#38-product_sku_deleted) vào Outbox trong cùng transaction.
   - Nếu Seller sửa `original_price`, **bắt buộc phải thực hiện truy vấn đồng bộ** (Synchronous Trigger) để đánh giá bảng `product_markdown_applied` trong cùng 1 Transaction. KÈM THEO: Bắt buộc lock theo đúng thứ tự chống Deadlock: Gọi `SELECT 1 FROM products WHERE product_id = ? FOR NO KEY UPDATE`, sau đó mới gọi `SELECT original_price FROM product_skus WHERE sku_id = ? FOR UPDATE` (Pessimistic Locking). Nếu SKU đang có rule giảm giá active, hệ thống tự động tính toán lại `promotional_price`. KÈM ĐÓ: BẮT BUỘC phải ra lệnh `UPDATE product_markdown_applied SET original_price_snapshot = :new_original_price` để chống Data Drift (trôi dữ liệu giá giữa lịch sử Audit Markdown và bảng thực tế).
   - `INSERT` event [`ProductUpdatedEvent`](product_events.md#32-product_updated) vào Outbox.
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
   - Thay vì dùng Distributed Lock Redisson chiếm băng thông mạng, chuyển đổi logic kiểm tra Guard (VD: check trạng thái hiện tại phải là DRAFT) và cập nhật qua **LUA Script trên Redis**, hoặc phó thác hoàn toàn cho **Optimistic Locking cấp Database** `UPDATE products SET status = :new_status WHERE product_id = :product_id AND version = :v`. Không cần dùng khóa mềm Redisson dễ deadlock.
2. **[Database & FSM] - Kích hoạt Logic Lõi:**
   - Lấy Record `products` từ Postgres (Theo ID).
   - Truyền Command vào State Machine (`productStateMachine.sendEvent(command, product)`).
   - FSM kiểm tra điều kiện rẽ nhánh (Guard Conditions) (VD: nếu `command=APPROVE` thì state hiện tại bắt buộc phải là `PENDING_REVIEW`). Xem [State Machine Docs](product_state_machine.md).
   - Nếu thỏa mãn, chuyển sang State mới.
3. **[Database Giao Dịch Đồng Thời] - Lưu Trữ Tình Trạng:**
   - `@Transactional`: `UPDATE products SET status = newStatus, version = version + 1`.
   - Nếu command là `RE_ACTIVATE`, xử lý bằng 1 nhánh atomic:
       - `UPDATE products SET seller_intention_hidden = false WHERE product_id = :product_id`.
       - `SELECT COALESCE(SUM(display_quantity), 0) FROM product_skus WHERE product_id = :product_id FOR UPDATE`.
     - Chỉ đổi `status = ACTIVE` khi tổng tồn > 0. Nếu tổng tồn = 0 thì giữ nguyên `status = HIDDEN` nhưng vẫn lưu `seller_intention_hidden = false` để chờ inventory event hoặc worker re-evaluate.
     - Việc đánh thức trạng thái Product (`ACTIVE` hay `HIDDEN`) đã được quyết định ngay lập tức trong Transaction này dựa trên kết quả của hàm `SUM(display_quantity)`, triệt tiêu hoàn toàn nhu cầu sử dụng Worker bất đồng bộ chạy lại logic này.
   - Lệnh lưu log kiểm toán vào `product_status_history`.
   - Phát sinh Outbox Event: [`ProductStateChangedEvent`](product_events.md#33-product_state_changed).
4. **[Khoá] - Giải phóng Lock:**
   - (Bỏ qua nếu đã dùng Lua Script hoặc Optimistic Locking).
5. **[Background] - CQRS & Cảnh Báo:**
   - Search Service nhận `ProductStateChangedEvent` -> Ẩn sp khỏi Elasticsearch nếu `status` -> `BANNED`.
   - Notification Service gửi Mail cho Seller.
   - **Tường minh Cache Invalidation:** Khi bản tin outbox `PRODUCT_STATE_CHANGED` kích hoạt, Kafka in-house Invalidation Worker CHẮC CHẮN phải thực thi lệnh Override ghi đè trạng thái `status` trên Redis Cache cho Thẻ Phụ (`product:sku:{sku_id}`) và Thẻ Chính (`product:master:{product_id}`). Việc này được thực thi mượt mà vì mảng truyền đi `sku_ids` ĐÃ MẶC ĐỊNH tích hợp trong event, không tốn chút Select nào tại DB. Dễ dàng ngăn chặn hành vi mua hàng ở Cart ngay tắp lự.

## 5. API: Point-Read (Đọc Chính Xác / Nhanh)
**Endpoint / Giao thức:** Thực hiện qua **gRPC (Protocol Buffers)** thay vì REST/HTTP JSON tốn kém. Tên hàm gRPC: `GetProductSku`.

Dành riêng cho Mạng Lưới Nội Bộ nội vùng (Cart Service, Order Service, Promotion Service). Đòi hỏi tính chính xác tột độ liên quan đến Giá (`original_price`, `promotional_price`) hiện tại và có mua được không (`status` ACTIVE). Tuyệt đối **KHÔNG** đọc từ Search Service vì Event có thể delay vài giây.

**Hợp đồng phản hồi tối thiểu cho `GetProductSku`/`GetBatchProductSkus`:**
- `sku_id`, `product_id`, `sku_code`
- `product_name`, `seller_id`, `seller_name`
- `original_price`, `promotional_price`, `best_price`
- `sku_status`, `product_status`, `is_available`
- `display_quantity`, `variant_attributes`
- `main_image_url`, `product_updated_at`

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Fetch Data (Primary Read):**
   - Gọi `GET product:sku:{id}` (Dữ liệu đã được nén Snappy/Zstd).
   - Áp dụng **Probabilistic Early Expiration (PER)**: Thuật toán xổ số ngẫu nhiên. Nếu TTL còn < 5%, 1 HTTP Request hên xui duy nhất sẽ tự nguyện ở lại background tải dữ liệu mới từ Database và ghi đè khoá, giúp refresh Cache êm ái mà không đợi nó quá hạn. Trả về kết quả cho Client ngay.
2. **[Fallback] - Xử lý Cache Miss & Singleflight (Request Coalescing):**
   - Nếu `GET` trả null (Hoặc Server mới boot):
   - **[P1/H3 Tối Ưu: Cross-Pod Singleflight]**: Việc chỉ dùng In-process Singleflight (Guava/singleflight) là chưa đủ để chống bão ở cấp Cluster (20 Pods vẫn sinh ra 20 DB hits). Để khắc phục triệt để, Request tiên phong của Pod phải thử chiếm khóa phân tán `SETNX product:lock:fetch:{id} 1 EX 2`. 
   - Nếu TRÚNG LOCK: Pod được phép query DB Postgres bảng `product_skus` JOIN `products`, sau đó nạp ngược `SET product:sku:{id} compressed_payload EX 3600` đồ sộ lên Redis, rồi giải phóng lock.
   - Nếu TRƯỢT LOCK: Pod Sleep tĩnh 50ms rồi quay lại vòng lặp `GET product:sku:{id}` (vì Pod trúng lock đã query thực tế và nạp cache xong). Cơ chế này giúp triệt tiêu 95% DB Load dư thừa.

**Endpoint Batch:** `gRPC GetBatchProductSkus`

Dành cho Cart Service cần lấy N SKU cùng lúc (hiển thị giỏ hàng). **[P7 Kiến trúc Resilience]**: Kiến trúc này đã đẩy rủi ro về phía Cart (Pull Model). Phía Cart Service BẮT BUỘC phải bọc lời gọi gRPC này bằng một **Circuit Breaker** (Ví dụ: Resilience4j). Khi Circuit Breaker bị bung/mở (Open State) do Product Service quá tải/timeout quá 5 lần, Cart Service phải kích hoạt luồng Fallback: trả về những SKU đang có sẵn trong bộ nhớ Memory Cache của chính Cart, các SKU bị thiếu thông tin sẽ mang nhãn "Đang cập nhật..." trên giao diện trả về, tuyệt đối không được sập/block quá trình xem giỏ hàng của End-user.

**Quy trình:**
1. Nhận danh sách `sku_ids` từ Protobuf Request.
2. Lên Redis gọi `JSON.MGET product:sku:{id1} product:sku:{id2} ... .` — lấy tất cả trong 1 round-trip. Khử nén đồng loạt.
3. Chạy Singleflight: **[Sửa Lỗi Batch Singleflight Mù]**: Lệnh chạy Singleflight TUYỆT ĐỐI KHÔNG gói gọn toàn chuỗi batch truyền vào. Nó phải đi vào cấu trúc FOR LOOP xé mảng ra và gọi In-Process Singleflight chọc Database **riêng rẽ cho TỪNG cái `sku_id` bị miss**. Nhờ đó tận dụng tối đa 99% hit rate cục bộ giữa các Pods trên Cluster.
4. **[P5 Tối Ưu]** Nạp ngược cache bằng Soft Invalidation: Bắt buộc sử dụng kỹ thuật **Redis Pipelining** để gom tất cả các lệnh `JSON.SET product:sku:{id}` (cho 8-10 SKUs vừa miss) vào 1 mạng round-trip duy nhất, giảm triệt để N lần network I/O latency.
5. Serialize Protobuf và rẩy về cho Cart Service.

## 6. Workflow: Xử Lý Markdown Event từ Promotion Service
**Trigger:** Kafka Event `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` từ Topic `t.promotion.markdown.events`.
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#5-markdown-price-calculator-worker).

Đây là luồng **không đi qua API** mà chạy hoàn toàn ngầm (Background). Xem chi tiết kiến trúc tại [Promotion DB Design - Zero-Latency](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).

**Quy trình chuẩn kiện (Workflow):**
1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-markdown-group`) nhận sự kiện Cập Nhật.
   - Parse payload phù hợp cho `MARKDOWN_CREATED`, `MARKDOWN_UPDATED` hoặc `MARKDOWN_ENDED`.
2. **[PostgreSQL] - Trích xuất SKU bị ảnh hưởng:**
   - Nếu `scope_type = CATEGORY`: `SELECT sku_id, original_price FROM product_skus ps JOIN products p ON ps.product_id = p.product_id WHERE p.category_id = :ref_id AND p.status = 'ACTIVE' AND ps.status = 'ACTIVE'`.
   - Nếu `scope_type = PRODUCT`: Query theo `product_id = ref_id`.
3. **[RAM] - Tính toán Batch giá khuyến mãi:**
   - Dữ liệu query ở bước 2 là danh sách phẳng có thể lẫn SKU của nhiều Product, vì vậy Worker phải group theo `product_id` ở RAM (HashMap hoặc iterator grouping).
   - Với tập lớn, ưu tiên streaming/chunk theo `ORDER BY product_id ASC, sku_id ASC` để không phình bộ nhớ.
   - Với mỗi SKU: `promotional_price = original_price * (1 - percentage / 100)` hoặc `original_price - fixed_amount`.
   - Áp dụng `max_discount_amount` nếu có.
4. **[PostgreSQL] - Ghi dữ liệu & Tránh Deadlock Liên Bảng (Concurrency Guard):**
   - **Cảnh báo Race Condition:** Tuyệt đối không tung một lệnh `UPDATE product_skus` khổng lồ theo diện rộng (Category). Nếu đụng độ với luồng Seller Update API (Lock `products` -> `product_skus`), sẽ lập tức gây Deadlock chéo.
   - Worker BẮT BUỘC phải chia theo nhóm `product_id` và ORDER BY `product_id` ASC. Với mỗi khối `product_id`, mở `@Transactional` độc lập:
       - Khóa cha: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE`.
          - **UPDATE PRODUCTS**: `UPDATE products SET version = version + 1 WHERE product_id = :product_id`. Cập nhật `version` của Aggregation Root trước để trigger event bắt chính xác.
       - Cập nhật con: batch `UPDATE product_skus SET promotional_price = :calc, updated_at = NOW() WHERE product_id = :product_id AND sku_id IN (...)`.
       - Tracking: batch upsert `product_markdown_applied` (tracking rule → SKU mapping).
       - Outbox: batch insert `product_outbox_events` cho các SKU bị ảnh hưởng, chứa [`ProductPriceUpdatedEvent`](product_events.md#34-product_price_updated) với `change_source = MARKDOWN_APPLIED`.
     - COMMIT Transaction để nhả cọc cho Product kế tiếp. Mất thêm milisecond vòng lặp nhưng an toàn hệ thống tuyệt đối 100%.
5. **[Worker] - Invalidate Cache (Soft Invalidation):**
    - Sau mỗi lần commit của từng Product, Worker thực hiện cache update theo batch bằng Redis pipeline thay vì gửi lệnh lẻ.
    - Thông qua Outbox Event, Worker chủ động OVERWRITE (ghi đè mềm) `SET/JSON.SET product:sku:{id}` và `SET product:markdown:active:{sku_id}` với TTL theo thời gian còn lại của rule.
    - Với `MARKDOWN_ENDED` do tắt thủ công, đưa các lệnh `DEL product:markdown:active:{sku_id}` vào cùng pipeline batch để đồng bộ tức thời.
6. **[Background] - Phát Event CQRS:**
   - Debezium CDC Connector forward `ProductPriceUpdatedEvent` từ outbox sang Kafka → Search Service cập nhật giá hiển thị trên Elasticsearch.

> **Lưu ý khi `MARKDOWN_UPDATED` / `MARKDOWN_ENDED`:** Luồng tương tự áp dụng cho việc lấy danh sách SKU từ bảng `product_markdown_applied`. Đặc thù với UPDATE, Worker BẮT BUỘC query lại `original_price` live từ bảng `product_skus`, KHÔNG DÙNG snapshot rác cũ; tiếp theo tính toán sửa lại DB rồi tung outbox bình thường. Reset Null Price nếu là event ENDED.

## 7. Workflow: Xử Lý Inventory Sync Event
**Trigger:** Kafka Event [`INVENTORY_QUANTITY_CHANGED`](product_events.md#44-inventory_quantity_changed-từ-inventory-service) từ Topic `t.inventory.events`.
**Xử lý bởi:** [Inventory Sync Worker](product_workers.md#6-inventory-sync-worker).

**Quy trình chuẩn kiện (Workflow):**
1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-inventory-sync-group`) nhận `INVENTORY_QUANTITY_CHANGED`.
   - Parse payload bắt buộc: `product_id`, `sku_id`, `available_quantity`, `event_timestamp`.
2. **[PostgreSQL] - Cập nhật Display Quantity:**
   - `@Transactional`:
       - Bước 1: `SELECT product_id, status, seller_intention_hidden FROM products WHERE product_id = :product_id FOR NO KEY UPDATE`.
       - Bước 2: `SELECT sku_id, product_id, status, last_inventory_sync_at FROM product_skus WHERE sku_id = :sku_id AND product_id = :product_id FOR UPDATE`.
       - Bước 2.1: Nếu không có row ở Bước 2, coi là payload sai cặp `product_id/sku_id` -> dừng apply, đưa message vào DLQ/parking-lot để điều tra producer contract.
       - Bước 3: **Out-of-order guard:** chỉ apply nếu `event_timestamp > last_inventory_sync_at`.
       - Bước 4: `UPDATE product_skus SET display_quantity = :available_quantity, last_inventory_sync_at = :event_timestamp WHERE sku_id = :sku_id`.
       - Bước 5: Cập nhật `products.version = version + 1` khi có thay đổi cần phản ánh xuống các read models.
3. **[Logic] - Kiểm tra Auto-Hide / Auto-Reactivate chống Race Condition:**
   - **BẮT BUỘC sử dụng Row-Level Lock** kiểu Pessimistic (`FOR NO KEY UPDATE` + `FOR UPDATE`) và giữ lock xuyên suốt toàn bộ chuỗi Đọc Product -> Đọc SKU -> Tính toán -> Update, tuyệt đối không tách thành nhiều transaction con.
   - Nếu `available_quantity = 0` và SKU đang `ACTIVE`:
   - Chuyển `product_skus.status = 'OUT_OF_STOCK'`. Đồng thời BẮT BUỘC phải thực thi lệnh `UPDATE products SET version = version + 1 WHERE product_id = :product_id` để tăng version của bảng cha, nếu không Search Service sẽ từ chối Event này do phiên bản cũ.
     - Phát [`ProductSkuStatusChangedEvent`](product_events.md#35-product_sku_status_changed) vào Outbox.
     - Sau khi lưu trạng thái SKU vào DB, kiểm tra lại tổng: **Tất cả** SKU của Product này đều đã mang `status = OUT_OF_STOCK`? → Nếu đúng, Trigger [AUTO_HIDE_OUT_OF_STOCK](product_state_machine.md#22-transitions-do-systemworker-tự-động-trigger) trên Product cha.
   - Nếu `available_quantity > 0` và SKU đang `OUT_OF_STOCK`:
   - Chuyển `product_skus.status = 'ACTIVE'`. Đồng thời BẮT BUỘC phải thực thi lệnh `UPDATE products SET version = version + 1 WHERE product_id = :product_id` để tăng version của bảng cha.
     - Phát `ProductSkuStatusChangedEvent` vào Outbox.
      - **PHÒNG CHỐNG Lỗ hổng TOCTOU (Time-of-Check to Time-of-Use) & Override Ý định Seller (ATOMIC):** Khi SKU chuyển từ `OUT_OF_STOCK` → `ACTIVE`, cần kiểm tra kết hợp `status = 'HIDDEN' AND seller_intention_hidden = false` (MỆNH ĐỀ KÉP). **Cơ chế Thực thi (Atomicity Guarantee):** Phải sử dụng SQL Query đơn nguyên: `SELECT 1 FROM products WHERE product_id = :product_id AND status = 'HIDDEN' AND seller_intention_hidden = false FOR NO KEY UPDATE;` TRƯỚC trigger AUTO_REACTIVATE_IN_STOCK. Query check này BẮT BUỘC:
  - Đặt SAU Row-Level Lock đã acquire ở Bước 1 (cùng Transaction)
  - Chạy NGAY TRONG Transaction để lock toàn bộ read + write của cả hai cột
  - Nếu query không return row, hủy trigger (Seller ý định ẩn)
  - Nếu return row, trigger AUTO_REACTIVATE_IN_STOCK kèm tăng `version`
  - Điều này triệt tiêu Race Condition nơi Seller đổi `seller_intention_hidden` giữa thời điểm check và update.
   - **Đồng bộ hóa phiên bản:** Giữ cho State luôn nhất quán. Giải phóng Pessimistic Lock tự động khi hoàn tất DB Transaction (Commit).

4. **[Redis] - Update Cache (Chống Read-After-Write Inconsistency):**
   - Không được dùng một lệnh cắm `SELECT` rời rạc khác sau khi Commit vì sẽ làm tăng kết nối và sinh rủi ro đọc nhầm State do Replicate-lag (nếu trúng Read-Replica). Worker nên tự tổng hợp hoặc dùng `RETURNING` để có kết quả vừa Commit.
   - Sau commit, đẩy cache-update task vào **bounded queue in-memory** và để threadpool xử lý Redis pipeline bất đồng bộ.
   - **Giải thích ngắn:** bounded queue là hàng đợi RAM có giới hạn cứng để chống OOM; threadpool là tập worker threads cố định đọc task từ queue để gửi batch lệnh Redis.
   - **Queue full policy (Backpressure cứng):** khi queue đầy, worker phải block enqueue hoặc pause Kafka partitions; tuyệt đối không drop task cache update.
   - **Scale-down safety:** khi SIGTERM, worker dừng nhận message mới và drain queue trong thời gian grace; nếu timeout mà còn backlog thì phát metric/alert bắt buộc.
    - Rủi ro out-of-order cho cùng SKU có thể xảy ra nếu nhiều task/retry/pod cùng ghi key. Cách chặn tối ưu:
       - tuyến task theo striped lane `hash(sku_id)` để cùng SKU xử lý tuần tự,
       - coalescing latest-only theo `event_timestamp` trong queue,
       - Lua CAS tại Redis chỉ cho phép update nếu `incoming.event_timestamp >= cached.last_inventory_sync_at`.
    - **Lua CAS tại Redis (chi tiết):**
       - `KEYS[1] = product:sku:{sku_id}`
       - `ARGV[1] = incoming_event_timestamp`, `ARGV[2] = new_display_quantity`, `ARGV[3] = new_sku_status` (optional)
       - Đọc `cached_ts = $.last_inventory_sync_at` (mặc định `0` nếu key chưa có field).
       - Nếu `incoming_event_timestamp < cached_ts` -> return stale, bỏ qua write.
       - Nếu hợp lệ -> cập nhật `display_quantity`, `sku_status` (nếu có), và bắt buộc set `last_inventory_sync_at = incoming_event_timestamp`.
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
       - Cập nhật cờ khóa mềm: `UPDATE products SET is_deleted = true, version = version + 1 WHERE product_id = :product_id AND version = :current_version`. (Lưu ý, thư viện FSM không có trạng thái `DELETED`, chỉ lưu cờ `is_deleted` để giữ nguyên trạng thái cũ (ví dụ đang HIDDEN mà bị xóa), dễ phục vụ luồng Restore sau này).
     - Nếu số dòng update = 0, ném `StaleObjectStateException` để Controller retry.
   - **Dọn dẹp Orphan Data**: `DELETE FROM product_markdown_applied WHERE product_id = :product_id` để cắt đứt mọi khuyến mãi đang dính vào sản phẩm này, tránh rác dữ liệu tính toán.
     - `INSERT INTO product_outbox_events` ([`ProductDeletedEvent`](product_events.md#36-product_deleted)) để trigger quá trình async cleanup.
3. **[Worker] - Soft Invalidate Cache:**
   - Thông qua In-house worker, bắt giữ DB event và bắn ngầm lệnh `SET product:sku:{sku_id}` cập nhật đè trạng thái `is_deleted = true` cho tất cả SKU thuộc Product để duy trì triết lý Always-Warm. Không được dùng `DEL` sinh bão truy vấn DB.
   - Thao tác Overwrite tương tự cho `SET product:master:{product_id}`.
4. **[Background] - CQRS Cleanup:**
   - Search Service nhận event → xoá Product khỏi Elasticsearch index.
   - (Cart Service không còn nhận event từ Product Service do thiết kế đã chuyển sang Pull Model Point-Read theo thời gian thực).

## 9. Workflow Quản trị Danh mục (Category APIs)
**Endpoint:** `POST / PUT / DELETE /api/v1/categories/...`

Cho phép Administrator xây dựng hệ thống cây nhị phân (Level, Leaf).
**Quy trình chuẩn kiện (Workflow):**
1. **[PostgreSQL] - Quản lý cấu trúc:** 
   - Với thao tác Insert/Update: Kiểm duyệt dữ liệu cha con tránh Cây vòng tròn (Circular Dependency Trees).
   - Với thao tác Soft Delete: Kiểm duyệt rào cản xem Danh mục có dính Root chứa Sản phẩm không.
   - Mở Transaction -> Ghi Bảng `categories` -> tăng version bảng -> Insert Outbox Events (Bao gồm `CATEGORY_UPDATED`, `CATEGORY_DELETED`).
   - Đóng Giao Dịch.
2. **[Redis] - Kích hoạt Invalidate Caffeine L1 (After-Commit Hook) BẮT BUỘC:** 
   - Sau khi giao diện đã commit sạch sẽ ở PostgreSQL, API thực thi Bắn tín hiệu làm rỗng L1: Chạy `Redis.Publish("channel:product:l1-evict", "{...}")` để đẩy mệnh lệnh đến toàn cụm Product Pods trút bỏ hoàn toàn Cached Category Tree. Request API tức thì phía sau sẽ lao xuống DB tải lại nhánh cây mới nhất nạp lên RAM.
3. **[Background CQRS] - Nhuộm dữ liệu Search Service:**
   - Search Worker bắt thông điệp Update hoặc Cờ Deleted.
   - Thay vì lắt nhắt bắt DB Product tính ra Cây Search. ElasticSearch của Service Tìm kiếm sẽ dùng tập lệnh **Update By Query** nội tại ES để chạy ngầm và đổi toàn bộ Name Category cũ kỹ trên chục triệu sản phẩm. Khủng bố tốc độ không gây hại Product Database. NẾU `CATEGORY_DELETED` văng ra, Product Service phải xử lý ở Write Model bằng worker chuyên trách, đổi trạng thái các sản phẩm liên đới rồi phát `PRODUCT_STATE_CHANGED`; Search chỉ phản chiếu theo event này.
