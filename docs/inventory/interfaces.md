---
doc_id: inventory-interfaces
service: inventory
scope: current
status: proposed
owners:
  - Inventory Service team
evidence:
  - contracts/http/inventory/v1/openapi.yaml
  - contracts/http/inventory/internal/v1/openapi.yaml
  - contracts/events/inventory/v1/asyncapi.yaml
verified_against_commit: null
---

# Inventory Service — Interfaces

> File này trả lời: “Service nhận và gửi thông tin gì?” Machine-readable contract là
> nguồn chuẩn cho payload; file này giải thích semantics và hành vi. Không copy schema
> từ OpenAPI/AsyncAPI vào đây.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumers | Contract | Status |
|---|---|---|---|---|---|---|
| `INV-IF-HTTP-V1` — API tồn kho | Inbound | HTTP | Inventory | Checkout, Admin, Order | [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml) | proposed |
| `INV-IF-EVT` — event trên `t.inventory.events` | Outbound | Kafka | Inventory | Order, Search, Analytics | Chưa có contract machine-readable | đang chạy |
| `INV-IF-ACT` — Reserve/Deduct/Release | Inbound | Temporal SDK | Inventory | Order Saga | Chưa có contract machine-readable | đang chạy |
| `INV-IF-HTTP-INT` — export snapshot | Inbound | HTTP nội bộ | Inventory | Search, Product Detail BFF | [Internal OpenAPI v1](../../contracts/http/inventory/internal/v1/openapi.yaml) | **target, chưa implement** |
| `INV-IF-EVT-STOCK-V1` — stock snapshot | Outbound | Kafka | Inventory | Search, Product Detail BFF | [AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml) | **target, chưa implement** |
| `INV-IF-EVT-MUT` — `INVENTORY_STOCK_MUTATION` | Nội bộ | Redis Stream → Kafka | Inventory | Chính Inventory | Không phải public contract | internal |

`INV-IF-EVT-MUT` xuất hiện trên một topic Kafka dùng chung nên trông giống event
public, nhưng nó là **transport nội bộ** giữa hai worker của cùng service. Consumer
ngoài không được xây dựng logic dựa trên nó.

## 2. HTTP APIs

Path, status code và schema nằm trong OpenAPI. Bảng dưới giải thích những gì OpenAPI
không nói được.

| Nhóm | Mục đích nghiệp vụ | Caller | Auth | Idempotency |
|---|---|---|---|---|
| `POST /v1/inventory/routing-options` | Chọn kho cho giỏ hàng và cấp `route_plan_id` có hạn | Checkout | Gateway identity, user đã đăng nhập | Không mutate; gọi lại sinh plan mới |
| `POST /v1/inventory/restock` | Nhập hàng mới hoặc nhận hàng hoàn về kho | Admin | Gateway identity, role `ADMIN` | **Không idempotent** — mỗi lần gọi cộng thêm |
| `POST /v1/inventory/ship-to-customer` | Báo Order rằng kho đã xuất hàng | Admin | Gateway identity, role `ADMIN` | An toàn khi gọi lại: consumer dedupe theo `event_id` |
| `POST /v1/inventory/returns/refund-expectations` | Order khóa tập item sẽ hoàn về | Order Service | Service identity m2m | Idempotent theo `(order_id, refund_request_id, order_item_ids)` |
| `POST /v1/inventory/admin/unlock-sku` | Gỡ đóng băng sau khi đã đối soát | Admin | Gateway identity, role `ADMIN` | Gọi lại trên SKU đã mở trả `SKU_NOT_SUSPENDED` |
| `GET /internal/v1/inventory/stock-snapshots` *(target, chưa implement)* | Bootstrap/recovery projection tồn kho | Search, Product Detail BFF | Service identity, không qua Gateway | Read-only, keyset ổn định theo `sku_id` |

**Precondition đáng chú ý:**

- Restock yêu cầu kho tồn tại và `is_active = true`. Restock hàng hoàn còn yêu cầu tồn
  tại reservation `DEDUCTED` khớp `(order_id, order_item_id)` và reservation đó đã
  được đánh dấu `refund_expected`.
- Ship-to-customer yêu cầu có ít nhất một reservation `DEDUCTED` tại đúng
  `(order_id, warehouse_id)`. Bỏ điều kiện `warehouse_id` sẽ khiến item ở kho khác bị
  đánh dấu đã giao — xem [ADR-009](./decisions/ADR-009-ship-signal-without-ledger.md).
- Lập route plan không giữ chỗ. Plan hợp lệ không bảo đảm reserve sẽ thành công.

**Postcondition:** mọi HTTP command làm thay đổi tồn kho đều để lại một dòng ledger và
một outbox row trong cùng transaction trước khi trả 200.

**Idempotency của restock là rủi ro đã biết.** Không có `Idempotency-Key` vì schema
không có nơi lưu kết quả command. Admin gặp timeout phải tra `inventory_ledger` theo
`correlation_id` trước khi gửi lại, nếu không sẽ nhập kho hai lần.

**Error semantics:** lỗi trả về dạng `{error, message, details, timestamp}` với `error`
là domain error tại [Domain §8](./domain.md#8-domain-errors). Đây **không** phải RFC 9457
Problem Details mà Product và Auth dùng.

**Ba điểm lệch quy ước chung của dự án** — đều là drift đã biết, ghi ở
[Known gaps](./README.md#9-known-gaps):

| Điểm | Inventory | Phần còn lại của dự án |
|---|---|---|
| Tiền tố path | `/v1/inventory/...` | `/api/v1/...` |
| Đặt tên field JSON | camelCase | snake_case |
| Định dạng lỗi | `{error, message, details, timestamp}` | RFC 9457 Problem Details |

## 3. Temporal Activities

Reserve, Deduct và Release **không phải HTTP endpoint**. Order Saga gọi chúng qua
Temporal SDK, nên chúng không xuất hiện trong OpenAPI và không đi qua Gateway.

| Activity | Mục đích | Input chính | Kết quả |
|---|---|---|---|
| `ReserveInventoryActivity` | Giữ chỗ trước thanh toán | `order_id`, `route_plan_id`, danh sách item kèm `warehouse_id` | Thành công, hoặc lỗi phân loại được |
| `DeductInventoryActivity` | Trừ hàng sau thanh toán thành công | `order_id`, danh sách item kèm `warehouse_id` | như trên |
| `RollbackInventoryActivity` | Trả lại phần đã giữ chỗ | `order_id`, danh sách item, `change_type` | như trên |

`change_type` của Rollback là `ORDER_CANCELLED` (compensation) hoặc
`RESERVATION_EXPIRED` (hết hạn thanh toán). Hai giá trị này chỉ khác nhau ở ledger;
tác động tồn kho giống hệt nhau — xem `INV-BR-012`.

**Ánh xạ kết quả Lua sang Activity result:**

| Kết quả script | Activity result | Order Saga làm gì |
|---|---|---|
| Áp dụng mới | Success | Đi tiếp |
| Idempotent hit | Success | Đi tiếp; **không** phát thêm event |
| `OUT_OF_STOCK` | Business failure | Compensation |
| `INSUFFICIENT_RESERVED` | Business failure | Điều tra; không compensation tự động |
| `RELEASE_UNDERFLOW` | Business failure | Điều tra |
| `SKU_SUSPENDED` | Business failure | Chờ admin mở khóa |
| `INVARIANT_DRIFT` | Activity failure không retry | Alert vận hành, dừng luồng |

**Deadline và retry:** deadline của Activity và retry policy do Order Saga cấu hình,
không phải Inventory. Inventory chỉ bảo đảm rằng retry cùng `(op, sku, kho, order)` là
an toàn nhờ idempotency key. **Retry với `INVARIANT_DRIFT` là sai** — Order phải cấu
hình lỗi này là non-retryable.

**Timer hết hạn giữ chỗ thuộc Order.** Inventory ghi `expires_at` lên reservation
nhưng không có worker nào quét nó; giá trị đó phục vụ dashboard và điều tra. Xem
[ADR-006](./decisions/ADR-006-reservation-expiry-owned-by-order.md).

## 4. Events phát ra

### 4.1 Stock snapshot v1 — contract đích, chưa implement

Contract dưới đây đã có AsyncAPI nhưng **chưa có gì ở phía provider**: schema không có
bảng `sku_stock_state`, và ràng buộc `event_type` của bảng outbox hiện cấm insert loại
event này. Xem [Data §5](./data.md#5-khoảng-cách-giữa-thiết-kế-đích-và-schema-hiện-tại).

| Thuộc tính | Giá trị |
|---|---|
| Event | `com.ecommerce.inventory.stock-snapshot.v1` |
| Topic | `t.inventory.stock.events.v1` |
| Partition/ordering key | `sku_id` |
| Ordering guard | `stock_revision` — consumer chỉ áp dụng revision lớn hơn |
| Dedup key | CloudEvents `id` |
| Delivery | At-least-once |
| Consumer | Search, Product Detail BFF |
| Cơ chế | `inventory_outbox` + Debezium CDC |
| Contract | [AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml) |

**Khi nào phát:** mỗi khi tổng `available_quantity` của một SKU thay đổi, trong cùng
transaction đã ghi mutation. Snapshot mang **toàn bộ** state per-SKU chứ không phải
delta, nên consumer mất event vẫn hội tụ được khi nhận event sau.

Snapshot không mang tồn kho per-warehouse và không mang reservation. Đó là chủ đích:
Search và Product Detail BFF chỉ hiển thị khả dụng UI, không hiển thị hàng nằm ở kho nào
và không dùng snapshot để reserve.

### 4.2 Event đang chạy trên `t.inventory.events`

Ba event dưới đây chưa có contract machine-readable. Chúng dùng CloudEvents wrapper
theo [quy ước event chung](../../contracts/conventions/events.md), nhưng vì không có
AsyncAPI nên không có gì kiểm chứng được rằng producer thực sự tuân thủ.

| Event | Khi nào phát | Key | Consumer | Cơ chế |
|---|---|---|---|---|
| `INVENTORY_STOCK_CHANGED` | Sau mỗi thay đổi tổng khả dụng per-SKU | `sku_id` | Search, Analytics | Outbox + CDC |
| `INVENTORY_RETURN_RECEIVED_ALL` | Khi mọi item của một refund scope đã về kho | `order_id` | Order | Outbox + CDC |
| `INVENTORY_SHIP_TO_CUSTOMER` | Khi admin xác nhận xuất kho | `order_id` | Order | Outbox + CDC |

`INVENTORY_STOCK_CHANGED` **không có revision**, nên consumer không có cách nào bỏ
event cũ đến muộn ngoài việc dựa vào thứ tự Kafka. Đây chính là lý do nó không đủ dùng
cho Search về lâu dài và là động lực của
[ADR-007](./decisions/ADR-007-stock-snapshot-v1-for-search.md).
Product **không** consume event này; Product không phải stock replica.

Hai signal còn lại là sự kiện nghiệp vụ thuần túy, không mang số liệu tồn kho.
`INVENTORY_RETURN_RECEIVED_ALL` chỉ mang `order_id` và `refund_request_id`;
`INVENTORY_SHIP_TO_CUSTOMER` chỉ mang `order_id`, `warehouse_id` và `shipped_at`.
Order tự truy vấn phần còn lại. `courier_note` không bao giờ vào Kafka.

### 4.3 Quan hệ giữa các event trong một hành động

Một lần restock hàng hoàn có thể sinh **hai** outbox row: một `INVENTORY_STOCK_CHANGED`
(tồn kho đã đổi) và một `INVENTORY_RETURN_RECEIVED_ALL` (nếu đây là item cuối). Hai
event độc lập về nghiệp vụ và không thay thế nhau.

Ngược lại, ship-to-customer sinh **đúng một** event và không kèm bất kỳ event tồn kho
nào, vì hàng đã bị trừ từ lúc deduct.

## 5. Events tiêu thụ

Inventory **không consume event nghiệp vụ nào từ service khác.** Nó không nghe
`t.order.events`, không nghe `t.product.events`, không nghe event vận chuyển.

Hệ quả cần biết:

- SKU bị xóa ở Product không tự động biến mất khỏi Inventory. Dòng
  `physical_inventory` vẫn tồn tại và vẫn có thể được reserve.
- Đơn hàng bị hủy ở Order không tự trả lại tồn kho; Order **phải** gọi Rollback
  Activity.
- Không có bảng inbox và không cần deduplication theo `event_id` cho event ngoại lai.

`INVENTORY_STOCK_MUTATION` là ngoại lệ duy nhất được Ledger Worker consume, nhưng nó
do chính Inventory phát. Worker phải lọc theo `event_type` trước khi xử lý, nếu không
sẽ tiêu thụ chính event nó gián tiếp sinh ra qua outbox và tạo vòng lặp.

## 6. Compatibility

**Quy tắc chung:** chỉ thêm field optional; không xóa field, không thu hẹp enum, không
đổi ngữ nghĩa của field đang tồn tại khi còn consumer được hỗ trợ.

**Chiến lược version:** contract mới đi kèm topic mới (`t.inventory.stock.events.v1`)
thay vì thêm version vào topic cũ. Hai contract chạy song song; consumer cũ không phải
hiểu event v1 và ngược lại.

**Kế hoạch ngừng contract legacy:**

0. Implement phía provider trước: migration tạo `sku_stock_state`, nới ràng buộc
   `event_type` của bảng outbox, backfill state, và endpoint export. Bước này chưa bắt
   đầu — xem [Data §5](./data.md#5-khoảng-cách-giữa-thiết-kế-đích-và-schema-hiện-tại).
1. Search chuyển sang snapshot v1 và hoàn tất một lần full reindex từ export API;
   Product Detail BFF bootstrap availability projection rồi replay cùng offsets.
2. Xác nhận không còn consumer nào của `INVENTORY_STOCK_CHANGED` — hiện chưa có danh
   sách consumer được xác minh, đây là điều kiện chặn.
3. Dừng ghi `INVENTORY_STOCK_CHANGED` vào outbox.
4. Giữ topic thêm một chu kỳ retention rồi mới gỡ.

Mốc thời gian cho từng bước chưa được ấn định.

**Điều kiện để một contract đạt `verified`:** có lint CI, có contract test hai chiều
provider/consumer, có breaking-change check trong CI, và có ít nhất một lần chạy
đối chiếu với implementation. Hiện chưa contract nào của Inventory đạt điều kiện này.
