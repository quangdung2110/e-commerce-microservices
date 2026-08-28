---
doc_id: cart-workflows
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/internal/domain/service
  - go/services/cart-service/internal/workers
  - go/services/cart-service/test/integration
verified_against_commit: null
---

# Cart Service — Workflows

> Mỗi workflow phải cho developer biết chính xác điều gì xảy ra khi một bước
> timeout hoặc khi cùng một request được gửi hai lần.

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-CART-001` | Thêm hoặc cập nhật item trong giỏ | API | Authenticated user | proposed |
| `WF-CART-002` | Đọc giỏ hàng khi trúng cache | API | Authenticated user | proposed |
| `WF-CART-003` | Phục hồi giỏ khi hụt cache | API (nhánh nội bộ) | Application | proposed |
| `WF-CART-004` | Xóa item theo lô | API | Authenticated user | proposed |
| `WF-CART-005` | Relay outbox từ Redis Stream sang Kafka | Continuous | Outbox Relay worker | proposed |
| `WF-CART-006` | Chiếu event xuống cold store | Event | Postgres Sync consumer | proposed |
| `WF-CART-007` | Dọn giỏ sau khi đơn hàng được thanh toán | Event | Order Event consumer | proposed |
| `WF-CART-008` | Đánh dấu giỏ bị bỏ quên | Schedule | Abandoned Cart Marker | proposed |

Hai tác vụ bảo trì thuần vận hành — Stream Trimmer và Data Archival Cleaner —
được mô tả tại [Operations §11](./operations.md#11-workers-và-scheduled-jobs).

---

## WF-CART-001 — Thêm hoặc cập nhật item trong giỏ

### Mục tiêu

Đưa một SKU vào giỏ hoặc thay đổi số lượng của SKU đã có, đảm bảo kết quả đúng
dưới tải song song và đảm bảo cold store cuối cùng nhận được thay đổi này.

### Actor và participants

Authenticated user khởi tạo. Participants: API Gateway (inject identity), Cart
application layer, Product Service (gRPC), Redis (cart hash và outbox stream).

### Trigger

`POST /api/v1/carts/items`.

### Preconditions

User đã đăng nhập; SKU và product ở trạng thái `ACTIVE`; giỏ chưa vượt `max_skus`
nếu đây là SKU mới.

### Input

Định danh SKU và số lượng mong muốn, theo
[OpenAPI](../../contracts/http/cart/v1/openapi.yaml). `user_id` đến từ header do
Gateway inject, không nằm trong body.

### Happy path

1. Handler đọc `X-User-Id`, dựng `cart_key = {cart}:{user_id}`, validate cú pháp request.
2. Application layer gọi `GetProductSku` qua gRPC với deadline 200ms.
3. Map dữ liệu Product thành snapshot: giá lấy giá khuyến mãi nếu có, ngược lại lấy
   giá gốc; ảnh và thông tin biến thể lấy từ SKU.
4. Validate nghiệp vụ: trạng thái sản phẩm phải `ACTIVE`; snapshot tồn kho hiển thị
   phải đủ cho số lượng yêu cầu.
5. Tính shard đích của outbox stream từ `user_id`.
6. Gọi [`add_or_update_item.lua`](./scripts/add_or_update_item.lua). Trong một lần
   thực thi nguyên tử, Redis kiểm tra `max_skus`, cộng dồn hoặc tạo mới item, chặn
   trần `max_qty`, `HSET` giá trị mới, gia hạn TTL 7 ngày, rồi `XADD` đúng một event
   vào stream shard.
7. Backend xóa negative cache `{cart}:{user_id}:notfound`.
8. Trả `201` nếu SKU mới được thêm, `200` nếu SKU đã có được cập nhật.

### State/data changes

Thay đổi duy nhất trong transaction là hash `{cart}:{user_id}` trên Redis, kèm entry
mới trong stream shard. Cold store thay đổi sau đó qua `WF-CART-005` và `WF-CART-006`;
thay đổi đó thuộc quyền của Cart Service, không của caller.

### Events emitted

`CART_ITEM_ADDED` hoặc `CART_ITEM_UPDATED`, tùy nhánh item mới hay đã tồn tại. Xem
[Interfaces §4](./interfaces.md#4-events-phát-ra).

### Output

Kết quả gồm `sku_id`, quantity sau thao tác và mốc cập nhật.

### Failure paths

| Điểm lỗi | Hành vi | State còn lại |
|---|---|---|
| Product Service timeout hoặc breaker mở | Từ chối thao tác vì không xác nhận được sản phẩm còn bán | Giỏ không đổi |
| Product trả sản phẩm không `ACTIVE` | `PRODUCT_NOT_AVAILABLE` | Giỏ không đổi |
| Giỏ đã đủ `max_skus` và SKU là mới | `CART_FULL` | Giỏ không đổi |
| `step` không hợp lệ hoặc giảm item chưa tồn tại | `STEP_INVALID` / `ITEM_NOT_FOUND` | Giỏ không đổi |
| Redis không khả dụng | Lỗi hạ tầng, trả `5xx` | Giỏ không đổi, không có event mồ côi |
| Lua script lỗi giữa chừng | Redis hủy toàn bộ script | Không có cửa sổ “đã ghi giỏ nhưng thiếu event” |
| Xóa negative cache thất bại sau khi Lua thành công | Ghi log; lần đọc kế tiếp có thể trả giỏ rỗng giả trong tối đa 60 giây | Giỏ đã đúng trên Redis |

### Timeout và retry

gRPC sang Product có deadline cứng 200ms và không retry mù trong request path.
Redis dùng timeout ngắn theo cấu hình client. Client được phép thử lại toàn bộ
request; xem mục idempotency ngay dưới.

### Idempotency

Contract yêu cầu `Idempotency-Key` cho endpoint này: cùng key và cùng payload trả
cùng kết quả logic, cùng key với payload khác trả `409`. Đây là điểm còn lệch giữa
contract và thiết kế cộng dồn trước đây, đã ghi tại
[Interfaces §6](./interfaces.md#6-compatibility). Ở tầng dữ liệu, mỗi lần thực thi
script sinh một `stream_id` mới, nên hai lần ghi giống hệt nhau vẫn là hai event
hợp lệ và cold store hội tụ về trạng thái cuối cùng.

### Concurrency/race conditions

Nhiều request cùng thao tác trên một SKU: cộng dồn nằm trong script đơn luồng nên
không mất lượt bấm. Request ghi chạy song song với luồng rehydrate: script rehydrate
chỉ ghi khi bản đến mới hơn nên không đè lên kết quả vừa ghi.

### Compensation hoặc recovery

Không có compensation. Nếu event chưa kịp relay, nó nằm lại an toàn trong stream
cho tới khi Kafka phục hồi.

### Metrics và traces

Latency và tỷ lệ lỗi theo endpoint, kết quả gọi Product, trạng thái circuit breaker,
tỷ lệ `CART_FULL`, thời gian thực thi script. Định danh theo dõi: `cart_id`,
`sku_id`, `stream_id`, request ID.

### Implementation evidence

`internal/api/handler`, `internal/domain/service`, `internal/infrastructure/redis`,
`internal/infrastructure/grpc`, [`scripts/add_or_update_item.lua`](./scripts/add_or_update_item.lua).

### Test scenarios

Thêm SKU mới; cộng dồn SKU đã có; nhiều request song song trên cùng SKU; chạm trần
`max_qty`; chạm trần `max_skus`; giảm item chưa tồn tại; sản phẩm không `ACTIVE`;
Product timeout; breaker mở; Redis lỗi; kiểm chứng đúng một event được sinh ra cho
mỗi lần ghi thành công.

---

## WF-CART-002 — Đọc giỏ hàng khi trúng cache

### Mục tiêu

Trả toàn bộ giỏ với giá lịch sử và giá hiện tại ở độ trễ thấp, và không bao giờ
thất bại chỉ vì Product Service đang lỗi.

### Actor và participants

Authenticated user; Cart application layer; Redis; Product Service.

### Trigger

`GET /api/v1/carts/mine`.

### Preconditions

User đã đăng nhập.

### Happy path

1. Kiểm tra negative cache `{cart}:{user_id}:notfound`; nếu tồn tại, trả giỏ rỗng ngay.
2. `HGETALL` cart key. Nếu rỗng, chuyển sang `WF-CART-003`.
3. Decode từng field thành item và gom tập `sku_id`.
4. Gọi `GetBatchProductSkus` một lần cho toàn bộ tập SKU, deadline 200ms, bọc
   circuit breaker.
5. Phủ dữ liệu runtime lên response: giá hiện tại và trạng thái khả dụng mới nhất.
6. Nếu Product trả `is_available = false` cho một SKU, ghi lại đúng một field
   `is_available = false` vào Redis bằng `HSET`, không đụng TTL và không đụng field
   khác (`CART-BR-009`).
7. Dựng response kèm chỉ báo độ tươi của giá.

### State/data changes

Không có thay đổi state, trừ ngoại lệ ghi lại `is_available = false` ở bước 6. Đây
là ngoại lệ duy nhất của quy tắc Read-Only TTL và nó không sinh event.

### Events emitted

Không có.

### Output

Danh sách item kèm `unit_price_at_add`, `current_price`, `is_available`, tổng số
lượng và tổng số SKU, cùng cờ độ tươi của giá.

### Failure paths

| Điểm lỗi | Hành vi |
|---|---|
| Product timeout hoặc lỗi | Dùng snapshot trong Redis, đánh dấu giá là suy giảm, vẫn trả `200` |
| Circuit breaker mở | Không gọi Product, trả ngay dữ liệu suy giảm |
| Redis không khả dụng | Lỗi hạ tầng, trả `5xx`; không có fallback đọc thẳng cold store trong request path |
| Ghi lại `is_available` thất bại | Ghi log; response vẫn đúng, lần đọc sau sẽ thử lại |

### Timeout và retry

Deadline gRPC 200ms. Không retry trong request path; breaker chuyển sang half-open
sau khoảng chờ cấu hình và cho phép một số request thăm dò.

### Idempotency

Thao tác đọc, tự nhiên idempotent.

### Concurrency/race conditions

Đọc chạy song song với ghi: `HGETALL` có thể bắt được trạng thái ngay trước hoặc
ngay sau một lần ghi, cả hai đều là trạng thái hợp lệ. Ghi lại `is_available` chỉ
chạm đúng một field nên không ghi đè quantity đang được cập nhật song song.

### Compensation hoặc recovery

Không có. Dữ liệu giá sai lệch tự khỏi ở lần đọc kế tiếp khi Product phục hồi.

### Metrics và traces

Latency đọc, tỷ lệ cache hit, tỷ lệ response suy giảm, tỷ lệ lỗi và trạng thái
breaker của Product client.

### Implementation evidence

`internal/api/handler`, `internal/domain/service`, `internal/common/circuitbreaker`.

### Test scenarios

Giỏ rỗng; giỏ có nhiều SKU; Product trả thiếu SKU; Product timeout; breaker mở;
SKU bị gỡ bán và kiểm chứng Redis được ghi lại đúng một field; kiểm chứng TTL không
bị gia hạn ở luồng đọc.

---

## WF-CART-003 — Phục hồi giỏ khi hụt cache

### Mục tiêu

Nạp lại giỏ từ cold store khi Redis mất key, mà không sinh event rác và không đè
lên thao tác ghi đang chạy song song.

### Actor và participants

Application layer (nhánh nội bộ của `WF-CART-002`); PostgreSQL; Product Service; Redis.

### Trigger

`HGETALL` trả rỗng trong luồng đọc giỏ.

### Preconditions

Không có negative cache cho user này.

### Happy path

1. Đọc trạng thái giỏ trong bảng `carts`. Nếu là `DELETED` hoặc `CHECKED_OUT`, trả
   giỏ rỗng và dừng.
2. Đọc các dòng `cart_items` theo `cart_id`.
3. Nếu không có dòng nào, đặt negative cache TTL 60 giây và trả giỏ rỗng.
4. Gọi `GetBatchProductSkus` với deadline 200ms và circuit breaker; ghi nhận rõ lời
   gọi thành công hay thất bại.
5. Merge theo chính sách: giữ nguyên `unit_price_at_add` và `added_at`; làm mới
   `is_available` và `updated_at`; không đưa `current_price` vào dữ liệu ghi Redis;
   ghép `unit_price_at_add` từ cột riêng vào JSON trước khi ghi.
6. Nếu gọi Product thất bại, không merge `is_available` mà giữ giá trị từ snapshot
   cold store, và đánh dấu response là suy giảm.
7. Gọi [`silent_rehydrate.lua`](./scripts/silent_rehydrate.lua): chỉ `HSET` khi field
   chưa tồn tại hoặc bản đến có `updated_at` mới hơn; không `XADD`.
8. Đặt lại TTL 7 ngày đúng một lần — rehydrate là write-recovery nên được phép
   (`CART-BR-010`).
9. Dựng response từ dữ liệu đã merge.

### State/data changes

Redis được nạp lại. Cold store không thay đổi.

### Events emitted

Không có. Đây là ràng buộc bắt buộc (`CART-INV-004`).

### Output

Giỏ hàng đầy đủ, có thể kèm cờ suy giảm giá.

### Failure paths

| Điểm lỗi | Hành vi |
|---|---|
| PostgreSQL lỗi | Trả `5xx`; không đặt negative cache để tránh ghi nhớ trạng thái sai |
| Product lỗi | Vẫn rehydrate bằng snapshot cold store, response suy giảm |
| Script rehydrate lỗi | Trả dữ liệu đã merge cho client, Redis vẫn rỗng, lần đọc sau thử lại |

### Timeout và retry

Deadline gRPC 200ms; timeout truy vấn cold store theo cấu hình pool. Không retry
trong request path.

### Idempotency

Chạy lại rehydrate nhiều lần cho ra cùng kết quả nhờ so sánh `updated_at` theo từng
field, và không sinh event nên không ảnh hưởng downstream.

### Concurrency/race conditions

Nhiều request cùng hụt cache cho một user có thể cùng rehydrate; kết quả vẫn hội tụ
vì script chỉ ghi bản mới hơn. Rehydrate chạy song song với một request thêm hàng:
dữ liệu cold store cũ hơn sẽ không đè lên item vừa được thêm.

### Compensation hoặc recovery

Không có. Nếu cold store thiếu dữ liệu do event chưa được chiếu xuống, phần thiếu sẽ
xuất hiện sau khi consumer bắt kịp.

### Metrics và traces

Tỷ lệ cache miss, latency rehydrate, số item được nạp lại, tỷ lệ negative cache,
tỷ lệ rehydrate suy giảm.

### Implementation evidence

`internal/domain/service`, `internal/infrastructure/postgres`,
[`scripts/silent_rehydrate.lua`](./scripts/silent_rehydrate.lua).

### Test scenarios

Cold store còn dữ liệu; cold store rỗng và kiểm chứng negative cache; giỏ ở trạng
thái `DELETED`; Product lỗi; rehydrate đồng thời với thao tác ghi; kiểm chứng không
có event nào được sinh ra; kiểm chứng `unit_price_at_add` xuất hiện đúng trong Redis
sau khi nạp lại.

---

## WF-CART-004 — Xóa item theo lô

### Mục tiêu

Xóa một tập SKU khỏi giỏ trong một thao tác nguyên tử, và phát đúng tín hiệu vòng
đời khi giỏ trở nên rỗng.

### Actor và participants

Authenticated user hoặc Order Event consumer (`WF-CART-007`); Redis.

### Trigger

`DELETE /api/v1/carts/items`, hoặc lời gọi nội bộ từ consumer.

### Preconditions

User đã đăng nhập; danh sách `sku_ids` không rỗng và tối đa 100 phần tử.

### Happy path

1. Dựng `cart_key` từ identity và tính shard đích của stream.
2. Gọi [`batch_delete_items.lua`](./scripts/batch_delete_items.lua) với danh sách
   `sku_ids` và loại event kết thúc (`CART_CLEARED` cho luồng user, `CART_CHECKED_OUT`
   cho luồng thanh toán).
3. Script duyệt danh sách, gom các SKU thực sự tồn tại, phát `CART_ITEM_DELETED` cho
   từng SKU đó, rồi `HDEL` một lần cho cả nhóm.
4. Nếu giỏ rỗng sau khi xóa, script `DEL` cart key và phát đúng loại event kết thúc.
5. Backend xóa negative cache và trả số lượng đã xóa.

### State/data changes

Hash giỏ mất các field tương ứng; khi rỗng thì key bị xóa hẳn. Cold store được cập
nhật sau qua `WF-CART-006`.

### Events emitted

`CART_ITEM_DELETED` cho mỗi SKU thực sự bị xóa, cộng thêm `CART_CLEARED` hoặc
`CART_CHECKED_OUT` nếu giỏ rỗng hoàn toàn.

### Output

`deleted_count`.

### Failure paths

| Điểm lỗi | Hành vi |
|---|---|
| SKU không tồn tại trong giỏ | Bỏ qua, không tính vào `deleted_count`, không phát event |
| Redis không khả dụng | Trả `5xx`; giỏ không đổi |
| Script lỗi giữa chừng | Redis hủy toàn bộ; không có tình trạng xóa một nửa kèm event lệch |

### Timeout và retry

Timeout client Redis. Thao tác an toàn khi retry vì xóa là idempotent theo `sku_id`.

### Idempotency

Xóa lại cùng danh sách lần hai cho `deleted_count = 0` và không sinh event. Ở tầng
HTTP, contract yêu cầu `Idempotency-Key` như endpoint ghi còn lại.

### Concurrency/race conditions

Xóa chạy song song với thêm hàng: cả hai đều là script nguyên tử nên thứ tự thực thi
là tuần tự trên Redis, và `stream_id` phản ánh đúng thứ tự đó cho downstream.

### Compensation hoặc recovery

Không có; thao tác xóa là chủ đích của người dùng hoặc hệ quả của đơn hàng đã thanh toán.

### Metrics và traces

Số SKU xóa mỗi lần gọi, tỷ lệ giỏ trở thành rỗng, phân bố loại event kết thúc.

### Implementation evidence

`internal/api/handler`, `internal/infrastructure/redis`,
[`scripts/batch_delete_items.lua`](./scripts/batch_delete_items.lua).

### Test scenarios

Xóa một phần; xóa toàn bộ dẫn tới `CART_CLEARED`; xóa toàn bộ trong luồng thanh toán
dẫn tới `CART_CHECKED_OUT`; danh sách chứa SKU không tồn tại; xóa lặp lại; xóa song
song với thêm hàng; kiểm chứng key bị `DEL` khi giỏ rỗng.

---

## WF-CART-005 — Relay outbox từ Redis Stream sang Kafka

### Mục tiêu

Chuyển mọi event trong outbox stream sang Kafka đúng một lần về mặt hiệu lực, và
không bao giờ mất event khi worker hoặc Kafka gặp sự cố.

### Actor và participants

Outbox Relay worker; Redis Streams; Kafka.

### Trigger

Chạy liên tục.

### Preconditions

Consumer group tồn tại trên từng shard stream.

### Happy path

1. Worker khởi tạo consumer group cho mỗi shard.
2. Với mỗi shard, `XREADGROUP` theo lô với block time ngắn.
3. Với mỗi message, trích `event_type` và payload, sinh định danh event theo cách
   xác định từ `stream_id` và `cart_id`, bổ sung các header thứ tự (`stream_id`, phần
   timestamp và phần sequence của nó) cùng metadata chuẩn.
4. Produce sang `t.cart.events` với partition key là `cart_id` và chờ xác nhận đầy đủ.
5. `XACK` message sau khi Kafka đã xác nhận.

### State/data changes

Không thay đổi cart state. Chỉ thay đổi trạng thái pending của stream.

### Events emitted

Chính là các event Cart đã mô tả tại [Interfaces §4](./interfaces.md#4-events-phát-ra).

### Output

Message trên `t.cart.events`.

### Failure paths

| Điểm lỗi | Hành vi | State còn lại |
|---|---|---|
| Kafka lỗi hoặc timeout | Không `XACK` | Message ở trạng thái pending, sẽ được xử lý lại |
| Worker crash sau `XREADGROUP`, trước `XACK` | Redis giữ message ở pending | Worker khác nhặt lại bằng `XAUTOCLAIM` sau ngưỡng thời gian |
| Payload hỏng không parse được | Ghi log và đưa vào đường xử lý lỗi | Cần quyết định chuyển sang dead-letter thay vì chặn shard |
| Kafka lỗi kéo dài | Stream phình to | Rủi ro bộ nhớ Redis; xem runbook tại [Operations](./operations.md#10-runbooks) |

### Timeout và retry

Retry với backoff khi produce lỗi. Không giới hạn số lần thử với lỗi tạm thời, vì bỏ
message đồng nghĩa mất dữ liệu.

### Idempotency

Định danh event xác định từ `stream_id` và `cart_id` nên mọi lần gửi lại đều mang
cùng định danh, cho phép downstream dedupe. Delivery vẫn là at-least-once.

### Concurrency/race conditions

Mỗi shard được một consumer group phục vụ; nhiều instance chia nhau các shard. Thứ
tự trong một shard được giữ theo thứ tự stream, và thứ tự theo giỏ được giữ tiếp ở
Kafka nhờ partition key.

### Compensation hoặc recovery

`XAUTOCLAIM` cho message pending quá ngưỡng. Nếu một shard bị kẹt, tất cả event của
các user thuộc shard đó bị chậm — đây là đánh đổi đã ghi ở
[ADR-003](./decisions/ADR-003-global-sharded-stream.md).

### Metrics và traces

Độ dài stream theo shard, số message pending, tuổi message pending lâu nhất, tỷ lệ
produce lỗi, độ trễ từ lúc `XADD` tới lúc `XACK`.

### Implementation evidence

`internal/workers/outbox_relay`, `internal/infrastructure/kafka`.

### Test scenarios

Relay bình thường; Kafka lỗi rồi phục hồi; worker crash trước `XACK`; `XAUTOCLAIM`
nhặt lại message; kiểm chứng định danh event không đổi qua các lần retry; kiểm chứng
không `XACK` khi produce thất bại.

---

## WF-CART-006 — Chiếu event xuống cold store

### Mục tiêu

Giữ `carts` và `cart_items` hội tụ đúng trạng thái Redis, bất chấp message trùng lặp
hoặc đến sai thứ tự.

### Actor và participants

Postgres Sync consumer; Kafka; PostgreSQL.

### Trigger

Message trên `t.cart.events`, consumer group `cart-db-sync`.

### Preconditions

Schema cold store đã được tạo.

### Happy path

1. Poll một lô message.
2. Nhóm theo `cart_id`; message của cùng một giỏ nằm cùng partition nên giữ đúng thứ tự.
3. Với mỗi `cart_id`, mở một transaction và trong đó:
   - Upsert các dòng `cart_items` cho event thêm và cập nhật, có guard `stream_id`;
   - Hard delete các dòng cho event xóa, có guard `stream_id`;
   - Cập nhật dòng master `carts`, có guard `last_event_stream_id`.
4. Commit offset sau khi mọi transaction trong lô thành công.

### State/data changes

| Event | Thay đổi |
|---|---|
| `CART_ITEM_ADDED` / `CART_ITEM_UPDATED` | Upsert dòng item; đưa `carts.status` về `ACTIVE` nếu `stream_id` mới hơn |
| `CART_ITEM_DELETED` | Xóa hẳn dòng item |
| `CART_CLEARED` | Chỉ đổi `carts.status` sang `DELETED`; **không** xóa `cart_items` |
| `CART_CHECKED_OUT` | Chỉ đổi `carts.status` sang `CHECKED_OUT`; **không** xóa `cart_items` |
| `CART_ABANDONED` | Trạng thái đã do cronjob ghi; consumer không cần đổi thêm |

Lý do `CART_CLEARED` và `CART_CHECKED_OUT` không xóa item: từng item đã bị xóa trước
đó bởi các `CART_ITEM_DELETED` riêng lẻ trong cùng thao tác batch delete.

### Events emitted

Không có.

### Output

Cold store phản chiếu đúng trạng thái Redis.

### Failure paths

| Điểm lỗi | Hành vi |
|---|---|
| SQL lỗi trong một transaction | Rollback transaction của `cart_id` đó, không commit offset của lô |
| Một giỏ lỗi nhưng giỏ khác thành công | Các giỏ đã commit vẫn giữ; toàn bộ lô được redeliver và guard `stream_id` khiến phần đã ghi không bị hỏng |
| Lỗi kéo dài sau nhiều lần thử | Ghi log chi tiết và chuyển sang đường xử lý lỗi để không chặn partition vô hạn |
| PostgreSQL không khả dụng | Consumer dừng commit; lag tăng; cart API vẫn hoạt động bình thường |

### Timeout và retry

Retry với exponential backoff ở mức lô. Vì offset chỉ được commit khi cả lô thành
công, redeliver là cơ chế retry chính.

### Idempotency

Mọi câu lệnh ghi đều có guard so sánh `stream_id`. Event trùng lặp không đổi dữ liệu;
event cũ đến muộn không khớp dòng nào. Trường hợp `stream_id` bằng đúng giá trị đã
lưu được coi là duplicate và bỏ qua.

### Concurrency/race conditions

Nhiều consumer chạy song song trên các partition khác nhau. Vì partition key là
`cart_id`, hai message của cùng một giỏ không bao giờ được xử lý đồng thời bởi hai
consumer.

### Compensation hoặc recovery

Replay từ offset cũ là cách sửa lệch dữ liệu, an toàn nhờ guard `stream_id`. Nếu
lệch xảy ra ngoài cửa sổ retention Kafka, nguồn chân lý vẫn là Redis và cold store
sẽ hội tụ lại từ các thao tác kế tiếp của người dùng.

### Metrics và traces

Consumer lag, độ trễ từ `stream_id` tới lúc ghi xong, số dòng bị guard bỏ qua, tỷ lệ
transaction rollback, kích thước lô.

### Implementation evidence

`internal/workers/postgres_sync`, `internal/infrastructure/postgres`,
[`schema/0001_initial_cart_schema.sql`](./schema/0001_initial_cart_schema.sql).

### Test scenarios

Event trùng lặp; event sai thứ tự cho item và cho trạng thái giỏ; delete đến trước
add; giỏ được tái kích hoạt sau `CHECKED_OUT`; một giỏ lỗi trong lô nhiều giỏ; kiểm
chứng `CART_CLEARED` không xóa `cart_items`; kiểm chứng offset không commit khi lô lỗi.

---

## WF-CART-007 — Dọn giỏ sau khi đơn hàng được thanh toán

### Mục tiêu

Xóa đúng các SKU đã mua khỏi giỏ, không nhiều hơn, và đánh dấu giỏ là đã thanh toán
khi không còn gì.

### Actor và participants

Order Event consumer; Order Service (producer); Redis.

### Trigger

`ORDER_PAID` trên `t.order.events`, consumer group `cart-order-consumer`.

### Preconditions

Payload có `order_id`, `user_id` và `sku_ids` không rỗng.

### Happy path

1. Lọc message theo loại `ORDER_PAID`.
2. Validate `user_id` và `sku_ids`; dựng `cart_id` từ `user_id`.
3. Chốt khóa idempotency `cart:idemp:order_paid:{order_id}` bằng thao tác đặt-nếu-chưa-có
   với TTL ngắn. Nếu khóa đã ở trạng thái hoàn tất, bỏ qua message.
4. Gọi batch delete (`WF-CART-004`) với loại event kết thúc là `CART_CHECKED_OUT`.
5. Đánh dấu khóa idempotency là hoàn tất với TTL dài.
6. Commit offset.

### State/data changes

Các SKU đã mua biến mất khỏi giỏ. Nếu giỏ rỗng, key bị xóa và cold store chuyển sang
`CHECKED_OUT` qua `WF-CART-006`. Thanh toán một phần giữ giỏ ở `ACTIVE`
(`CART-BR-011`).

### Events emitted

`CART_ITEM_DELETED` cho từng SKU bị xóa; `CART_CHECKED_OUT` nếu giỏ rỗng hoàn toàn.

### Output

Không có response đồng bộ; kết quả là thay đổi state và event.

### Failure paths

| Điểm lỗi | Hành vi |
|---|---|
| Thiếu `user_id` hoặc `sku_ids` rỗng | Ghi log lỗi contract, bỏ qua message, commit offset |
| SKU đã bị user xóa trước đó | Script bỏ qua, không lỗi |
| Redis lỗi sau khi đặt khóa nhưng trước khi xóa | Khóa hết TTL ngắn và message được redeliver, xử lý lại an toàn |
| Crash sau khi xóa nhưng trước khi đánh dấu hoàn tất | Redeliver dẫn tới lần xóa thứ hai với `deleted_count = 0`, không phát event thừa |

### Timeout và retry

Retry theo cơ chế redelivery của Kafka. TTL ngắn của khóa đang-xử-lý phải đủ dài để
bao trọn một lần xử lý và đủ ngắn để không chặn retry hợp lệ.

### Idempotency

Khóa `cart:idemp:order_paid:{order_id}` là điểm chốt chính. Thao tác xóa bản thân nó
cũng idempotent, nên đây là hai lớp bảo vệ độc lập.

### Concurrency/race conditions

Hai instance nhận cùng `order_id` do rebalance: chỉ một instance đặt được khóa. User
thao tác giỏ đồng thời với luồng dọn: cả hai đều là script nguyên tử nên không mất
dữ liệu, chỉ khác nhau về thứ tự ghi nhận.

### Compensation hoặc recovery

Không có; SKU đã mua không được đưa lại vào giỏ.

### Metrics và traces

Consumer lag, số SKU xóa mỗi đơn, tỷ lệ message bị bỏ do lỗi contract, tỷ lệ trúng
khóa idempotency. Định danh theo dõi: `order_id`, `user_id`, `cart_id`.

### Implementation evidence

`internal/workers/order_consumer`,
[`scripts/batch_delete_items.lua`](./scripts/batch_delete_items.lua).

### Test scenarios

Thanh toán toàn bộ giỏ; thanh toán một phần; `ORDER_PAID` trùng lặp; payload thiếu
field; SKU không còn trong giỏ; crash giữa xóa và đánh dấu hoàn tất; hai consumer
cùng nhận một đơn.

---

## WF-CART-008 — Đánh dấu giỏ bị bỏ quên

### Mục tiêu

Chuyển các giỏ `ACTIVE` không tương tác quá ngưỡng sang `ABANDONED` và phát tín hiệu
để Notification chạy luồng chăm sóc lại.

### Actor và participants

Abandoned Cart Marker cronjob; PostgreSQL; Redis Streams.

### Trigger

Lịch chạy hằng ngày.

### Preconditions

Cronjob chạy singleton.

### Happy path

1. Quét theo lô nhỏ các giỏ `ACTIVE` có `updated_at` cũ hơn ngưỡng, sắp xếp từ cũ nhất.
2. Với mỗi giỏ, cập nhật trạng thái sang `ABANDONED` với điều kiện trạng thái hiện
   tại vẫn là `ACTIVE`, đồng thời đặt lại mốc đã-publish về rỗng.
3. Chỉ khi cập nhật thực sự khớp một dòng, `XADD` `CART_ABANDONED` vào đúng shard
   stream của user.
4. Sau khi `XADD` thành công, ghi mốc đã-publish.
5. Event đi tiếp theo đúng đường `WF-CART-005`.

### State/data changes

`carts.status` chuyển sang `ABANDONED`; mốc đã-publish được cập nhật. Redis cart key
không bị đụng tới — giỏ trên Redis có thể đã hết TTL từ lâu.

### Events emitted

`CART_ABANDONED`.

### Output

Số giỏ được đánh dấu trong một lượt chạy.

### Failure paths

| Điểm lỗi | Hành vi |
|---|---|
| Crash sau khi đổi trạng thái, trước `XADD` | Lượt chạy sau tìm các dòng `ABANDONED` chưa publish trong cửa sổ gần đây và publish bù |
| `XADD` thành công nhưng ghi mốc thất bại | Lượt chạy sau publish bù, tạo event trùng; downstream dedupe theo định danh event |
| PostgreSQL lỗi giữa lô | Lô dừng; lượt chạy sau tiếp tục từ các giỏ cũ nhất |

### Timeout và retry

Chia lô nhỏ để tránh giữ lock lớn và để một lượt chạy có thể dừng an toàn giữa chừng.

### Idempotency

Cập nhật trạng thái có điều kiện `status = 'ACTIVE'` nên chạy lại không đánh dấu hai
lần. Publish bù có thể tạo event trùng — đây là đánh đổi at-least-once đã chấp nhận.

### Concurrency/race conditions

User quay lại thêm hàng ngay lúc cronjob đánh dấu: event thêm hàng có `stream_id`
mới hơn nên `WF-CART-006` sẽ đưa giỏ về `ACTIVE`, và trạng thái cuối cùng là đúng.

### Compensation hoặc recovery

Bước reconcile publish bù chính là cơ chế phục hồi.

### Metrics và traces

Số giỏ đánh dấu mỗi lượt, thời gian chạy, số event publish bù, độ trễ so với lịch.

### Implementation evidence

`internal/workers/cronjobs`.

### Test scenarios

Đánh dấu bình thường; crash giữa cập nhật và publish; publish bù; user quay lại ngay
sau khi bị đánh dấu; lô lớn bị dừng giữa chừng; chạy lại lượt cron trong cùng ngày.

---

## Yêu cầu test chung

Mọi workflow phải có test cho happy path, biên giá trị, input hoặc event trùng lặp,
timeout, retry, request đồng thời, lỗi của Redis/Kafka/PostgreSQL/Product, và đường
phục hồi. Trạng thái thực tế của bộ test là hạng mục cần xác minh trước khi tài liệu
được nâng lên `verified`.
