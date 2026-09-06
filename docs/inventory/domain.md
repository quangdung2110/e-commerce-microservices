---
doc_id: inventory-domain
service: inventory
scope: current
status: proposed
owners:
  - Inventory Service team
evidence:
  - java/services/inventory-service
verified_against_commit: null
---

# Inventory Service — Domain Model

> File này trả lời: “Service đang bảo vệ những quy tắc nghiệp vụ nào?”

## 1. Ubiquitous language

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Warehouse | Kho vật lý do admin quản lý, có mã tỉnh/quận/phường và cờ hoạt động | Địa chỉ giao hàng của khách |
| Stock item | Tồn kho của một SKU tại một kho — đơn vị nhất quán nhỏ nhất của service | SKU trong catalog |
| Actual (`total`) | Số hàng đang thực sự nằm trong kho | Available |
| Reserved | Phần đã hứa cho đơn hàng nhưng chưa xuất | Deducted |
| Available | `actual - reserved`, phần còn bán được | Actual |
| Reservation | Lời hứa giữ chỗ cho một `order_item` tại một kho, có hạn | Đơn hàng |
| Deduct | Trừ hàng thật sau khi thanh toán thành công | Ship |
| Route plan | Kế hoạch chọn kho cho một giỏ hàng, có `route_plan_id` và hạn dùng | Reservation |
| Ledger entry | Một dòng bất biến ghi lại biến động `actual` | Reservation history |
| Stock revision | Số nguyên tăng dần theo SKU, dùng làm ordering guard cho consumer | Timestamp phát event |
| Refund expectation | Đăng ký trước của Order về tập item sẽ được hoàn về kho | Yêu cầu hoàn tiền |
| Suspension | Trạng thái đóng băng một `(SKU, kho)` sau khi phát hiện sai số liệu | SKU bị ẩn trong catalog |
| Invariant drift | Tình trạng `available != actual - reserved` trong Redis | Hết hàng |

## 2. Domain objects

**Aggregate root: Stock item, định danh bằng `(sku_id, warehouse_id)`.** Đây là ranh
giới nhất quán thật sự của service: mọi mutation atomic đều nằm gọn trong một stock
item, và không có thao tác nào cần khóa hai stock item cùng lúc. Aggregate này tồn tại
đồng thời ở hai nơi — Redis (bản đang chạy) và `physical_inventory` (bản durable).

**Entity:**

- `Warehouse` — lifecycle độc lập, do admin tạo, chỉ vô hiệu hóa bằng `is_active`.
- `Reservation` — thuộc về một `order_item` và một kho; có state machine riêng và tuổi
  thọ dài hơn transaction sinh ra nó.

**Value object:**

- `RoutePlan` — bất biến sau khi tạo, chỉ có hiệu lực tới `expires_at`, không được
  sửa; hết hạn thì phải sinh cái mới.
- `LedgerEntry` — bất biến tuyệt đối, không bao giờ update hay xóa.

**Projection (không phải aggregate):** thiết kế đích có một bản tổng hợp per-SKU
(`sku_stock_state`) dùng để công bố ra ngoài; nó **chưa tồn tại trong schema hiện tại**
(xem [Data §5](./data.md#5-khoảng-cách-giữa-thiết-kế-đích-và-schema-hiện-tại)). Hiện tổng
khả dụng per-SKU được tính tại thời điểm phát event. Dù ở dạng nào, bản tổng hợp này
không được dùng làm cơ sở cho quyết định reserve hay routing.

**Đối tượng thuộc service khác, chỉ tham chiếu bằng identifier:** `sku_id` và
`product_id` (Product), `order_id`/`order_item_id` (Order), `refund_request_id`
(Order). Inventory không validate sự tồn tại của chúng ở service gốc.

## 3. Commands và queries

| Loại | Capability | Actor | Xử lý bởi |
|---|---|---|---|
| Query | Lập route plan | Checkout thay mặt user | Warehouse Selection Engine |
| Command | Reserve | Order Temporal worker | Stock item |
| Command | Deduct | Order Temporal worker | Stock item |
| Command | Release | Order Temporal worker | Stock item |
| Command | Restock (`ADMIN_RESTOCK`) | Admin | Stock item |
| Command | Restock hàng hoàn (`ORDER_RETURNED_RESTOCK`) | Admin | Stock item + Reservation |
| Command | Đăng ký refund expectation | Order Service (m2m) | Reservation |
| Command | Xác nhận xuất kho | Admin | Không aggregate nào — chỉ phát signal |
| Command | Mở khóa SKU | Admin | Suspension flag |
| Query | Export stock snapshot *(target, chưa có)* | Search (service identity) | Bản tổng hợp per-SKU |

## 4. Business rules

| ID | Rule | Điều kiện | Vi phạm trả về |
|---|---|---|---|
| `INV-BR-001` | Reserve chỉ thành công khi `available >= quantity` tại đúng kho được chỉ định | Mọi lần reserve | `OUT_OF_STOCK` |
| `INV-BR-002` | Deduct chỉ thành công khi `reserved >= quantity` | Mọi lần deduct | `INSUFFICIENT_RESERVED` |
| `INV-BR-003` | Release không được trả về nhiều hơn phần đang giữ chỗ | Mọi lần release | `RELEASE_UNDERFLOW` |
| `INV-BR-004` | Request nhiều item là all-or-nothing: một item hỏng thì các item đã áp dụng phải được release lại | Reserve nhiều item | Lỗi tổng của request |
| `INV-BR-005` | Mọi mutation phải chỉ định `warehouse_id`; không có mutation ở mức SKU | Mọi mutation | `INVALID_REQUEST` |
| `INV-BR-006` | Restock là entry point khởi tạo duy nhất; SKU chưa restock lần nào không tồn tại với Inventory | Routing, reserve | `SKU_NOT_FOUND` |
| `INV-BR-007` | Chọn kho: kho phải đủ hàng cho item; ưu tiên kho cùng `city_code` với khách; tie-break `warehouse_id` tăng dần | Lập route plan | `INSUFFICIENT_STOCK` |
| `INV-BR-008` | Route plan hết hạn hoặc không có trong cache là không hợp lệ; không có gia hạn | Checkout submit | `CONTRACT_INVALID_ROUTE_PLAN` |
| `INV-BR-009` | Hàng hoàn chỉ được nhận cho reservation `DEDUCTED` đã có `refund_expected = true` | Restock hàng hoàn | `REFUND_NOT_EXPECTED` |
| `INV-BR-010` | Một `(order_id, refund_request_id)` chỉ gắn với một tập `order_item_ids` duy nhất | Đăng ký refund expectation | `REFUND_SCOPE_MISMATCH` |
| `INV-BR-011` | Ledger chỉ ghi biến động `actual_quantity`; reserve, release và ship ghi `quantity_change = 0` | Mọi ledger write | Vi phạm audit |
| `INV-BR-012` | `RESERVATION_EXPIRED` được giữ tách khỏi `ORDER_CANCELLED` trong ledger, dù cả hai cùng đưa reservation về `CANCELLED` | Release | Mất khả năng phân biệt khi audit |
| `INV-BR-013` | Xuất kho không mutate tồn kho, không ghi ledger, không đổi trạng thái reservation | Ship-to-customer | Vi phạm `INV-INV-007` |
| `INV-BR-014` | Khi một `(SKU, kho)` đang bị đóng băng, mọi mutation bị từ chối trước khi kiểm tra bất kỳ điều kiện nghiệp vụ nào khác | Mọi mutation | `SKU_SUSPENDED` |
| `INV-BR-015` | Mở khóa SKU chỉ do người thực hiện; hệ thống không tự mở khi hết TTL mà không có đối soát | Recovery | Rủi ro vận hành |

## 5. Invariants

| ID | Điều kiện | Nơi enforcement |
|---|---|---|
| `INV-INV-001` | `available + reserved == total`, cả ba `>= 0` | Kiểm tra trước và sau mutate trong Lua script Deduct; vi phạm trả `-1` |
| `INV-INV-002` | `actual_quantity >= reserved_quantity >= 0` | `ck_physical_inventory_actual_gte_reserved` trong [migration V1](../../java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql) |
| `INV-INV-003` | Một `(op, sku, kho, order)` áp dụng đúng một lần | `SETNX` idempotency key trong Lua; partial unique index `uq_reservations_active` |
| `INV-INV-004` | Một `event_id` tạo tối đa một ledger row | `inventory_ledger.event_id UNIQUE` |
| `INV-INV-005` | `INVENTORY_RETURN_RECEIVED_ALL` phát đúng một lần mỗi `(order_id, refund_request_id)` | `uq_inventory_outbox_return_received` |
| `INV-INV-006` | Ledger chỉ phản ánh biến động `actual_quantity` | Enum `reference_type` + quy ước `quantity_change` |
| `INV-INV-007` | Stock item bị drift không nhận thêm mutation | Kiểm tra cờ suspension ở dòng đầu mọi Lua script |
| `INV-INV-008` *(target)* | `stock_revision` tăng đúng một lần mỗi state transition per-SKU | **Chưa có nơi enforcement** — cần bảng `sku_stock_state` |

Các invariant trên đều có nơi kiểm chứng cụ thể. Mong muốn "Redis và PostgreSQL luôn
khớp nhau" **không** phải invariant: kiến trúc chấp nhận nó sai trong một cửa sổ thời
gian, và điều đó được ghi ở [ADR-001](./decisions/ADR-001-redis-hot-path-commit-source.md).

## 6. State machines

### Reservation

| State | Transition tới | Trigger | Guard | Side effect |
|---|---|---|---|---|
| *(none)* | `RESERVED` | Reserve thành công | `available >= quantity`; chưa có row `RESERVED` cho `(order, sku, kho)` | `reserved` tăng; ledger `ORDER_RESERVED`; `expires_at` được đặt |
| `RESERVED` | `DEDUCTED` | Deduct sau khi thanh toán thành công | `reserved >= quantity` | `actual` và `reserved` cùng giảm; ledger `ORDER_DEDUCTED` |
| `RESERVED` | `CANCELLED` | Release do hủy đơn | `reserved >= quantity` | `reserved` giảm, `available` tăng; ledger `ORDER_CANCELLED` |
| `RESERVED` | `CANCELLED` | Release do Temporal timer hết hạn | như trên | ledger `RESERVATION_EXPIRED` |
| `DEDUCTED` | *(terminal)* | — | — | Chỉ còn có thể nhận `refund_returned_at` |
| `CANCELLED` | *(terminal)* | — | — | Được archive sau 90 ngày |

`DEDUCTED` không quay lại `RESERVED`. Hàng hoàn về kho **không** đổi state của
reservation; nó chỉ điền `refund_returned_at` và cộng tồn kho mới.

### Refund expectation của một reservation `DEDUCTED`

`refund_expected = false` → `true` (Order đăng ký scope) → `refund_returned_at` được
điền (admin nhận hàng). Khi mọi reservation cùng `(order_id, refund_request_id)` đều
đã có `refund_returned_at`, signal `INVENTORY_RETURN_RECEIVED_ALL` được phát đúng một
lần. Không có transition ngược: đã đăng ký scope thì không hủy được qua API.

### Suspension của một `(SKU, kho)`

`NORMAL` → `SUSPENDED` khi Lua script phát hiện drift (mã `-1`), kèm TTL 24 giờ và
cảnh báo vận hành. `SUSPENDED` → `NORMAL` chỉ qua API unlock của admin. TTL hết hạn
cũng làm cờ biến mất, nhưng đó là cơ chế dọn rác chứ không phải quy trình phục hồi
hợp lệ — xem [ADR-005](./decisions/ADR-005-fail-safe-suspension.md).

## 7. Authorization rules

| Actor | Được phép | Cơ chế |
|---|---|---|
| User đã đăng nhập (qua Checkout) | Lập route plan | Identity do Gateway inject |
| Admin | Restock, xác nhận xuất kho, mở khóa SKU | Role `ADMIN` từ Gateway |
| Order Service | Đăng ký refund expectation | Service identity m2m + header `X-Internal-Service: order-service` |
| Order Temporal worker | Reserve, Deduct, Release | Temporal task queue nội bộ |
| Search Service | Export stock snapshot | Service identity, không qua Gateway |

Không có khái niệm tenant hay shop: hệ thống chỉ có một shop, nên không có kiểm tra
ownership theo shop. Inventory không tự kiểm tra người gọi có sở hữu `order_id` hay
không — quyền đó thuộc Order và Gateway.

## 8. Domain errors

| Error code | Điều kiện phát sinh | Retryable | Interface mapping |
|---|---|---|---|
| `OUT_OF_STOCK` | `available < quantity` khi reserve | No | Temporal Activity failure → saga compensation |
| `INSUFFICIENT_RESERVED` | `reserved < quantity` khi deduct | No | Temporal Activity failure, không compensation tự động |
| `RELEASE_UNDERFLOW` | `reserved < quantity` khi release | No | Temporal Activity failure, cần điều tra |
| `SKU_SUSPENDED` | Cờ suspension tồn tại | No, cho tới khi admin mở khóa | Temporal failure / HTTP 409 |
| `INVARIANT_DRIFT` | `available != total - reserved` | **Không bao giờ retry** | Đóng băng SKU + alert; HTTP 503 cho caller |
| `INSUFFICIENT_STOCK` | Không kho nào đủ hàng cho một item | No | HTTP 409 |
| `SKU_NOT_FOUND` | SKU chưa từng được restock | No | HTTP 404 |
| `WAREHOUSE_NOT_FOUND` | Kho không tồn tại hoặc `is_active = false` | No | HTTP 404 |
| `RESERVATION_NOT_FOUND` | Không có reservation khớp điều kiện | No | HTTP 404 |
| `REFUND_NOT_EXPECTED` | Reservation tồn tại nhưng chưa được đăng ký refund scope | No | HTTP 409 |
| `REFUND_SCOPE_MISMATCH` | Cùng khóa refund nhưng khác tập item | No | HTTP 409 |
| `CONTRACT_INVALID_ROUTE_PLAN` | Plan hết hạn hoặc không có trong cache | No — client phải preview lại | HTTP 409 tại Checkout |
| `SKU_NOT_SUSPENDED` | Unlock một SKU không bị khóa | No | HTTP 404 |
| `INVENTORY_UNAVAILABLE` | Redis hoặc PostgreSQL không khả dụng | Yes | HTTP 503, `retryable = true` |

`INVENTORY_UNAVAILABLE` là lỗi hạ tầng; mọi mã còn lại là domain error và không được
retry mù. `INVARIANT_DRIFT` đặc biệt: nó là dấu hiệu dữ liệu đã sai, nên retry hoặc
rollback tự động chỉ làm sai thêm.

## 9. Business examples

**Giữ chỗ thành công.** Kho 5 có `total = 800`, `reserved = 0`, `available = 800`.
Đơn 10001 reserve 2 đơn vị SKU-001 tại kho 5 → `reserved = 2`, `available = 798`,
`actual` không đổi, ledger ghi `ORDER_RESERVED` với `quantity_change = 0`.

**Replay đúng cách.** Temporal retry lại chính Activity đó với cùng `order_id`. Script
thấy idempotency key đã tồn tại, trả mã idempotent, **không** mutate lần hai và
**không** phát event mới. Caller nhận kết quả thành công.

**Vi phạm rule đa item.** Giỏ có SKU-001 (đủ hàng) và SKU-002 (thiếu 1 đơn vị).
Reserve SKU-001 thành công, SKU-002 trả `OUT_OF_STOCK`. Theo `INV-BR-004`, service
phải release lại SKU-001 rồi trả lỗi tổng — không được để đơn giữ chỗ một nửa.

**Sai lệch số liệu.** Redis có `total = 100`, `reserved = 5`, nhưng `available = 97`
thay vì 95. Script Deduct phát hiện ngay ở bước kiểm tra đầu, trả `-1`, không mutate
gì. `(SKU-001, kho 5)` bị đóng băng; mọi reserve/deduct/release tiếp theo trả
`SKU_SUSPENDED` cho tới khi có người đối soát và gọi unlock.

**Hoàn hàng đủ.** Order đăng ký refund scope gồm item 2001 và 2002. Admin nhận 2001 →
`refund_returned_at` được điền, còn 1 item pending, chưa phát signal. Admin nhận 2002 →
pending về 0 → `INVENTORY_RETURN_RECEIVED_ALL` được ghi outbox. Nếu admin lỡ tay gửi
lại request cho 2002, unique index chặn event thứ hai.

**Xuất kho nhiều kho.** Đơn 10001 có item ở kho 5 và kho 7. Admin gọi ship-to-customer
cho kho 5 → chỉ item thuộc kho 5 chuyển sang đã giao. Nếu bỏ lọc `warehouse_id`, item
ở kho 7 sẽ bị đánh dấu đã giao trong khi hàng còn nằm trong kho — đây là lỗi nghiệp vụ
nghiêm trọng mà `INV-BR-013` và [ADR-009](./decisions/ADR-009-ship-signal-without-ledger.md)
tồn tại để ngăn.
