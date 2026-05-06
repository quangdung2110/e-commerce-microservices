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

> **Boundary với Checkout Service:** Đây là API duy nhất cho nghiệp vụ mutate `quantity`. Checkout Preview không cho phép tăng/giảm số lượng trực tiếp.

**Quy trình chuẩn kiện (Workflow):**
1. **[Backend] - Chuẩn bị Payload:**
   - Backend nhận `sku_id`, `quantity` từ Body và `user_id` từ Header `X-User-Id` (được Inject bởi API Gateway).
   - Gọi gRPC nội bộ sang *Product Service*: `GetProductSku(sku_id)`.
   - **Xử lý và Mapping dữ liệu:**
      - `unit_price_at_add`: Lấy `promotional_price` nếu có, ngược lại lấy `original_price`.
      - `image_url`: Map từ trường `main_image_url` của SKU.
      - `variant_info`: Chuyển đổi từ `variant_attributes` (JSON) sang chuỗi hiển thị (VD: "Màu: Đen, Size: L").
   - **Validation cấp độ Service:**
      - Kiểm tra `sku_status` và `product_status` phải là `ACTIVE`.
      - Kiểm tra tồn kho `display_quantity` (Snapshot) có đủ cho `quantity` yêu cầu không.
   - Build chuỗi JSON Payload hoàn chỉnh đại diện cho SKU đó.
2. **[Redis] - Nguyên tử hóa qua Lua Script ([Xem code](cart_caching_schema.md#31-thêm-add--cập-nhật-update-item-ngăn-chặn-race-condition)):**
   - Trước khi gọi Lua, App Server pre-register intent: `ZADD outbox_publish_intents <now_ms> <stream_key>`.
   - App Server truyền params ngầm vào Lua Script.
   - Redis kiểm tra ngưỡng cấu hình tối đa số lượng SKUs trong giỏ (`HLEN`).
   - Redis cộng dồn Quantity nếu đã tồn tại, chặn trần max quantity per item.
   - Ghi/Cập nhật dữ liệu vào `HSET`.
   - Sinh Event `CART_ITEM_ADDED` hoặc `CART_ITEM_UPDATED` thông qua `XADD` xuống Outbox Stream trên cùng Lua context đó.
   - Gia hạn vòng đời giỏ hàng 7 ngày.
3. **[Backend] - Hậu xử lý Write & Trả HTTP 200 OK:**
   - Xóa negative cache: `DEL cart:user:{id}:notfound` để tránh trả giỏ rỗng giả ở request đọc kế tiếp.
   - Hoàn tất publish path: `ZADD active_outbox_streams <now_ms> <stream_key>` + `PUBLISH outbox_notifications <stream_key>`.
   - Dọn intent sau publish thành công: `ZREM outbox_publish_intents <stream_key>`.
   - Nếu Pod chết giữa Lua thành công và publish path, [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) sẽ tự recovery từ `outbox_publish_intents` thay vì quét SCAN toàn keyspace.
   - Relay Worker sẽ lấy `stream_id` trực tiếp từ Redis message ID (`msg.ID`) để bổ sung vào Kafka header; Postgres Sync dùng `stream_id` làm khóa ordering/idempotency chính.
   - Sau khi hoàn tất publish path nhẹ (thường chỉ vài ms), API trả về Success cho User mà không cần chờ DB đã lưu xong.
4. **[Background] - Bất đồng bộ xuống DB:**
   - [Outbox Relay Worker](cart_workers.md) gặp Event từ Redis Stream bắn sang Kafka.
   - [Postgres Sync Worker](cart_workers.md) gặp Kafka Event Upsert xuống Table `cart_items` và cập nhật bảng master `carts` theo guard `stream_id`. Xem cơ chế chống lỗi ở [Idempotency](cart_idempotency.md).

## 3. API: Lấy thông tin Giỏ hàng (Đọc)
**Endpoint:** `GET /api/v1/carts/mine`

**Định nghĩa dữ liệu bắt buộc trước khi triển khai:**
- `unit_price_at_add`: Giá snapshot lúc user thêm vào giỏ. Đây là dữ liệu lịch sử, **immutable**.
- `current_price`: Giá hiện tại tại thời điểm đọc (lấy từ Product Service theo Pull Model), **chỉ là field response runtime**, không phải field lưu bền trong Redis Hash Value.
- `is_available`: Cờ khả dụng để hiển thị/validate checkout. Trường này có thể được làm mới trong luồng cache-miss rehydrate, nhưng không phát sinh event outbox.

**Quy trình chuẩn kiện (Workflow):**
1. **[Request Gate] - Anti-Stampede và Negative Cache check:**
    - Kiểm tra key `cart:user:{id}:notfound` trước khi đọc DB.
    - Nếu key tồn tại thì trả giỏ rỗng ngay (TTL 60 giây) để chặn quét DB lặp.
2. **[Redis] - Fetch Data:**
    - Gọi `HGETALL $cart_key`.
    - Nếu có dữ liệu, đi theo nhánh **Cache Hit**.
    - Nếu không có dữ liệu, đi theo nhánh **Cache Miss Rehydrate**.

### 3.1. Nhánh Cache Hit
1. **[App Server] - Decode và gom SKU:**
    - Decode dict trả về từ Redis (key = `sku_id`, value = JSON string).
    - Thu thập tập `sku_ids`.
2. **[App Server] - Pull Model (Multi-Get qua gRPC với Circuit Breaker):**
    - Lấy các SKU được gom ở trên và gọi 1 request gRPC `GetBatchProductSkus`.
    - Timeout cứng: 200ms. Bắt buộc bọc Circuit Breaker.
3. **Merge Runtime Overlay (viết lại `is_available` vào Redis khi BANNED):**
    - Nếu gRPC thành công: map dữ liệu Product vào response runtime:
       - `current_price = promotional_price ?? original_price`
       - `is_available` runtime theo trạng thái mới nhất từ Product
    - **Đặc biệt:** Nếu gRPC trả về `is_available = false` (product bị BANNED, deleted, hoặc out-of-stock), **bắt buộc ghi lại field `is_available = false` vào Redis Hash** bằng `HSET cart_key sku_id updated_json` ngay sau đó (không EXPIRE). Đây là ngoại lệ duy nhất của Read-Only TTL rule — chỉ cập nhật 1 field với is_available=false, không đụng vào TTL hay các field khác.
    - Nếu gRPC lỗi/timeout: dùng snapshot cũ trong Redis và trả cờ `is_price_degraded = true` để UI cảnh báo mềm.
    - **Nguyên tắc bắt buộc:** Ngoại trừ việc ghi lại `is_available = false` khi Product BANNED, Cache-hit read **không được HMSET/EXPIRE** vào `cart:user:{id}`.

> **Lý do kiến trúc:** Redis vẫn là Source of Truth của trạng thái cart (SKU, quantity, timeline). Product Service vẫn là Source of Truth của dữ liệu catalog thời gian thực. Overlay lúc đọc không làm mất vai trò Source of Truth của Redis.

### 3.2. Nhánh Cache Miss Rehydrate (chi tiết)
Xảy ra khi Redis key hết TTL hoặc bị evict, nhưng Cold Store vẫn còn dữ liệu.

1. **B1 - Cart state gate (DB):**
    - Query bảng `carts` lấy `status`.
    - Nếu `DELETED` hoặc `CHECKED_OUT` -> trả giỏ rỗng.
2. **B2 - Load cold items (DB):**
    ```sql
    SELECT sku_id, quantity, product_snapshot
    FROM cart_items
    WHERE cart_id = $1;
    ```
3. **B3 - Negative Caching:**
    - Nếu DB không có row, set `cart:user:{id}:notfound` TTL 60 giây rồi trả rỗng.
4. **B4 - Singleflight + Pull Product:**
    - Dùng In-Memory Singleflight trên từng Pod để dồn các request miss trùng `cart_id`.
    - Chỉ request tiên phong thực hiện B2 + gRPC `GetBatchProductSkus`.
    - **Singleflight Timeout (bắt buộc):**
      - Leader phải có context timeout cứng (khuyến nghị 1 giây), bao gồm cả DB query + gRPC.
      - Nếu leader timeout **sau khi gRPC đã gọi nhưng chưa nhận response**, context cancel sẽ unblock tất cả waiter threads ngay lập tức. Waiter threads sẽ nhận kết quả từ DB (B2) mà không có data từ Product Service.
      - Nếu leader timeout **trước khi gRPC được gọi**, waiter threads sẽ nhận kết quả DB tương tự.
    - **Track trạng thái gRPC:** Leader cần trả về kèm metadata:
      - `grpc_success`: true/false
      - `grpc_skus`: map `sku_id -> {is_available, current_price}` (chỉ khi grpc_success = true)
      - `grpc_error`: string error code nếu fail (dùng cho logging)
5. **B5 - Merge policy trước khi nạp lại Redis:**
    - **Bảo toàn các field lịch sử:** `unit_price_at_add`, `added_at`.
    - **Làm mới các field hiển thị có thể thay đổi:** `is_available`, `updated_at`.
    - **Không thêm `current_price` vào Redis JSON; `current_price` chỉ build ở response.**
    - **Xử lý theo trạng thái gRPC:**
      - **Nếu `grpc_success = true`:**
        - Với mỗi SKU, merge `is_available` từ `grpc_skus` vào item data.
        - **Đặc biệt:** Nếu `grpc_skus[sku_id].is_available = false` (product bị BANNED, deleted, hoặc out-of-stock), **bắt buộc ghi `is_available = false` vào merged data** để Lua Script ghi đúng giá trị này xuống Redis.
        - Nếu `grpc_skus[sku_id].is_available = true`, giữ nguyên giá trị từ DB (vì DB snapshot có thể cũ hơn nhưng không có nghĩa là unavailable).
      - **Nếu `grpc_success = false` (timeout/error):**
        - **KHÔNG merge** `is_available` từ Product Service.
        - Giữ nguyên `is_available` từ `product_snapshot` trong DB.
        - Set `is_price_degraded = true` ở response (để UI cảnh báo user giá có thể chưa cập nhật).
        - **Quan trọng:** KHÔNG ghi đè Redis bằng data không có metadata `is_price_degraded`. Lua Script chỉ ghi data đã merge, và data này không chứa thông tin degraded status. Hệ thống vẫn đảm bảo consistency vì Redis sẽ có data từ DB (đã có `is_available` snapshot).
    - **Output của B5:** Danh sách items đã merge + `grpc_success` flag (dùng cho B6 và response construction).
6. **B6 - Silent Rehydrate Lua Script (timestamp guard):**
    - Script nhận danh sách item đã merge (từ B5).
    - Script cũng nhận `grpc_success` flag qua ARGV để có thể log/debug nếu cần.
    - Với mỗi `sku_id`:
       - Nếu chưa có field trên Redis -> `HSET`.
       - Nếu đã có -> chỉ `HSET` khi `incoming.updated_at > redis.updated_at`.
    - **Tuyệt đối không `XADD` outbox** trong script rehydrate.
    - **is_available handling trong Lua:** Item đã được merge với `is_available` đúng từ B5, nên Lua chỉ việc ghi JSON đã chứa giá trị chính xác.
7. **B7 - TTL policy:**
    - Rehydrate là một thao tác phục hồi dữ liệu (write-recovery), được phép `EXPIRE $cart_key 604800` đúng 1 lần sau khi rehydrate thành công.
    - Quy tắc Read-Only TTL chỉ áp dụng cho read hit thông thường.
8. **B8 - Response construction:**
    - Nếu `grpc_success = true`: `is_price_degraded = false`, `current_price` được populate từ gRPC data.
    - Nếu `grpc_success = false`: `is_price_degraded = true`, `current_price` bằng `unit_price_at_add` (hoặc null nếu không có), UI hiển thị cảnh báo.

### 3.3. Grouping và Response
1. Group item theo `platform_id`.
2. Sắp xếp group theo `latest_item_at DESC` và item theo `added_at DESC`.
3. Trả response gồm cả giá lịch sử và giá runtime mới nhất:
    ```json
    {
       "items": [
          {
             "latest_item_at": 1708530000123,
             "items": [
                {
                   "sku_id": "sku-1001",
                   "product_name": "Áo Thun Nam",
                   "variant_info": "Màu Đen, Size L",
                   "image_url": "https://cdn.domain.com/ao.jpg",
                   "quantity": 2,
                   "unit_price_at_add": 150000,
                   "current_price": 165000,
                   "is_available": true,
                   "added_at": 1708530000123,
                   "updated_at": 1708616400456
                }
             ]
          }
       ],
       "is_price_degraded": false,
       "total_items": 2,
       "total_unique_skus": 2
    }
    ```

> `items.items[].quantity` trong response Cart là nguồn chuẩn để Checkout Preview consume. Frontend không được gửi quantity override ở API Checkout.

## 4. API: Batch Delete (Xóa lô sản phẩm sau Checkout tĩnh)
**Endpoint:** `DELETE /api/v1/carts/items` (Body: `cart_id` + mảng `sku_ids`)

> **Quy ước nghiệp vụ:** Contract chuẩn hóa dùng `cart_id` + `sku_ids` để xóa thẳng các SKU đã mua khỏi giỏ, tránh parse chuỗi ghép có dấu `:`.

**Quy trình chuẩn kiện (Workflow):**
1. **[Backend] - Chuẩn bị Payload:**
   - Nhận `cart_id` và mảng `sku_ids` từ event `ORDER_PAID`.
2. **[Redis] - Nguyên tử hóa qua Lua Script ([Xem code](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove)):**
   - Trước khi gọi Lua, App Server pre-register intent: `ZADD outbox_publish_intents <now_ms> <stream_key>`.
   - App Server chốt idempotency ở Go layer bằng key `cart:idemp:order_paid:{order_id}`: `SET NX EX 300` trước Lua, `SETEX DONE 24h` sau publish path thành công.
   - Truyền danh sách `sku_ids` vào Redis (không truyền idempotency key vào Lua).
   - Redis xóa thẳng từng field SKU (`HDEL`) có trong danh sách nếu SKU đó tồn tại và phát [`CART_ITEM_DELETED`](cart_events.md#23-cart_item_deleted).
   - Check chốt hạ nếu giỏ hàng rỗng `HLEN == 0` → Xóa nguyên Key bằng `DEL`.
   - Nếu luồng do user xóa thủ công: phát [`CART_CLEARED`](cart_events.md#24-cart_cleared).
   - Nếu luồng do checkout (`ORDER_PAID`) và giỏ rỗng hoàn toàn: phát [`CART_CHECKED_OUT`](cart_events.md#25-cart_checked_out). Nếu giỏ vẫn còn SKU khác (partial checkout), không phát event này và giỏ tiếp tục trạng thái `ACTIVE`.
   - Sau khi Lua thành công, Go phải chạy `ZADD active_outbox_streams` + `PUBLISH outbox_notifications`, rồi `ZREM outbox_publish_intents`.
3. **[Background] - Bất đồng bộ Database:**
   - Tương tự chiều Update, [Postgres Sync Worker](cart_workers.md#2-postgres-sync-consumer-worker) gặp Event Delete đi xử lý, gỡ dòng trong bảng Cold Store (`cart_items`). Áp dụng [Out-of-order validation cho DELETE](cart_idempotency.md#24-xử-lý-sai-thứ-tự-cho-delete-event).
   - Nếu nhận event `CART_CLEARED`, Update bảng `carts` chuyển `status` sang `DELETED`.
   - Nếu nhận event `CART_CHECKED_OUT`, Update bảng `carts` chuyển `status` sang `CHECKED_OUT`.

## 5. Cơ chế Circuit Breaker (Pull Model)

Để phòng ngừa lỗi Cascading Failure (sập dây chuyền nếu Product Service sụp trặc), các truy vấn lấy giá mới nhất sẽ được cấu hình quy tắc sau:
- **Thời gian chờ (Timeout):** Request gRPC sang Product Service bị giới hạn cứng thời gian thực thi (ví dụ tối đa 200ms).
- **Circuit Breaker Logic:** Nếu Product Service phản hồi quá 200ms hoặc báo lỗi, Circuit Breaker sẽ chuyển sang trạng thái Open. Cart Service tự động Bypass chốt chặn.
- **Half-Open Recovery:** Sau một khoảng `wait_duration_in_open_state`, Circuit Breaker phải vào trạng thái Half-Open và cho phép một số request thăm dò có giới hạn. Nếu thăm dò thành công thì đóng mạch (Closed); nếu tiếp tục lỗi thì quay lại Open.
- **Cảnh báo UI:** Phản hồi khi Circuit Breaker open đọc `product_snapshot` hiện đang lưu sẵn trong Redis/PostgreSQL ra hiện tạm để user vẫn lướt được giỏ hàng. Kèm theo đó là thông báo hiển thị nhẹ trên UI: _"Hệ thống đang quá tải, giá sản phẩm có thể chưa được cập nhật mới nhất"_.
- **Lợi ích kiến trúc:** Đảm bảo SLA cho màn hình xem giỏ hàng luôn nằm trong ngưỡng an toàn tuyệt đối. Cart Service không bao giờ sập kéo theo Product Service.
