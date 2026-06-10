# Product Service - Trình Tự Nghiệp Vụ API (Workflows)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tài liệu này mô tả chi tiết trình tự các bước thực thi từ lúc Admin gọi API cho đến khi dữ liệu chạy qua **Redis (Validation Cache)** → **PostgreSQL (Source of Truth)** → **Outbox Stream (Event)**.

Khác với Cart (Redis-First), Product Service mang tư tưởng **PostgreSQL-First (Command Model)** nhưng Cache lại đóng vai trò là "Người Gác Cổng" (Validation Gateway).

> **Kiến trúc tổng quan:**
> - [Thẩm định Dữ liệu (Validation)](product_caching_schema.md) — Redis Cache-Aside.
> - [Nguồn Sự Thật (PostgreSQL)](product_db_design.md) — Lưu trữ gốc cho Command Write Model.
> - [Biến Trạng thái (State Machine)](product_state_machine.md) — Cấu hình FSM thay đổi trạng thái sản phẩm.
> - [Luồng Đồng Bộ Ngầm (Background Workers)](product_workers.md) — Debezium Outbox + các worker đồng bộ nghiệp vụ.
> - [Cấu trúc Event Chi tiết (Events)](product_events.md) — Payload schema cho tất cả messages.

## 1. Gợi ý cấu trúc Base API Path
`[POST/GET/PUT/DELETE] /api/v1/products/...`

## 2. API: Tạo mới Sản Phẩm Bản Nháp (Create Concept)
**Endpoint:** `POST /api/v1/products`

Được gọi khi Admin bắt đầu quá trình list thông tin sản phẩm mới (Có thể chưa bán ngay).

**Quy trình chuẩn kiện (Workflow):**
1. **[DB] - Trích xuất & Xác minh Dữ Liệu Tham Khảo (Validation):**
   - Kiểm tra `category_id` tồn tại và hợp lệ bằng truy vấn trực tiếp PostgreSQL.
   - Kiểm tra xem Payload.Attributes gửi lên có chứa đủ các thuộc tính `is_required = true` theo Category đó không (tra trực tiếp từ bảng `category_attributes` trong PostgreSQL).
   - Nếu dữ liệu không hợp lệ → Return 400 Bad Request ngay.
2. **[PostgreSQL] - Ghi dữ liệu lõi & Outbox trong cùng Transaction:**
   - Mở Giao Dịch (`@Transactional`).
   - `INSERT INTO products` (Trạng thái: DRAFT).
   - `INSERT INTO product_skus` (Thêm các SKU con, `status` = ACTIVE).
   - `INSERT INTO product_outbox_events` (Sự kiện: [`PRODUCT_CREATED`](product_events.md#31-product_created)). **[Sửa Đổi Kiến Trúc CQRS]:** Tuyệt đối KHÔNG ĐƯỢC để Promotion Service consume sự kiện `PRODUCT_CREATED` này. Sản phẩm vừa tạo ở trạng thái DRAFT hoàn toàn chưa được xuất hiện hệ thống, việc gài đè luật Markdown lúc này là phi lý. Chờ khi sản phẩm lật sang trạng thái `ACTIVE` qua sự kiện FSM, hệ thống mới bắt đầu kết dính giá trị Markdown.
   - Đóng Giao Dịch (Commit).
3. **[Redis] - Lưu lại Cache cho API khác:**
   - (Tùy chọn) Ghi tức thì `product:sku:{id}` vào Redis để sẵn sàng cho tác vụ Đọc Point-Read nội bộ.
4. **[Backend] - Trả HTTP 201 Created:**
   - Phản hồi JSON cho admin.
5. **[Background] - Phát Event Đồng Bộ CQRS:**
   - **Debezium CDC Connector** thọc thẳng vào Write-Ahead Log (WAL) của PostgreSQL, chắt lọc dữ liệu table `product_outbox_events` tự động bắn tắp lự sang Kafka `t.product.events` cho Search Service. Loại bỏ hoàn toàn bottleneck của Outbox Polling Worker.

## 3. API: Sửa Đổi Thông Tin Sản Phẩm
**Endpoint:** `PUT /api/v1/products/{id}`

Sửa tên, giá tiền, hình ảnh, cập nhật tồn kho tĩnh, hoặc sửa SKU.

**Điểm khác biệt:**
- Tiến trình trong Transaction:
    - Cập nhật Product bằng Pessimistic Locking ở Aggregate Root. Quy tắc bắt buộc: trong một transaction, luôn lock Product cha bằng `SELECT ... FOR NO KEY UPDATE` trước khi thao tác bất kỳ SKU nào.
    - Nếu admin sửa `original_price`, luồng API vẫn xử lý đồng bộ trong cùng transaction thông qua Pricing Domain Module dùng chung với Markdown Worker: lock theo thứ tự `products -> product_skus`, cập nhật `original_price`, và nếu SKU đang có markdown active thì recompute `promotional_price` ngay để tránh drift. Giá `original_price` được lấy trực tiếp từ `product_skus`.
   - **Contract FE -> BE cho payload event:** Request FE có thể gửi delta (chỉ SKU thay đổi), nhưng trước khi ghi outbox, BE bắt buộc materialize snapshot đầy đủ Product + `skus[]` canonical tại thời điểm commit (không phát event delta mù).
   - `INSERT` event [`PRODUCT_UPDATED`](product_events.md#32-product_updated) vào Outbox.
    - Nếu giá thay đổi, `INSERT` thêm event [`PRODUCT_PRICE_UPDATED`](product_events.md#33-product_price_updated) vào Outbox.
- **Tiến trình Invalidate Cache (Soft Invalidation - Chống Thundering Herd Strict):**
  - ⚠️ **NEVER use Hard DELETE (`DEL product:sku:{id}`)** - nó gây Cache Stampede khi hàng nghìn request đổ vào cùng lúc. 
   - **Thay thế bằng Overwrite có guard:** `JSON.SET product:sku:{sku_id} ...` để đảm bảo luôn có dữ liệu point-read.
  - **Fallback Safety:** Nếu Redis GET return null (cache bị evict), Application BUỘC phải:  
    1. Gọi DB SELECT (không được gọi ES vì có thể stale)
    2. Transform dữ liệu ở RAM
      3. `JSON.SET` lại Redis ngay lập tức với TTL 1 tiếng
  - **Không được phép Fallback → ES** vì dữ liệu đó lệch (Event có thể delay vài giây). Chỉ fallback → PostgreSQL (Source of Truth).
   - **In-band bắt buộc trước khi trả HTTP:** Ngay sau DB commit và trước HTTP response, API phải overwrite các key `product:sku:{sku_id}` bị ảnh hưởng bằng dữ liệu mới nhất (JSON.SET). Mục tiêu là đảm bảo Read-Your-Own-Writes cho admin.
   - Nếu Redis ghi in-band lỗi, API retry ngắn có jitter (khuyến nghị 2-3 lần). Nếu vẫn thất bại, trả lỗi có thể retry (5xx) kèm correlation ID để client gọi lại an toàn.
   - Invalidation Worker chỉ đóng vai trò fallback/self-healing khi in-band không hoàn tất hoặc khi thay đổi đến từ luồng async (Markdown/Inventory).

## 4. API: Chuyển Trạng Thái Sản Phẩm
**Endpoint:** `PATCH /api/v1/products/{id}/status` hoặc `POST /api/v1/products/{id}/events`

Được gọi bởi Admin để chuyển trạng thái sản phẩm. Áp dụng Spring State Machine.

**Quy trình chuẩn kiện (Workflow):**
1. **[Khoá Song Song] - Database Row Lock (Đường chính thức):**
   - Đường chính thức là lock cấp Database theo lock order chuẩn hóa. Yêu cầu BẮT BUỘC: Dòng code đầu tiên nhất trong Transaction của Controller/FSM phải là: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE` để lấy cọc ở bảng Cha. Cấm tuyệt đối đụng vào bảng `product_skus` trước khi Lock Cha (Chống Deadlock AB-BA với Inventory Worker).
2. **[Database & FSM] - Kích hoạt Logic Lõi:**
   - Lấy Record `products` từ Postgres (Theo ID).
   - Truyền Command vào State Machine (`productStateMachine.sendEvent(command, product)`).
   - FSM kiểm tra điều kiện rẽ nhánh (Guard Conditions) (VD: nếu `command=SUBMIT` thì state hiện tại bắt buộc phải là `DRAFT`; nếu `command=SHOW` thì state hiện tại bắt buộc phải là `HIDDEN`). Xem [State Machine Docs](product_state_machine.md).
   - Nếu thỏa mãn, chuyển sang State mới.
3. **[Database Giao Dịch Đồng Thời] - Lưu Trữ Tình Trạng:**
   - `@Transactional`: Thông qua JpaRepository (Entity `.save()`), Hibernate lưu trạng thái mới. Locking được đảm bảo bằng `SELECT ... FOR NO KEY UPDATE` ở đầu transaction (xem Bước 1 bên dưới). TUYỆT ĐỐI KHÔNG sử dụng native SQL lock xen kẽ trong luồng FSM để tránh deadlock.
   - Nếu command là `SHOW`, xử lý bằng 1 nhánh atomic (Sau khi đã Lock Cha ở Bước 1):
         - Đếm tồn kho bằng hàm: `SELECT COALESCE(SUM(display_quantity), 0) FROM product_skus WHERE product_id = :product_id`.
       - Chỉ đổi `status = ACTIVE` khi tổng tồn > 0. Nếu tổng tồn = 0 thì giữ nguyên `status = HIDDEN` nhưng hệ thống vẫn ghi nhận ý định hiện lại. Sau khi inventory nhả hàng trở lại, luồng `AUTO_REACTIVATE_IN_STOCK` sẽ tự động hiện sản phẩm khi `is_deleted = false`.
       - Việc đánh thức trạng thái Product (`ACTIVE` hay `HIDDEN`) đã được quyết định ngay lập tức trong Transaction này dựa trên kết quả của hàm `SUM(display_quantity)`, triệt tiêu hoàn toàn nhu cầu sử dụng Worker bất đồng bộ chạy lại logic này.
   - Thay vì ghi log vào bảng riêng, audit trail được xử lý thông qua Kafka event `PRODUCT_STATE_CHANGED` đã được phát ở bước trên.
   - Trước khi insert outbox `PRODUCT_STATE_CHANGED`, BE phải truy vấn tập `sku_ids[]` liên đới trong cùng transaction và đóng gói vào payload (FE không cung cấp trường này).
   - Phát sinh Outbox Event: [`PRODUCT_STATE_CHANGED`](product_events.md#33-product_state_changed). Lưu ý: Nếu Product chuyển từ `DRAFT` sang `ACTIVE`, Promotion Service có trách nhiệm lắng nghe event này để chèn thêm Rule Giảm Giá còn hiệu lực (nếu có).
4. **[Khoá] - Giải phóng Lock:**
   - Lock row được giải phóng tự động khi transaction kết thúc (COMMIT/ROLLBACK).
5. **[Background] - CQRS & Cảnh Báo:**
   - Search Service nhận `PRODUCT_STATE_CHANGED` -> cập nhật `product_status` projection tương ứng trên Elasticsearch.
   - **Tường minh Cache Invalidation:** Khi bản tin outbox `PRODUCT_STATE_CHANGED` kích hoạt, Kafka in-house Invalidation Worker CHẮC CHẮN phải thực thi lệnh Override ghi đè trạng thái `status` trên Redis Cache cho các key `product:sku:{sku_id}` thuộc product đó. Việc này được thực thi mượt mà vì mảng `sku_ids` đã được BE đóng gói sẵn trong payload event, nên worker invalidation không cần query bổ sung xuống DB.

## 5. API: Point-Read (Đọc Chính Xác / Nhanh)
**Endpoint / Giao thức:** Thực hiện qua **gRPC (Protocol Buffers)** thay vì REST/HTTP JSON tốn kém. Tên hàm gRPC: `GetProductSku`.

Dành riêng cho Mạng Lưới Nội Bộ nội vùng (Cart Service, Order Service, Promotion Service). Đòi hỏi tính chính xác tột độ liên quan đến Giá (`original_price`, `promotional_price`) hiện tại và có mua được không (`status` ACTIVE). Tuyệt đối **KHÔNG** đọc từ Search Service vì Event có thể delay vài giây.

**Hợp đồng phản hồi tối thiểu cho `GetProductSku`/`GetBatchProductSkus`:**
- `sku_id`, `product_id`, `sku_code`
- `original_price`, `promotional_price`, `best_price`
- `sku_status`, `product_status`, `is_available`
- `display_quantity`, `variant_attributes`
- `main_image_url`

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Fetch Data (Primary Read):**
   - Gọi `JSON.GET product:sku:{id}`.
   - Áp dụng **Probabilistic Early Expiration (PER)**: Thuật toán xổ số ngẫu nhiên. Nếu TTL còn < 5%, 1 HTTP Request hên xui duy nhất sẽ tự nguyện ở lại background tải dữ liệu mới từ Database và ghi đè khoá, giúp refresh Cache êm ái mà không đợi nó quá hạn. Trả về kết quả cho Client ngay.
2. **[Fallback] - Xử lý Cache Miss & Singleflight (Request Coalescing):**
   - Nếu `JSON.GET` trả null (Hoặc Server mới boot):
   - **[P1/H3 Tối Ưu: Cross-Pod Singleflight]**: Việc chỉ dùng In-process Singleflight là chưa đủ để chống bão ở cấp Cluster (20 Pods vẫn sinh ra 20 DB hits). Cơ chế chuẩn là cross-pod distributed lock bằng `SETNX product:lock:fetch:{id} 1 EX 2`.
   - Nếu TRÚNG LOCK: Pod được phép query DB Postgres bảng `product_skus` JOIN `products`, sau đó nạp ngược `JSON.SET product:sku:{id}` với TTL 3600s, rồi giải phóng lock.
   - Nếu TRƯỢT LOCK: Pod Sleep 50ms (kèm jitter nhỏ) rồi quay lại vòng lặp `JSON.GET product:sku:{id}` (vì Pod trúng lock đã query thực tế và nạp cache xong). Có thể kết hợp In-process Singleflight để giảm thêm contention trong nội bộ từng pod.

**Endpoint Batch:** `gRPC GetBatchProductSkus`

Dành cho Cart Service cần lấy N SKU cùng lúc (hiển thị giỏ hàng). **[P7 Kiến trúc Resilience]**: Kiến trúc này đã đẩy rủi ro về phía Cart (Pull Model). Phía Cart Service BẮT BUỘC phải bọc lời gọi gRPC này bằng một **Circuit Breaker** (Ví dụ: Resilience4j). Khi Circuit Breaker bị bung/mở (Open State) do Product Service quá tải/timeout quá 5 lần, Cart Service phải kích hoạt luồng Fallback: trả về những SKU đang có sẵn trong bộ nhớ Memory Cache của chính Cart, các SKU bị thiếu thông tin sẽ mang nhãn "Đang cập nhật..." trên giao diện trả về, tuyệt đối không được sập/block quá trình xem giỏ hàng của End-user.

**Quy trình:**
1. Nhận danh sách `sku_ids` từ Protobuf Request.
2. Lên Redis gọi `JSON.MGET product:sku:{id1} product:sku:{id2} ... .` — lấy tất cả trong 1 round-trip.
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
   - Parse payload phù hợp cho allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`; event ngoài allowlist phải skip + metric + DLQ.
   - Chuẩn hóa `rule_id` về string ngay tại consumer boundary trước khi truy vấn/ghi DB.
   - Nếu `MARKDOWN_CREATED` có `start_time > now`, worker sẽ delay xử lý trong chính consumer (sử dụng thư viện delay queue hoặc scheduled poll).
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
    - `best_price = LEAST(original_price, COALESCE(promotional_price, original_price))`.
4. **[PostgreSQL] - Ghi dữ liệu & Tránh Deadlock Liên Bảng (Concurrency Guard):**
   - **Cảnh báo Race Condition:** Tuyệt đối không tung một lệnh `UPDATE product_skus` khổng lồ theo diện rộng (Category). Nếu đụng độ với luồng admin Update API (Lock `products` -> `product_skus`), sẽ lập tức gây Deadlock chéo.
   - Worker BẮT BUỘC phải chia theo nhóm `product_id` và ORDER BY `product_id` ASC. Với mỗi khối `product_id`, mở `@Transactional` độc lập:
   - **Chống va chạm đa instance worker:** ngay đầu transaction lock Product cha bằng `FOR NO KEY UPDATE` với `lock_timeout` ngắn. Nếu timeout thì `nack/requeue` theo retry policy, không được skip im lặng.
       - Khóa cha: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE`.
       - Khóa con + lấy giá live: `SELECT sku_id, original_price FROM product_skus WHERE sku_id IN (...) AND product_id = :product_id FOR UPDATE`.
       - Tính giá theo tập row đã lock, sau đó batch `UPDATE product_skus SET promotional_price = :calc, updated_at = NOW() WHERE product_id = :product_id AND sku_id IN (...)`.
       - Tracking: batch upsert `product_markdown_applied` (tracking rule → SKU mapping).
      - Outbox: batch insert `product_outbox_events` cho các SKU bị ảnh hưởng, chứa [`PRODUCT_PRICE_UPDATED`](product_events.md#33-product_price_updated); payload phải dùng `updated_at` timestamp để downstream guard out-of-order.
     - COMMIT Transaction để nhả cọc cho Product kế tiếp. Mất thêm milisecond vòng lặp nhưng an toàn hệ thống tuyệt đối 100%.
5. **[Worker] - Invalidate Cache (Soft Invalidation - Ownership tách bạch):**
   - Sau mỗi lần commit của từng Product, Markdown Worker chỉ quản lý key phụ markdown theo batch Redis pipeline.
   - Markdown Worker **không** ghi đè trực tiếp `product:sku:{id}` để tránh double-write với luồng Kafka invalidation.
    - Invalidation Consumer (`product-event-handler-group`) sẽ self-consume `PRODUCT_PRICE_UPDATED` từ outbox để refresh `product:sku:{id}` theo một đường ghi bất đồng bộ duy nhất.
   - Để tránh write amplification, Invalidation Consumer phải dedupe theo `product_id` trong từng poll-batch.
   - Trước khi overwrite `product:sku:{id}`, áp dụng stale guard theo `updated_at`: chỉ ghi khi event mới hơn cache hiện tại; event bằng/cũ hơn phải bỏ qua.
   - Với `MARKDOWN_ENDED` do tắt thủ công, đưa các lệnh `DEL product:markdown:active:{sku_id}` vào cùng pipeline batch để đồng bộ tức thời.
6. **[Background] - Phát Event CQRS:**
   - Debezium CDC Connector forward `PRODUCT_PRICE_UPDATED` từ outbox sang Kafka → Search Service cập nhật giá hiển thị trên Elasticsearch.

> **Lưu ý khi `MARKDOWN_UPDATED` / `MARKDOWN_ENDED`:** Luồng tương tự áp dụng cho việc lấy danh sách SKU từ bảng `product_markdown_applied`. Đặc thù với UPDATE, Worker BẮT BUỘC query lại `original_price` live từ bảng `product_skus`, KHÔNG DÙNG snapshot rác cũ; tiếp theo tính toán sửa lại DB rồi tung outbox bình thường. Reset Null Price nếu là event ENDED.
>
> **No-op idempotent cho ENDED:** nếu không còn bản ghi mapping theo `rule_id` thì ACK và kết thúc, không phát outbox rỗng.

## 7. Workflow: Xử Lý Inventory Sync Event
**Trigger:** Kafka Event [`INVENTORY_QUANTITY_CHANGED`](product_events.md#44-inventory_quantity_changed-từ-inventory-service) từ Topic `t.inventory.events`.
**Xử lý bởi:** [Inventory Sync Worker](product_workers.md#6-inventory-sync-worker).

**Quy trình chuẩn kiện (Workflow):**
1. **[Kafka Consumer] - Nhận Event:**
   - Worker (`groupId: product-inventory-sync-group`) nhận `INVENTORY_QUANTITY_CHANGED`.
   - Parse payload bắt buộc: `product_id`, `sku_id`, `available_quantity`, `event_timestamp`.
2. **[PostgreSQL] - Cập nhật Display Quantity:**
   - `@Transactional`:
       - Bước 1: `SELECT product_id, status, admin_hidden, is_deleted FROM products WHERE product_id = :product_id FOR NO KEY UPDATE`.
       - Bước 2: `SELECT sku_id, product_id, status, last_inventory_sync_at FROM product_skus WHERE sku_id = :sku_id AND product_id = :product_id FOR UPDATE`.
       - Bước 3: **Out-of-order guard:** chỉ apply nếu `event_timestamp > last_inventory_sync_at`.
       - Bước 4: `UPDATE product_skus SET display_quantity = :available_quantity, last_inventory_sync_at = :event_timestamp WHERE sku_id = :sku_id`.
       - Bước 5: Sử dụng Pessimistic Locking cho toàn bộ transaction. Nếu transaction chỉ chạy SQL và không gọi FSM/JPA thì vẫn áp dụng lock `SELECT ... FOR NO KEY UPDATE` ở đầu transaction. Nếu transaction gọi FSM/JPA thì lock được đảm bảo bởi logic rẽ nhánh FSM.
3. **[Logic] - Kiểm tra Auto-Hide / Auto-Reactivate chống Race Condition:**
   - **BẮT BUỘC sử dụng Row-Level Lock** kiểu Pessimistic (`FOR NO KEY UPDATE` + `FOR UPDATE`) và giữ lock xuyên suốt toàn bộ chuỗi Đọc Product -> Đọc SKU -> Tính toán -> Update, tuyệt đối không tách thành nhiều transaction con.
    - Nếu `available_quantity = 0` và SKU đang `ACTIVE`:
      - Chuyển `product_skus.status = 'OUT_OF_STOCK'`.
   - Sau khi lưu trạng thái SKU vào DB, kiểm tra lại tổng: **Tất cả** SKU của Product này đều đã mang `status = OUT_OF_STOCK` và `is_deleted = false`? → Nếu đúng, Trigger [AUTO_HIDE_OUT_OF_STOCK](product_state_machine.md#22-transitions-do-systemworker-tự-động-trigger) trên Product cha.
    - Nếu `available_quantity > 0` và SKU đang `OUT_OF_STOCK`:
         - **PHÒNG CHỐNG Lỗ hổng TOCTOU (Time-of-Check to Time-of-Use) & Override Ý định admin (ATOMIC):** Khi SKU chuyển từ `OUT_OF_STOCK` → `ACTIVE`, cần kiểm tra kết hợp `status = 'HIDDEN' AND admin_hidden = false AND is_deleted = false` (MỆNH ĐỀ KÉP + soft-delete guard).
         - **Chuẩn thực thi nhất quán:** Không được mở transaction mới hoặc chạy query kiểm tra bên ngoài transaction. Có hai cách tương đương và đều hợp lệ nếu chạy trong cùng transaction đang giữ lock:
            - Cách A: dùng trực tiếp row `products` đã lock từ Bước 1 để evaluate predicate trước khi trigger `AUTO_REACTIVATE_IN_STOCK`.
            - Cách B: chạy query guard tường minh `SELECT 1 FROM products WHERE product_id = :product_id AND status = 'HIDDEN' AND admin_hidden = false AND is_deleted = false FOR NO KEY UPDATE;` ngay trước trigger.
         - Nếu predicate không thỏa thì hủy trigger (tôn trọng ý định ẩn của admin). Nếu thỏa thì trigger `AUTO_REACTIVATE_IN_STOCK`.
         - Mục tiêu là giữ check + transition ở cùng transaction đang giữ lock để triệt tiêu Race Condition khi cờ `admin_hidden` thay đổi giữa lúc check và lúc update.
   - **Đồng bộ hóa trạng thái:** Giữ cho State luôn nhất quán. Giải phóng Pessimistic Lock tự động khi hoàn tất DB Transaction (Commit).

4. **[Redis] - Update Cache (Đồng bộ - Không Queue):**
   - Sau khi Commit Database thành công, gọi Redis SET đồng bộ ngay lập tức với retry ngắn (2-3 lần).
   - Sử dụng `JSON.SET product:sku:{sku_id}` để ghi đè cache với dữ liệu mới nhất từ transaction.
   - Nếu Redis write thất bại sau retry, event đã được ghi vào outbox (`PRODUCT_STATE_CHANGED`) sẽ được `product-event-handler-group` tiêu thụ để self-heal cache.

## 8. API: Xóa Mềm Sản Phẩm (Soft Delete)
**Endpoint:** `DELETE /api/v1/products/{id}`

Chỉ cho phép xoá sản phẩm ở trạng thái `DRAFT` hoặc `HIDDEN`. Tuyệt đối KHÔNG xóa `ACTIVE` (phải HIDE trước).

**Quy trình chuẩn kiện (Workflow):**
1. **[Validation] - Kiểm tra trạng thái hiện tại:**
   - Nếu `status` không thuộc `{DRAFT, HIDDEN}` → Return 400 Bad Request.
2. **[Database] - Đánh cờ xóa mềm và Dọn dẹp rác (Cascade Cleanup):**
   - Sử dụng **Optimistic Locking** ở tầng Database để đồng nhất kiến trúc (Lock-Free) với APIs chuyển trạng thái, thay vì sử dụng Distributed Lock.
    - `@Transactional`:
       - Cập nhật cờ khóa mềm: `UPDATE products SET is_deleted = true WHERE product_id = :product_id`. (Lưu ý, thư viện FSM không có trạng thái `DELETED`, chỉ lưu cờ `is_deleted` để giữ nguyên trạng thái cũ (ví dụ đang HIDDEN mà bị xóa), dễ phục vụ luồng Restore sau này).
     - Nếu số dòng update = 0, ném `StaleObjectStateException` để Controller retry.
    - Trong cùng transaction, BE phải lấy danh sách `sku_ids[]` thuộc Product (không phụ thuộc payload FE) để đóng gói event `PRODUCT_DELETED` phục vụ invalidation đầy đủ.
   - **Dọn dẹp Orphan Data**: `DELETE FROM product_markdown_applied WHERE product_id = :product_id` để cắt đứt mọi khuyến mãi đang dính vào sản phẩm này, tránh rác dữ liệu tính toán. ĐỒNG THỜI, BẮT BUỘC phải update `promotional_price = NULL` cho tất cả bản ghi thuộc bảng `product_skus` của Sản phẩm này, để chống lỗi mồ côi dữ liệu khuyến mãi nếu Sản phẩm được Restore sau này.
   - `INSERT INTO product_outbox_events` ([`PRODUCT_DELETED`](product_events.md#36-product_deleted)) để trigger quá trình async cleanup.
3. **[Worker] - Soft Invalidate Cache:**
   - Thông qua In-house worker, bắt giữ DB event và bắn ngầm lệnh `JSON.SET product:sku:{sku_id}` cập nhật đè trạng thái `is_deleted = true` cho tất cả SKU thuộc Product để duy trì triết lý Always-Warm. Không được dùng `DEL` sinh bão truy vấn DB.
   - Bắt buộc dọn key markdown phụ: `DEL product:markdown:active:{sku_id}` cho toàn bộ SKU thuộc product bị soft-delete.
   - Mọi worker async (Inventory/Markdown/Invalidation/FSM hook) bắt buộc kiểm tra `is_deleted = false` trước khi trigger bất kỳ transition tự động nào để tránh hồi sinh bản ghi đã soft-delete.
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
   - Mở Transaction -> Ghi Bảng `categories` -> Insert Outbox Events (Bao gồm `CATEGORY_UPDATED`, `CATEGORY_DELETED`).
   - Đóng Giao Dịch.
2. **[Background CQRS] - Nhuộm dữ liệu Search Service:**
   - Search Worker bắt thông điệp Update hoặc Cờ Deleted.
   - Thay vì lắt nhắt bắt DB Product tính ra Cây Search. ElasticSearch của Service Tìm kiếm sẽ dùng tập lệnh **Update By Query** nội tại ES để chạy ngầm và đổi toàn bộ Name Category cũ kỹ trên chục triệu sản phẩm. Khủng bố tốc độ không gây hại Product Database. NẾU `CATEGORY_DELETED` văng ra, payload nên mang thêm `descendant_category_ids` để Product worker xử lý đúng toàn bộ subtree; tối thiểu luôn xử lý `category_id` gốc. Product Service phải xử lý ở Write Model bằng worker chuyên trách, đổi trạng thái các sản phẩm liên đới sang `HIDDEN`. Sau đó mới phát `PRODUCT_STATE_CHANGED`; Search chỉ phản chiếu theo event này.

## 10. Internal APIs cho Search Service

Các API dưới đây chỉ dành cho nội bộ hệ thống, dùng cho Search bootstrap/reindex và không public cho client bên ngoài.

### 10.1 API: Category Tree Snapshot
**Endpoint:** `GET /api/v1/internal/products/categories/tree`

**Mục đích:** Search Category Tree Sync Worker sử dụng để đồng bộ dữ liệu category vào Search Service khi bootstrap hoặc self-heal định kỳ.

**Response tối thiểu:**
- `category_id`, `name`, `slug`, `parent_id`, `level`, `path`

**Ràng buộc:**
- API phải trả snapshot nhất quán tại thời điểm đọc.
- Dùng cho read-only projection, không kích hoạt logic nghiệp vụ ghi.

### 10.2 API: Product Export for Reindex
**Endpoint:** `GET /api/v1/internal/products/export?page={n}&size={m}`

**Mục đích:** Search Full Reindex Job quét dữ liệu theo trang.

**Response tối thiểu mỗi record:**
- danh sách `skus[]` (bao gồm `sku_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `variant_attributes_json`)
- `attributes_json`, `created_at`, `updated_at`

**Tương thích ngược cho trường trạng thái SKU:**
- Nếu producer nội bộ cũ vẫn trả `status` trong `skus[]`, Search worker phải map alias `status -> sku_status` trước khi index.

**Ràng buộc:**
- Sort ổn định (`product_id ASC`, `sku_id ASC`) để replay an toàn.
- Hỗ trợ watermark/updated_since cho phase catch-up reindex.
- Read-only, không mutate Source of Truth.

### 10.3 API: Product Snapshot by ID (Self-heal cho Retry)
**Endpoint:** `GET /api/v1/internal/products/{product_id}/snapshot`

**Mục đích:** Search Retry Replayer dựng lại đầy đủ document khi gặp `document_missing_exception` cho event partial update (`PRODUCT_PRICE_UPDATED`, ...).

**Response tối thiểu:**
- `skus[]`: `sku_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `variant_attributes_json`, `updated_at`
- `attributes_json`

**Ràng buộc:**
- Trả về snapshot nhất quán tại thời điểm đọc.
- Read-only, không phát side-effect nghiệp vụ.
- Tương thích ngược: chấp nhận `skus[].status` như alias của `skus[].sku_status` khi parse request/response nội bộ.
