---
doc_id: cart-domain
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/internal/domain
  - go/services/cart-service/test
verified_against_commit: null
---

# Cart Service — Domain Model

> File này trả lời: “Cart Service đang bảo vệ những quy tắc nghiệp vụ nào?”

## 1. Ubiquitous language

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Cart | Tập hợp ý định mua hàng của một user, định danh bằng `cart_id` | Order hoặc Checkout session |
| Cart item | Một dòng trong giỏ, định danh bằng `sku_id`, có quantity và snapshot | Order item |
| `cart_id` | Identity nghiệp vụ ổn định `c1:<user_token>` | Redis key vật lý hoặc `user_id` |
| Product snapshot | Dữ liệu hiển thị được sao chép lúc thêm vào giỏ | Dữ liệu catalog thời gian thực |
| `unit_price_at_add` | Giá tại thời điểm user thêm SKU vào giỏ, bất biến | `current_price` |
| `current_price` | Giá hiện tại lấy từ Product Service khi đọc giỏ | Trường lưu bền trong Redis hoặc DB |
| `is_available` | Cờ khả dụng dùng để hiển thị và chặn checkout | Số lượng tồn kho khả dụng |
| `is_price_degraded` | Cờ báo response dựng từ snapshot cũ do Product lỗi | Lỗi nghiệp vụ |
| `stream_id` | Redis Stream Entry ID, khóa thứ tự của một cart trong stream bucket của nó | `event_id` |
| Outbox event | Event được `XADD` cùng lúc với thay đổi cart state | Kafka message sau khi relay |
| Rehydrate | Nạp lại Redis từ cold store khi mất key | Business write |
| Cleared | Giỏ rỗng do user tự xóa hết | Checked out |
| Checked out | Giỏ rỗng do toàn bộ item đã được mua | Cleared |
| Abandoned | Giỏ `ACTIVE` không tương tác quá ngưỡng thời gian | Deleted |

## 2. Domain objects

**Cart** là aggregate root. Identity là `cart_id = c1:<user_token>`, ổn định theo
user và độc lập với Redis key vật lý (`CART-INV-001`). Vòng đời của Cart là vô hạn theo user: một `cart_id`
có thể đi qua nhiều vòng `ACTIVE → CHECKED_OUT → ACTIVE`.

**CartItem** là entity bên trong aggregate, định danh cục bộ bằng `sku_id`. Trong
Redis nó là một hash field; trong cold store nó là một dòng `cart_items` với khóa
`item_id = cart_id + ':' + sku_id`. Không tồn tại hai dòng cùng `sku_id` trong một
giỏ (`CART-INV-002`).

**ProductSnapshot** là value object gắn với CartItem: `product_id`, `product_name`,
`variant_info`, `image_url`, `is_available`, `added_at`, `updated_at`. Nó được sao
chép từ Product Service tại thời điểm ghi và không phải nguồn chân lý của catalog.

**Product và SKU** thuộc Product Service; Cart chỉ tham chiếu bằng `sku_id`,
`product_id` và snapshot. **User** thuộc Auth/User Service; Cart chỉ giữ `user_id`.

## 3. Commands và queries

| Type | Capability | Actor | Aggregate/capability |
|---|---|---|---|
| Command | Thêm SKU hoặc thay đổi số lượng | Authenticated user (chủ giỏ) | Cart |
| Command | Xóa theo lô các SKU | Authenticated user (chủ giỏ) | Cart |
| Command | Dọn các SKU đã mua sau thanh toán | Order Event consumer | Cart |
| Command | Đánh dấu giỏ bỏ quên | System cronjob | Cart |
| Command | Dọn dữ liệu quá hạn khỏi cold store | System cronjob | Cold store |
| Command | Phục hồi giỏ từ cold store | Application (nội bộ, không phải API) | Cart |
| Query | Đọc giỏ hàng của chính mình | Authenticated user (chủ giỏ) | Cart |

Không có command hay query nào cho phép một actor thao tác trên giỏ của user khác;
cũng không có API quản trị trên giỏ hàng.

## 4. Business rules

| ID | Điều kiện áp dụng | Quy tắc | Kết quả khi vi phạm |
|---|---|---|---|
| `CART-BR-001` | Thêm hoặc tăng số lượng một SKU | Quantity sau thao tác bị chặn trần ở `max_qty` (mặc định 100); vượt trần thì clamp về `max_qty`, không báo lỗi | Không có lỗi; giá trị bị clamp |
| `CART-BR-002` | Thêm một SKU chưa có trong giỏ | Số loại SKU trong giỏ không vượt `max_skus` (mặc định 50) | `CART_FULL` |
| `CART-BR-003` | Mọi thao tác add/update | `step` phải là số nguyên khác 0 | `STEP_INVALID` |
| `CART-BR-004` | SKU chưa tồn tại trong giỏ | `step` phải dương; không được giảm số lượng của item chưa tồn tại | `ITEM_NOT_FOUND` |
| `CART-BR-005` | Giảm số lượng | Quantity sau thao tác phải `> 0`; muốn bỏ hẳn item thì dùng API xóa | `QUANTITY_INVALID` |
| `CART-BR-006` | Thêm hoặc cập nhật item | SKU và product phải ở trạng thái `ACTIVE` theo Product Service | `PRODUCT_NOT_AVAILABLE` |
| `CART-BR-007` | Item đã tồn tại được cập nhật | `added_at` và `unit_price_at_add` giữ nguyên giá trị ban đầu | Vi phạm `CART-INV-006` |
| `CART-BR-008` | Đọc giỏ trúng cache | Không được gia hạn TTL của cart key | Vi phạm chính sách Read-Only TTL |
| `CART-BR-009` | Đọc giỏ và Product trả `is_available = false` | Bắt buộc ghi lại đúng field `is_available = false` vào Redis, không đụng TTL và các field khác | Checkout có thể nhận item không còn bán |
| `CART-BR-010` | Rehydrate từ cold store | Không được phát event; được phép đặt lại TTL đúng một lần | Sinh event replay rác, phá idempotency downstream |
| `CART-BR-011` | Dọn giỏ sau `ORDER_PAID` | Chỉ xóa các `sku_id` có trong danh sách; SKU còn lại giữ nguyên và giỏ vẫn `ACTIVE` | Mất hàng người dùng chưa mua |
| `CART-BR-012` | Giỏ rỗng sau thao tác xóa | Rỗng do user xóa phát `CART_CLEARED`; rỗng do thanh toán phát `CART_CHECKED_OUT` | Phân loại vòng đời và retention sai |
| `CART-BR-013` | Mọi command | `user_id` chỉ được lấy từ header do Gateway inject | Truy cập giỏ của user khác |
| `CART-BR-014` | Đọc giỏ khi Product Service lỗi | Trả snapshot cũ kèm `is_price_degraded = true`, không trả lỗi | Giỏ hàng không đọc được khi Product sập |

## 5. Invariants

| ID | Điều kiện phải đúng | Nơi enforcement |
|---|---|---|
| `CART-INV-001` | `cart_id = c1:<user_token>` không đổi theo vòng đời user và không phụ thuộc key format/bucket Redis | Key builder ở application layer; `user_id` và `cart_id` được truyền tường minh vào mọi event payload |
| `CART-INV-002` | Một `sku_id` xuất hiện tối đa một lần trong một giỏ | Cấu trúc Redis hash; khóa chính `item_id` ở cold store |
| `CART-INV-003` | Quantity của mọi item nằm trong `1..max_qty` | Script [`add_or_update_item.lua`](./scripts/add_or_update_item.lua) |
| `CART-INV-004` | Mỗi state transition quan sát được sinh đúng một outbox event trong cùng script; rehydrate không sinh event | Script add/update và batch delete có `XADD`; script rehydrate không có |
| `CART-INV-005` | Cold store chỉ tiến lên theo `stream_id` | Mệnh đề guard trong upsert/delete của Postgres Sync consumer |
| `CART-INV-006` | `unit_price_at_add` và `added_at` immutable | Nhánh merge trong script add/update; cột riêng `unit_price_at_add` ở cold store |

Kiểm chứng: mỗi invariant cần một test tương ứng trong bộ test của service. Việc
các test này đã tồn tại hay chưa là hạng mục cần xác minh khi nâng trạng thái tài
liệu lên `verified`.

## 6. State machines

### Cart lifecycle (cột `carts.status`)

| State | Transition được phép | Trigger | Guard / side effect |
|---|---|---|---|
| *(chưa tồn tại)* | `ACTIVE` | `CART_ITEM_ADDED` đầu tiên | Upsert dòng `carts` cùng `last_event_stream_id` |
| `ACTIVE` | `DELETED` | `CART_CLEARED` | Chỉ khi `stream_id` mới hơn; Redis key đã bị `DEL`; không xóa `cart_items` ở bước này |
| `ACTIVE` | `CHECKED_OUT` | `CART_CHECKED_OUT` | Chỉ khi `stream_id` mới hơn và giỏ rỗng hoàn toàn sau thanh toán |
| `ACTIVE` | `ABANDONED` | Cronjob quét quá ngưỡng không tương tác | Cập nhật DB trước, phát `CART_ABANDONED` sau, đánh dấu `abandoned_event_published_at` |
| `DELETED` / `CHECKED_OUT` / `ABANDONED` | `ACTIVE` | `CART_ITEM_ADDED` hoặc `CART_ITEM_UPDATED` mới | Chỉ re-activate khi `stream_id` mới hơn `last_event_stream_id` |
| `DELETED` / `CHECKED_OUT` | *(bị xóa)* | Cronjob dọn dữ liệu quá hạn | Xóa `cart_items` trước, `carts` sau, trong cùng transaction |
| `ABANDONED` | *(bị xóa)* | Cronjob dọn dữ liệu quá hạn | Như trên, với ngưỡng retention dài hơn |

Transition không hợp lệ điển hình: event cũ đến muộn cố đưa giỏ về `ACTIVE` sau
khi đã `CHECKED_OUT`. Guard `last_event_stream_id` khiến câu lệnh không khớp dòng
nào và event bị bỏ qua một cách im lặng — đây là hành vi đúng, không phải lỗi.

### Cart item lifecycle

Item chỉ có hai trạng thái: tồn tại trong giỏ hoặc không. Không có soft delete.
Khi item bị xóa khỏi Redis, dòng tương ứng ở cold store bị hard delete. Nếu event
`CART_ITEM_DELETED` đến trước `CART_ITEM_ADDED` do lệch mạng, event add đến sau sẽ
insert lại bình thường và trạng thái vẫn hội tụ đúng theo `stream_id`.

## 7. Authorization rules

- Mọi command và query đều yêu cầu user đã đăng nhập. Không có giỏ hàng cho khách
  vãng lai.
- Chủ sở hữu tài nguyên được xác định bằng `user_id` mà Gateway inject vào header
  sau khi verify JWT. Cart tự dựng `cart_id` từ giá trị này và không nhận `cart_id`
  hay `user_id` do client gửi lên trong luồng API công khai (`CART-BR-013`).
- Order Event consumer hành động với tư cách hệ thống: nó dựng `cart_id` từ
  `user_id` trong payload `ORDER_PAID`, không nhận `cart_id` từ payload.
- Cronjob hành động với tư cách hệ thống trên toàn bộ dữ liệu, không đại diện cho
  một user cụ thể.
- Không có role quản trị nào được thao tác trực tiếp lên giỏ hàng của user.

## 8. Domain errors

| Error code | Điều kiện phát sinh | Retryable | Mapping ra interface |
|---|---|---|---|
| `CART_FULL` | Thêm SKU mới khi giỏ đã đạt `max_skus` | No | Lỗi validation nghiệp vụ ở HTTP; client phải xóa bớt item |
| `STEP_INVALID` | `step` không phải số nguyên khác 0 | No | Lỗi validation request |
| `ITEM_NOT_FOUND` | Giảm số lượng của SKU chưa có trong giỏ | No | Lỗi validation nghiệp vụ |
| `QUANTITY_INVALID` | Quantity sau thao tác `<= 0` | No | Lỗi validation nghiệp vụ |
| `PRODUCT_NOT_AVAILABLE` | SKU hoặc product không ở trạng thái `ACTIVE` | No | Lỗi validation nghiệp vụ |
| `INSUFFICIENT_DISPLAY_STOCK` | Snapshot tồn kho hiển thị không đủ cho số lượng yêu cầu | No | Lỗi validation nghiệp vụ; không phải cam kết tồn kho |
| `UNAUTHENTICATED` | Thiếu identity do Gateway inject | No | Lỗi xác thực |

Các tình huống sau **không** phải domain error và không được map như lỗi nghiệp vụ:

- Product Service timeout hoặc circuit breaker mở khi đọc giỏ: trả dữ liệu suy
  giảm kèm `is_price_degraded = true` (`CART-BR-014`).
- Redis, PostgreSQL hoặc Kafka không khả dụng: lỗi hạ tầng, xem
  [Operations](./operations.md#6-failure-modes).
- Event trùng lặp hoặc đến sai thứ tự: được guard xử lý im lặng, không sinh lỗi.

Mã lỗi HTTP cụ thể và cấu trúc error response được định nghĩa trong
[OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml).

## 9. Business examples

**Cộng dồn dưới tải song song.** Giỏ đang có `sku-1001` quantity 1. Ba request
`step = +1` đến gần như đồng thời. Vì cộng dồn nằm trong Lua script đơn luồng, kết
quả cuối cùng là 4, không phải 2. Ba event `CART_ITEM_UPDATED` được sinh ra với ba
`stream_id` tăng dần.

**Chặn trần số lượng.** Giỏ có `sku-1001` quantity 99 với `max_qty = 100`. Request
`step = +5` cho kết quả 100 chứ không phải 104, và vẫn trả thành công
(`CART-BR-001`).

**Giỏ đầy.** Giỏ đang có 50 SKU khác nhau với `max_skus = 50`. Thêm SKU thứ 51 bị
từ chối bằng `CART_FULL`, nhưng tăng số lượng một SKU đã có vẫn thành công vì
không làm tăng số loại (`CART-BR-002`).

**Thanh toán một phần.** Giỏ có `sku-A`, `sku-B`, `sku-C`. `ORDER_PAID` mang
`sku_ids = [A, B]`. Hai dòng A và B bị xóa và sinh `CART_ITEM_DELETED`; giỏ vẫn còn
C nên không phát `CART_CHECKED_OUT` và trạng thái vẫn `ACTIVE` (`CART-BR-011`).

**Event sai thứ tự.** Consumer nhận `CART_ITEM_UPDATED` với `stream_id`
`1708616400456-0` rồi mới nhận bản cũ `1708616400123-0`. Mệnh đề guard khiến bản cũ
không cập nhật dòng nào, dữ liệu cold store giữ nguyên giá trị mới (`CART-INV-005`).

**Product bị gỡ bán.** User mở giỏ, Product trả `is_available = false` cho
`sku-1001`. Response hiển thị item là không khả dụng, đồng thời Redis được ghi lại
đúng một field `is_available = false` mà không gia hạn TTL, để lần đọc sau và luồng
checkout đều thấy trạng thái này (`CART-BR-009`).

**Mất key Redis.** Giỏ hết TTL nhưng cold store còn dữ liệu. Lần đọc kế tiếp nạp
lại từ PostgreSQL, merge với dữ liệu Product, ghi vào Redis bằng script rehydrate,
đặt lại TTL đúng một lần và không phát bất kỳ event nào (`CART-BR-010`).
