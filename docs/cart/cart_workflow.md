# Cart Service - Trình Tự Nghiệp Vụ API (Workflows)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Tài liệu này mô tả chi tiết trình tự các bước thực thi từ lúc Khách hàng gọi API cho đến khi dữ liệu chạy qua **Redis (Primary)** → **Outbox Stream** → **PostgreSQL (Cold Store)**.

> **Kiến trúc tổng quan:**
> - [Caching Schema (Redis-First)](cart_caching_schema.md) — Lua Script, Key structure, Value format
> - [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md) — Đảm bảo Atomicity giữa Store + Event
> - [Database Schema (Cold Store)](cart_db_schema.md) — Backup bền vững trên PostgreSQL
> - [Events Structure](cart_events.md) — Cấu trúc payload và danh sách event types
> - [Idempotency Pattern](cart_idempotency.md) — Chống trùng / sai thứ tự khi ghi DB
> - [Background Workers](cart_workers.md) — Danh sách Worker xử lý bất đồng bộ

## 1. Gợi ý cấu trúc Base API Path
`[POST/GET/PUT/DELETE] /api/v1/carts/...`

## 2. API: Cập nhật / Thêm mới sản phẩm vào giỏ
**Endpoint:** `POST /api/v1/carts/items`

Được gọi khi người dùng bấm "Thêm vào giỏ hàng" hoặc thay đổi số lượng (+/-) ở giao diện.

**Quy trình chuẩn kiện (Workflow):**
1. **[Backend] - Chuẩn bị Payload:** 
   - Backend nhận `sku_id`, `quantity` từ Body và `user_id` từ Header `X-User-Id` (được Inject bởi API Gateway).
   - Gọi API nội bộ sang *Product Service*: `GET /api/v1/products/skus/{sku_id}`.
   - **Xử lý và Mapping dữ liệu:**
      - `unit_price_at_add`: Lấy `promotional_price` nếu có, ngược lại lấy `original_price`.
      - `image_url`: Map từ trường `main_image_url` của SKU.
      - `variant_info`: Chuyển đổi từ `variant_attributes` (JSON) sang chuỗi hiển thị (VD: "Màu: Đen, Size: L").
   - **Validation cấp độ Service:**
      - Kiểm tra `sku_status` và `product_status` phải là `ACTIVE`.
      - Kiểm tra tồn kho `display_quantity` (Snapshot) có đủ cho `quantity` yêu cầu không.
   - Build chuỗi JSON Payload hoàn chỉnh đại diện cho SKU đó.
2. **[Redis] - Nguyên tử hóa qua Lua Script ([Xem code](cart_caching_schema.md#31-thêm-add--cập-nhật-update-item-ngăn-chặn-race-condition)):**
   - App Server truyền params ngầm vào Lua Script.
   - Redis kiểm tra ngưỡng cấu hình tối đa số lượng SKUs trong giỏ (`HLEN`). 
   - Redis cộng dồn Quantity nếu đã tồn tại, chặn trần max quantity per item.
   - Ghi/Cập nhật dữ liệu vào `HSET`.
   - Sinh Event `CART_ITEM_ADDED` hoặc `CART_ITEM_UPDATED` thông qua `XADD` xuống Outbox Stream trên cùng Lua context đó.
   - Gia hạn vòng đời giỏ hàng 7 ngày.
3. **[Backend] - Trả HTTP 200 OK:**
   - Ngay sau khi Lua Script chạy xong nháy mắt (1-3ms), API trả về Success tức thì cho User mà không cần quan tâm DB đã lưu chưa.
4. **[Background] - Bất đồng bộ xuống DB:**
   - [Outbox Relay Worker](cart_workers.md) gắp Event từ Redis Stream bắn sang Kafka.
   - [Postgres Sync Worker](cart_workers.md) gắp Kafka Event Upsert xuống Table `cart_items` và cập nhật cột `updated_at` của bảng master `carts`. Xem cơ chế chống lỗi ở [Idempotency](cart_idempotency.md).

## 3. API: Lấy thông tin Giỏ hàng (Đọc)
**Endpoint:** `GET /api/v1/carts/mine`

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Fetch Data:**
   - Gọi `HGETALL $cart_key`.
   - App Server nhận về dict flat (key = `sku_id`, value = JSON string).
2. **[App Server] - Pull Model (Multi-Get qua gRPC với Circuit Breaker):**
   - Tập hợp tất cả `sku_id` có trong kết quả Redis.
   - Kiểm tra **Local Cache** (ví dụ sơ bộ có TTL cực ngắn 1-2 phút tùy bộ nhớ process) cho các SKU này. Các SKU chưa có trong Local Cache sẽ được gom thành 1 mảng để gọi gRPC.
   - Bắn 1 request gRPC duy nhất: `GetProductSnapshots(mảng_sku_ids)` sang Product Service.
   - **Lưu ý an toàn:** Request gRPC **phải được bọc bởi Circuit Breaker** (như Resilience4j) với mức *Timeout cứng (VD: 200ms)*. 
   - **Fallback Logic:** Nếu gRPC gọi lỗi hoặc quá Timeout, lập tức bypass và bỏ qua bước cập nhật giá mới (tiếp tục dùng nguyên Snapshot JSON cũ sinh ra lúc add vào giỏ), nhưng chèn kèm một flag/thông điệp lỗi mềm để cảnh báo ở màn hình: *"Hệ thống đang quá tải, giá sản phẩm có thể chưa được cập nhật mới nhất"*. Điều này đảm bảo Cart Service không bao giờ sập dây chuyền nếu Product Service sụp trặc.
   - **Merge Dữ Liệu:** Đối với những SKU gọi gRPC thành công, cập nhật đè thông tin hiển thị (VD: `current_price`, flag đứt hàng `is_available` mới nhất) vào JSON Object tương ứng. Tuyệt đối: **KHÔNG ghi đè trường `unit_price_at_add`**.
3. **[App Server] - Group By Shop (In-Memory):**
   - Decode tất cả JSON values.
   - Group items theo `seller_id`.
   - Với mỗi nhóm shop, tính `latest_item_at = max(added_at)` của các items trong nhóm.
   - Sắp xếp các nhóm theo `latest_item_at DESC` (shop có sản phẩm được thêm gần nhất hiển thị trên cùng).
   - Trong mỗi nhóm, sắp xếp items theo `added_at DESC`.
   - > **Hiệu năng:** Grouping và sorting thực hiện hoàn toàn in-memory trên App Server sau 1 lần `HGETALL` duy nhất — không tốn thêm Network I/O, không thay đổi Redis structure.
4. **Response Structure:**
   ```json
   {
     "shop_groups": [
       {
         "seller_id": "seller-002",
         "seller_name": "Fashion Store",
         "latest_item_at": 1708616400456,
         "items": [
           {
             "sku_id": "sku-2001",
             "product_name": "Váy Hoa",
             "variant_info": "Màu Hồng, Size M",
             "image_url": "https://cdn.domain.com/vay.jpg",
             "quantity": 1,
             "unit_price_at_add": 250000,
             "added_at": 1708616400456
           }
         ]
       },
       {
         "seller_id": "seller-001",
         "seller_name": "Shop Tech Việt",
         "latest_item_at": 1708530000123,
         "items": [
           {
             "sku_id": "sku-1001",
             "product_name": "Áo Thun Nam",
             "variant_info": "Màu Đen, Size L",
             "image_url": "https://cdn.domain.com/ao.jpg",
             "quantity": 2,
             "unit_price_at_add": 150000,
             "added_at": 1708530000123
           }
         ]
       }
     ],
     "total_items": 2,
     "total_unique_skus": 2
   }
   ```
4. **[Fallback] - Xử lý Cache Miss (Re-validate & Re-hydrate):**
   Xảy ra khi Redis Key đã hết hạn (TTL 7 ngày) nhưng User quay lại tương tác. Dữ liệu dưới Database lúc này là dữ liệu "Lạnh" (Cold Data), có thể chứa giá hoặc nội dung sản phẩm đã cũ.
   - **B1: Kiểm tra tình trạng giỏ hàng:** Query bảng `carts` lấy `status`. Nếu status là `DELETED` hoặc `CHECKED_OUT` → Trả về giỏ hàng trống ngay lập tức.
   - **B2: Truy xuất danh sách từ Database:** 
     ```sql
     SELECT sku_id, quantity, product_snapshot 
     FROM cart_items 
     WHERE cart_id = $1 AND is_deleted = false;
     ```
   - **B3: Negative Caching:** Nếu DB không trả về dữ liệu, ghi một key tạm `cart:user:{id}:notfound` với TTL 60 giây để chặn request spam quét rỗng xuống Database liên tục, sau đó return giỏ hàng rỗng.
   - **B4: Gọi Batch API sang Product Service (Sửa lỗi Cache Stampede/Thundering Herd):**
     **Cảnh báo Race Condition:** Trước khi thực hiện Query từ B2 và gọi B4, hệ thống phải thực hiện Distributed Lock thông qua lệnh Redis `SETNX cart:lock:rebuild:{id} 1 EX 10`. 
     - **Thread nắm Lock (Leader):** Sẽ tiến hành bốc mảng `sku_id` từ B2 và gọi API `GET /api/v1/products/batch?skus=sku1,sku2...` để kéo giá, tình trạng từ Product Service. Sau khi gọi thành công, xóa Lock `DEL cart:lock:rebuild:{id}`.
     - **Các Thread trượt Lock (Followers):** Tự động Sleep 50ms và Retry chạy lại từ Bước B1 lên Redis đọc. Không để cả trăm request cùng chọc xuống PostgreSQL và ngập lụt Call nội bộ sang Product Service gây sập chuyền.
   - **B5: Xử lý Mix Data (Re-hydrate) & Re-populate Redis:**
     - Vòng lặp map dữ liệu từ Product Service đè lên `product_snapshot` lấy từ DB, đặc biệt cập nhật lại thông tin mới nhất.
     - Nếu Product API trả về status không phải `ACTIVE`, đánh dấu cờ `is_available = false`.
     - Bắt buộc sử dụng **Silent Rehydrate Lua Script** kèm logic Timestamp Guard để đẩy lại lên Redis: Lệnh Lua nạp từng Field, chỉ `HSET` lại Item mới nếu Field đó hoàn toàn chưa có tại Redis, hoặc nếu có rồi thì Timestamp `updated_at` của Redis phải bé hơn bản mới. Tuyệt đối KHÔNG gọi `XADD` phát sinh Event trong Lua script này để tránh lỗi **TTL replay idempotency**.
   - **B6: Kích hoạt TTL:** Gọi `EXPIRE $cart_id 604800` (7 ngày) để kích hoạt lại vòng đời trên Cache với giá trị đã Re-validate. Tính toán trước `subtotal` bằng cách nhân số lượng với giá mới nhất và trả về kèm theo response mảng JSON cho Frontend (khắc phục issue subtotal uncalculated).

## 4. API: Batch Delete (Xóa lô sản phẩm sau Checkout tĩnh)
**Endpoint:** `DELETE /api/v1/carts/items` (Body: `cart_id` + mảng `sku_ids`)

> **Quy ước nghiệp vụ:** Contract chuẩn hóa dùng `cart_id` + `sku_ids` tách riêng để tránh parse chuỗi ghép có dấu `:`. Trường `cart_item_ids` chỉ duy trì backward compatibility tạm thời.

**Quy trình chuẩn kiện (Workflow):**
1. **[Backend] - Chuẩn bị Payload:** 
   - Nhận `cart_id` và mảng `sku_ids` từ event `ORDER_PAID`.
   - Với payload cũ chỉ có `cart_item_ids`, parse an toàn từ phải sang trái để tách `sku_id`.
2. **[Redis] - Nguyên tử hóa qua Lua Script ([Xem code](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove)):**
   - Truyền Timestamp của đơn Checkout (`checkout_timestamp_us`), Idempotency Key, và danh sách `sku_id` vào Redis.
   - Redis thực hiện chốt Idempotency chặn lệnh ngay ở dòng đầu trong mặt khối Script (Fix 4).
   - Redis thực hiện so sánh check mốc thời gian (Fix 3), bỏ qua những món bị user cố tình thêm mới sau checkout, sau đó thực thi `HDEL` theo field key của giỏ hàng (đang dùng `sku_id`).
   - Tương ứng với mỗi nhát `HDEL`, 1 Event [`CART_ITEM_DELETED`](cart_events.md#23-cart_item_deleted) được ném vào stream `XADD` (sử dụng `cjson.encode` cho JSON an toàn).
   - Check chốt hạ nếu giỏ hàng rỗng `HLEN == 0` → Xóa nguyên Key bằng `DEL`.
   - Nếu luồng do user xóa thủ công: phát [`CART_CLEARED`](cart_events.md#24-cart_cleared).
   - Nếu luồng do checkout (`ORDER_PAID`): phát [`CART_CHECKED_OUT`](cart_events.md#25-cart_checked_out).
3. **[Background] - Bất đồng bộ Database:**
   - Tương tự chiều Update, [Postgres Sync Worker](cart_workers.md#2-postgres-sync-consumer-worker) gắp Event Delete đi xử lý, gỡ dòng trong bảng Cold Store (`cart_items`). Áp dụng [Out-of-order validation cho DELETE](cart_idempotency.md#24-xử-lý-sai-thứ-tự-cho-delete-event).
   - Nếu nhận event `CART_CLEARED`, Update bảng `carts` chuyển `status` sang `DELETED`.
   - Nếu nhận event `CART_CHECKED_OUT`, Update bảng `carts` chuyển `status` sang `CHECKED_OUT`.




### 4. Cơ chế Circuit Breaker & Fallback (Pull Model)
Để phòng ngừa lỗi Cascading Failure (sập dây chuyền nếu Product Service sụp trặc), các truy vấn lấy giá mới nhất sẽ được cấu hình quy tắc sau:
- **Thời gian chờ (Timeout):** Request gRPC sang Product Service bị giới hạn cứng thời gian thực thi (ví dụ tối đa 200ms).
- **Fallback Logic:** Nếu Product Service phản hồi quá 200ms hoặc báo lỗi, Circuit Breaker sẽ chuyển sang trạng thái Open. Cart Service tự động Bypass chốt chặn. 
- **Cảnh báo UI:** Phản hồi Fallback đọc `product_snapshot` hiện đang lưu sẵn trong Redis/PostgreSQL ra hiện tạm để user vẫn lướt được giỏ hàng. Kèm theo đó là thông báo hiển thị nhẹ trên UI: _"Hệ thống đang quá tải, giá sản phẩm có thể chưa được cập nhật mới nhất"_.
- **Lợi ích kiến trúc:** Đảm bảo SLA cho màn hình xem giỏ hàng luôn nằm trong ngưỡng an toàn tuyệt đối. Cart Service không bao giờ sập kéo theo Product Service.


