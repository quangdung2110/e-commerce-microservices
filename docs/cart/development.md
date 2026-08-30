---
doc_id: cart-development
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence: []
verified_against_commit: null
---

# Cart Service — Development Guide

> Refactor này không đọc build configuration của service, nên không command,
> phiên bản hay đường dẫn nào được bịa ra. Phần đánh dấu `TBD` cần được điền từ
> `Makefile`, `docker-compose` và cấu hình CI thật tại
> [`go/services/cart-service`](../../go/services/cart-service).

## 1. Prerequisites

Cần ghi: phiên bản Go, container runtime, công cụ chạy migration, công cụ sinh mã
từ contract, và các linter mà CI sử dụng. Hiện `TBD`.

## 2. Chạy local

Cần các bước copy-and-run cho: cài dependency, build, khởi động service, kiểm tra
health endpoint, và dừng cùng dọn tài nguyên an toàn. Hiện `TBD`.

## 3. Local dependencies

Cart cần bốn dependency để chạy đầy đủ:

| Dependency | Vai trò | Có thể mock không |
|---|---|---|
| Redis | Primary store và outbox stream | Không — mọi đường ghi đều đi qua Lua script |
| PostgreSQL | Cold store | Có, nếu chỉ test đường đọc và ghi trên Redis |
| Kafka | Relay và consumer | Có, nếu chỉ test đường đồng bộ |
| Product Service | Pull Model qua gRPC | Có, và nên mock: test cần dựng được cả trường hợp timeout và trường hợp sản phẩm bị gỡ bán |

Lưu ý khi chạy Redis local: nếu dùng Redis đơn node thì hash tag không được kiểm
tra, nên lỗi `CROSSSLOT` sẽ chỉ lộ ra trên môi trường cluster. Muốn bắt sớm lớp lỗi
này thì phải test trên cấu hình cluster.

Port, script khởi tạo và file compose chính thức: `TBD`.

## 4. Local configuration

Cần ghi: file hoặc profile cấu hình local, danh sách biến môi trường bắt buộc, cách
cấp secret cho môi trường phát triển, và giá trị tuyệt đối không được commit. Danh
sách tham số nghiệp vụ và ý nghĩa của chúng đã có tại
[Operations §3](./operations.md#3-configuration).

Không commit chuỗi kết nối thật, thông tin xác thực Redis hay PostgreSQL.

## 5. Build, lint và format

Command chuẩn phải trùng với command CI sử dụng. Hiện `TBD`.

## 6. Tests

Ngoài unit test thông thường, service này có ba nhóm test không thể bỏ qua vì đặc
thù kiến trúc:

**Test Lua script.** Ba script trong [`scripts/`](./scripts) là nơi chứa gần như
toàn bộ bất biến của service. Cần test trực tiếp trên một Redis thật:

- cộng dồn dưới nhiều lời gọi song song trên cùng một SKU;
- chạm trần `max_qty` cho kết quả clamp chứ không lỗi;
- chạm trần `max_skus` từ chối SKU mới nhưng vẫn cho tăng SKU đã có;
- giảm số lượng của item chưa tồn tại bị từ chối;
- `added_at` và `unit_price_at_add` không đổi sau khi cập nhật;
- mỗi lần ghi thành công sinh **đúng một** entry stream;
- script rehydrate **không** sinh entry stream nào;
- script rehydrate không đè lên bản ghi mới hơn đang có trên Redis;
- batch delete bỏ qua SKU không tồn tại và phát đúng loại event kết thúc.

**Test thứ tự và trùng lặp ở consumer.** Cần dựng được các kịch bản: event trùng
lặp, event cũ đến sau event mới, delete đến trước add, và giỏ được tái kích hoạt
sau khi đã `CHECKED_OUT`. Đây là nhóm test bảo vệ `CART-INV-005`.

**Test đường suy giảm.** Product Service timeout và circuit breaker mở phải cho ra
response thành công kèm cờ độ tươi giá, chứ không phải lỗi.

Contract test hai chiều với
[OpenAPI](../../contracts/http/cart/v1/openapi.yaml) và
[AsyncAPI](../../contracts/events/cart/v1/asyncapi.yaml) là điều kiện để contract
được nâng lên `implemented`.

Command chạy từng nhóm test: `TBD`.

## 7. Contract code generation

Cần ghi: công cụ và phiên bản sinh mã từ OpenAPI, AsyncAPI và Protobuf; đường dẫn
output; và cách CI phát hiện mã sinh ra bị lệch so với contract. Hiện `TBD`.

## 8. Database migration

Schema chuẩn nằm tại
[`schema/0001_initial_cart_schema.sql`](./schema/0001_initial_cart_schema.sql).

Ràng buộc bắt buộc khi thêm migration:

- Cột mới phải nullable hoặc có giá trị mặc định, vì phiên bản cũ vẫn ghi trong lúc
  rollout.
- Không thêm foreign key giữa `cart_items` và `carts`: consumer có thể nhận event
  item trước khi dòng master tồn tại. Lý do đầy đủ tại
  [Data §4](./data.md#4-constraints).
- Không đổi định dạng `item_id` hay `cart_id`; chúng là contract dữ liệu xuyên suốt
  Redis, cold store và mọi event đã phát.

Công cụ migration, cách chạy local, cách rollback và cách seed dữ liệu test: `TBD`.

## 9. Test data và fixtures

Fixture không được chứa dữ liệu production hoặc dữ liệu cá nhân thật. `user_id`
trong fixture nên là UUID sinh ngẫu nhiên. Vị trí fixture: `test/fixtures` trong
thư mục service.

## 10. Debugging

Khi lần theo một thao tác từ đầu đến cuối, `stream_id` là sợi chỉ xuyên suốt: nó
xuất hiện ở entry stream, ở header message Kafka, và ở cột guard trong cold store.

Thao tác chẩn đoán chỉ đọc hữu ích:

- Xem nội dung một giỏ bằng `HGETALL` trên cart key.
- Xem tình trạng một stream bucket bằng `XINFO STREAM` và `XINFO GROUPS`.
- Xem message tồn đọng bằng `XPENDING`.
- Đối chiếu một item giữa Redis và cold store qua `item_id`.

Thao tác **không** an toàn trên môi trường chung: `XTRIM` hoặc `DEL` thủ công trên
stream bucket, và ghi trực tiếp vào cart hash bằng lệnh Redis rời — cả hai đều phá
bất biến của service.

Cấu hình debugger và mức log: `TBD`.

## 11. Các lỗi local phổ biến

| Triệu chứng | Nguyên nhân thường gặp | Cách kiểm tra |
|---|---|---|
| `CROSSSLOT` khi gọi Lua | Cart key và stream key không cùng hash tag bucket `{bNN}` | Kiểm tra `cart:v1:{bNN}:u:<user_token>:items` và `cart:v1:{bNN}:outbox` thực tế mà backend truyền vào `KEYS[]` |
| Mốc thời gian sai ở client | Timestamp bị cjson đổi sang ký hiệu khoa học | Kiểm tra timestamp có được lưu dạng chuỗi không |
| Hụt cache lặp lại với cart rỗng | Cart không dùng negative cache; cold store bị đọc lại là hành vi thiết kế | Kiểm tra latency/pool PostgreSQL và chỉ cân nhắc cache sau khi có số liệu |
| Giá lịch sử trả về rỗng sau rehydrate | Thiếu bước ghép `unit_price_at_add` từ cột riêng vào JSON | Kiểm tra dữ liệu truyền vào script rehydrate |
| Event xuất hiện sau khi chỉ đọc giỏ | Script rehydrate bị thêm `XADD` | Kiểm tra script đang nạp trên Redis |
| Cold store không đổi dù đã thao tác | Guard `stream_id` chặn, hoặc consumer đang lag | So sánh `stream_id` của event với giá trị trong cột guard |
| Stream phình to khi test | Trimmer chưa chạy, hoặc bị bỏ qua vì thiếu consumer group | `XLEN` và `XINFO GROUPS` trên stream bucket |

## 12. Definition of Done

Một thay đổi hoàn thành khi, tương ứng với phạm vi thay đổi:

- Build, lint và test đều thành công.
- Contract machine-readable được cập nhật nếu interface thay đổi.
- File schema và tài liệu dữ liệu được cập nhật nếu schema thay đổi.
- Script Lua trong [`scripts/`](./scripts) được cập nhật cùng lúc với code nếu hành
  vi nguyên tử thay đổi — hai nơi này lệch nhau là một loại nợ kỹ thuật nguy hiểm
  vì tài liệu sẽ mô tả sai bất biến.
- Tài liệu workflow hoặc domain được cập nhật nếu hành vi thay đổi.
- ADR mới được thêm nếu có quyết định kiến trúc.
- Tương thích ngược được kiểm tra cho cả contract lẫn định dạng snapshot trong Redis.
- Trạng thái và trường `evidence` của tài liệu được cập nhật.
