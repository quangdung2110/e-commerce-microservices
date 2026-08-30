---
doc_id: cart-operations
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/configs
  - go/services/cart-service/internal/workers
verified_against_commit: null
---

# Cart Service — Operations

> File này trả lời: “Service hỏng thì phát hiện và xử lý thế nào?”
> Ngưỡng cụ thể được đánh dấu `TBD` khi chưa có bằng chứng từ production hoặc từ
> cấu hình hạ tầng thật; không đưa ra con số phỏng đoán.

## 1. SLO và SLI

| SLI | Cách đo | Mục tiêu |
|---|---|---|
| Availability của API đọc giỏ | Tỷ lệ response không phải `5xx` | `TBD` |
| Availability của API ghi | Tỷ lệ response không phải `5xx` | `TBD` |
| Latency đọc giỏ | Phân vị p95 và p99 tính từ khi nhận request | `TBD` |
| Latency ghi giỏ | Phân vị p95 và p99 | `TBD` |
| Độ tươi của giá | Tỷ lệ response có độ tươi là `DEGRADED` | `TBD` |
| Độ trễ outbox | Thời gian từ lúc `XADD` tới lúc `XACK` | `TBD` |
| Độ trễ đồng bộ cold store | Thời gian từ `stream_id` tới lúc ghi xong PostgreSQL | `TBD` |
| Consumer lag | Lag của `cart-db-sync` và `cart-order-consumer` | `TBD` |
| Hoàn tất cronjob | Thời gian chạy và độ trễ so với lịch | `TBD` |

Error budget chưa được thiết lập. Không dùng các cụm từ như “hiệu năng cao” hay
“độ sẵn sàng cao” trong tài liệu này khi chưa có số đo tương ứng.

Cần phân biệt hai loại suy giảm khi đặt mục tiêu: response mang giá suy giảm vẫn
là response thành công về mặt availability, nhưng nếu tỷ lệ này cao kéo dài thì
trải nghiệm đã hỏng dù mọi biểu đồ lỗi đều xanh.

## 2. Health endpoints

| Probe | Ý nghĩa | Dependency ảnh hưởng |
|---|---|---|
| Liveness | Process còn sống | Không phụ thuộc dependency ngoài |
| Readiness | Instance sẵn sàng nhận traffic | Redis là dependency cứng |
| Startup | Khởi tạo xong client và consumer group | Redis |

Redis phải nằm trong readiness vì nó là nguồn chân lý: mất Redis thì instance
không phục vụ được request nào. PostgreSQL và Kafka **không** nên nằm trong
readiness của API process — mất chúng chỉ làm hỏng đường bất đồng bộ, còn đọc và
ghi giỏ vẫn hoạt động; loại instance khỏi traffic lúc đó sẽ biến một sự cố cục bộ
thành sự cố toàn phần. Product Service cũng không nằm trong readiness vì luồng đọc
đã có đường suy giảm.

Cấu hình probe cụ thể là `TBD` cho tới khi lấy từ deployment configuration.

## 3. Configuration

| Variable/Property | Purpose | Required | Default | Secret | Restart required |
|---|---|---|---|---|---|
| Địa chỉ Redis | Primary store và outbox | Yes | `TBD` | No | Yes |
| Thông tin xác thực Redis | Kết nối Redis | Yes | — | Yes | Yes |
| Chuỗi kết nối PostgreSQL | Cold store | Yes | `TBD` | Yes | Yes |
| Danh sách broker Kafka | Produce và consume | Yes | `TBD` | No | Yes |
| Địa chỉ Product Service | Pull Model qua gRPC | Yes | `TBD` | No | Yes |
| TTL giỏ hàng | Hạn sống của cart key | Yes | 7 ngày | No | No |
| Trần số lượng mỗi SKU | `max_qty` | Yes | 100 | No | No |
| Trần số loại SKU | `max_skus` | Yes | 50 | No | No |
| Số bucket outbox | Số stream bucket trong namespace `v1`; là data contract, không chỉnh runtime | Yes | 64 | No | Yes, qua migration versioned |
| Tên relay consumer group | Group tạo trên mỗi outbox stream | Yes | `cart-outbox-relay-v1` | No | Yes |
| Deadline gRPC Product | Giới hạn thời gian pull giá | Yes | 200ms | No | No |
| Ngưỡng circuit breaker | Điều kiện mở và thời gian chờ | Yes | `TBD` | No | No |
| Kích thước lô consumer | Số message mỗi lần poll | No | `TBD` | No | No |
| Lịch cronjob đánh dấu bỏ quên | Tần suất chạy | Yes | Hằng ngày | No | No |
| Lịch cronjob dọn dữ liệu | Tần suất chạy | Yes | Hằng tuần | No | No |
| Chu kỳ trim stream | Tần suất cắt bộ nhớ stream | Yes | `TBD` | No | No |

Ngưỡng bỏ quên 30 ngày và các mốc dọn dữ liệu 3 tháng / 6 tháng là chính sách
nghiệp vụ; xem [Data §9](./data.md#9-data-lifecycle).

Đổi số bucket, namespace hoặc routing function là thay đổi phá vỡ tính liên tục,
không phải tham số chỉnh tự do. Quy trình an toàn là tạo namespace/routing version
mới, giữ relay và trimmer của version cũ chạy tới khi mọi stream cũ được drain, rồi
mới ngừng version cũ.

## 4. Observability

**Metrics.** Latency và tỷ lệ lỗi theo endpoint; tỷ lệ cache hit và miss; tỷ lệ
response suy giảm giá; trạng thái và tỷ lệ chuyển trạng thái của circuit breaker;
độ dài và số message pending của từng stream bucket; tuổi của message pending lâu
nhất; tỷ lệ produce lỗi; consumer lag theo group; số dòng bị guard `stream_id` bỏ
qua; kết quả và thời lượng từng lượt cronjob.

Nhãn metric an toàn: endpoint, kết quả, loại event, chỉ số bucket, consumer group.
Tuyệt đối không dùng `user_id`, `cart_id` hay `sku_id` làm nhãn metric — lực lượng
của chúng không giới hạn.

**Log có cấu trúc.** Các sự kiện đáng ghi: thao tác ghi bị từ chối theo quy tắc
nghiệp vụ, gọi Product thất bại và trạng thái breaker đổi, produce thất bại,
message bị bỏ do lỗi contract, transaction rollback ở consumer, publish bù của
cronjob. Không log toàn bộ payload snapshot ở mức thông tin thường.

**Trace.** Span cần có: xử lý HTTP, gọi Product qua gRPC, thực thi Lua script, truy
vấn cold store khi rehydrate, produce Kafka, xử lý một lô ở consumer.

**Định danh dùng để nối các mảnh.** `cart_id`, `sku_id`, `stream_id`, `order_id`,
request ID và định danh event. Trong đó `stream_id` là sợi chỉ xuyên suốt từ lúc
người dùng bấm nút tới lúc dòng dữ liệu xuất hiện ở cold store.

## 5. Alerts

| Alert | Condition | Severity | Owner | Runbook |
|---|---|---|---|---|
| API đọc hoặc ghi lỗi cao | Tỷ lệ `5xx` vượt ngưỡng `TBD` trong cửa sổ `TBD` | Cao | Cart on-call | [§10](#10-runbooks) |
| Redis không khả dụng | Thao tác Redis thất bại liên tục | Nghiêm trọng | Cart on-call | [§10](#10-runbooks) |
| Outbox tồn đọng | Tuổi message pending lâu nhất vượt ngưỡng `TBD` | Cao | Cart on-call | [§10](#10-runbooks) |
| Stream phình to | Độ dài stream bucket vượt ngưỡng `TBD` | Cao | Cart on-call | [§10](#10-runbooks) |
| Consumer lag cao | Lag vượt ngưỡng `TBD` kéo dài `TBD` | Trung bình | Cart on-call | [§10](#10-runbooks) |
| Có message trong dead-letter | Số message trong `t.cart.events.dlq` lớn hơn 0 | Cao | Cart on-call | [§10](#10-runbooks) |
| Tỷ lệ giá suy giảm cao | Tỷ lệ response `DEGRADED` vượt ngưỡng `TBD` | Trung bình | Cart on-call | [§10](#10-runbooks) |
| Cronjob không hoàn tất | Không có lượt chạy thành công trong chu kỳ mong đợi | Trung bình | Cart on-call | [§10](#10-runbooks) |
| Publish bù bất thường | Số event `CART_ABANDONED` publish bù tăng đột biến | Thấp | Cart on-call | [§10](#10-runbooks) |

## 6. Failure modes

| Sự cố | Hành vi của service |
|---|---|
| **Redis không khả dụng** | Đọc và ghi giỏ đều hỏng; đây là sự cố toàn phần của chức năng giỏ hàng. Không có fallback đọc cold store trong request path vì dữ liệu đó có thể đã cũ và phục vụ nó như thể đúng thì nguy hiểm hơn trả lỗi. |
| **Redis mất dữ liệu (restart, evict)** | Cart state của các key bị mất được phục hồi dần theo từng người dùng qua rehydrate. Event trong stream chưa relay thì mất hẳn nếu không có AOF — đây là lý do bật persistence là bắt buộc. |
| **PostgreSQL không khả dụng** | API đọc và ghi vẫn chạy bình thường. Rehydrate hỏng, nên người dùng bị mất key Redis sẽ thấy giỏ rỗng cho tới khi PostgreSQL trở lại. Consumer ngừng commit và lag tăng. |
| **Cạn connection pool PostgreSQL** | Giống trên nhưng khó thấy hơn: rehydrate chậm dần thay vì hỏng dứt khoát. |
| **Kafka không khả dụng** | API vẫn chạy. Relay không `XACK` được nên stream phình to; đây là rủi ro bộ nhớ Redis và là mối nguy nghiêm trọng nhất của kiến trúc này khi sự cố kéo dài. Dọn giỏ sau thanh toán ngừng hoạt động, người dùng thấy hàng đã mua vẫn nằm trong giỏ. |
| **Consumer lag tăng** | Cold store tụt lại sau Redis. Người dùng không thấy gì bất thường cho tới khi họ mất key Redis và rehydrate từ dữ liệu cũ. |
| **Product Service timeout hoặc lỗi** | Đọc giỏ suy giảm mềm: trả snapshot cũ kèm cờ độ tươi. Ghi giỏ bị từ chối vì không xác nhận được sản phẩm còn bán. |
| **Circuit breaker mở kéo dài** | Toàn bộ giá hiển thị là giá snapshot. Người dùng có thể checkout với kỳ vọng giá sai; Checkout Service phải tự kiểm tra lại giá. |
| **Deploy lẫn phiên bản** | Hai phiên bản cùng đọc ghi một cart hash. An toàn nếu định dạng snapshot chỉ thêm field optional; nguy hiểm nếu đổi ngữ nghĩa field hoặc đổi format key. |
| **Consumer group của stream biến mất** | Trimmer bắt buộc bỏ qua bucket đó thay vì trim; nếu không sẽ xóa event chưa ai đọc. |

## 7. Degraded behavior

Service vẫn phục vụ được khi:

- Product Service lỗi: đọc giỏ trả dữ liệu snapshot, đánh dấu độ tươi giá là suy
  giảm. Client hiển thị cảnh báo mềm cho người dùng.
- PostgreSQL lỗi: mọi thao tác trên giỏ đang sống trong Redis vẫn bình thường.
- Kafka lỗi: mọi thao tác trên giỏ vẫn bình thường, event xếp hàng chờ.

Service từ chối phục vụ khi:

- Redis lỗi: không có chức năng nào hoạt động.
- Ghi giỏ khi không xác nhận được sản phẩm: từ chối thay vì đoán.

Dữ liệu có thể cũ và mức độ cũ chấp nhận được:

- Giá và trạng thái khả dụng: cũ từ lần đọc giỏ trước tới lần đọc hiện tại.
- Cold store: cũ bằng độ trễ relay cộng độ trễ consumer.

## 8. Scaling và capacity

| Thành phần | Tín hiệu scale | Giới hạn |
|---|---|---|
| API process | RPS, latency, CPU | Giới hạn thực tế là connection pool tới Redis |
| Outbox Relay | Số message pending | Tối đa 64 active relay loop, một loop mỗi stream; bốn worker mặc định chạy 16 loop/worker |
| Postgres Sync consumer | Consumer lag | Không vượt số partition của `t.cart.events` |
| Order Event consumer | Consumer lag | Không vượt số partition của `t.order.events` |
| Cronjob | Không scale | Phải chạy singleton |

Topic `t.cart.events` cần đủ partition để consumer scale được; con số tối thiểu
nên đặt cao hơn số instance dự kiến ở đỉnh tải. Vì partition key là `cart_id`,
việc tăng số partition sau này sẽ đổi ánh xạ giỏ sang partition và làm mất bảo đảm
thứ tự trong thời gian chuyển tiếp — cần cân nhắc điều này ngay khi đặt số partition
ban đầu.

Nút thắt đã biết: một bucket kẹt làm chậm mọi user thuộc bucket đó. 64 bucket giảm
blast radius so với thiết kế 4 shard, nhưng số bucket vẫn là giới hạn song song và
không được thay đổi tại runtime.

Kết quả load test và giả định capacity: `TBD`.

## 9. Deployment và rollback

- Thứ tự triển khai: chạy migration cold store trước, rồi mới deploy phiên bản
  service đọc ghi cột mới. Cột mới phải nullable hoặc có mặc định để phiên bản cũ
  vẫn ghi được trong lúc rollout.
- Thay đổi định dạng snapshot phải tương thích ngược trong ít nhất một chu kỳ
  deploy, vì hai phiên bản sẽ cùng đọc một cart hash.
- Đổi namespace, routing function, số bucket hoặc tên consumer group không phải thao
  tác rollout thông thường; chúng cần kế hoạch versioned có bước drain.
- Rollback: quay lại phiên bản trước an toàn khi thay đổi chỉ thêm field. Nếu đã
  ghi field mới vào Redis, phiên bản cũ phải bỏ qua field lạ thay vì lỗi.
- Không dừng consumer lâu hơn thời gian retention của Kafka; quá mốc đó, phần dữ
  liệu chưa chiếu xuống cold store không thể replay lại được.

## 10. Runbooks

Mỗi runbook cần triệu chứng, bước chẩn đoán chỉ đọc, cách khắc phục, cách xác nhận
phục hồi và người chịu trách nhiệm leo thang. Dưới đây là khung nội dung; runbook
đầy đủ chưa được viết và không được coi là hoàn chỉnh chỉ dựa trên thiết kế này.

| Runbook | Triệu chứng | Hướng chẩn đoán chỉ đọc |
|---|---|---|
| Outbox tồn đọng | Tuổi message pending tăng, độ dài stream tăng | Kiểm tra stream và consumer group của từng bucket, Kafka, và relay worker phụ trách bucket |
| Stream phình bộ nhớ | Bộ nhớ Redis tăng đều, độ dài stream bucket lớn | Xác định bucket nào phình, kiểm tra trimmer và consumer group của bucket đó |
| Consumer lag cao | Lag của `cart-db-sync` tăng | Kiểm tra tình trạng PostgreSQL và pool, kiểm tra tỷ lệ rollback, kiểm tra phân bố partition |
| Có message trong dead-letter | Số message DLQ lớn hơn 0 | Đọc một mẫu message, xác định là lỗi contract hay lỗi tạm thời, quyết định replay hay bỏ |
| Lệch dữ liệu Redis và cold store | Người dùng báo mất hàng sau khi mất key | So sánh nội dung cart hash với các dòng cold store, kiểm tra guard `stream_id` có chặn nhầm không, cân nhắc replay từ offset cũ |
| Redis không khả dụng | Lỗi toàn phần trên mọi endpoint | Kiểm tra tình trạng cụm Redis, failover, và tình trạng persistence trước khi khởi động lại |
| Cạn connection pool PostgreSQL | Rehydrate chậm, consumer chậm | Kiểm tra số kết nối đang mở, truy vấn chạy lâu, và cấu hình pool |
| Circuit breaker của Product mở kéo dài | Tỷ lệ response suy giảm cao | Kiểm tra tình trạng Product Service và độ trễ gRPC trước khi nới ngưỡng breaker |
| Cronjob thất bại | Không có lượt chạy thành công | Kiểm tra lock singleton, log của lượt chạy gần nhất, và số dòng còn tồn đọng cần xử lý |
| Publish bù bất thường | Số event bù tăng vọt | Kiểm tra cronjob có bị crash giữa cập nhật và `XADD` không |

## 11. Workers và scheduled jobs

### Outbox Relay

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Forward event từ stream bucket sang Kafka |
| Trigger | Chạy liên tục |
| Input/Output | Entry stream → message trên `t.cart.events` |
| Concurrency | Mỗi trong 64 stream có một group `cart-outbox-relay-v1`; bốn worker phụ trách 16 stream/worker, một active loop/stream |
| Retry và timeout | Backoff khi produce lỗi; không bỏ message vì bỏ là mất dữ liệu |
| Idempotency | Định danh event sinh xác định từ `outbox_key + stream_id` |
| Chạy lại an toàn | Có; message chưa `XACK` được nhặt lại bằng `XAUTOCLAIM` |
| Metric/alert | Độ dài stream, số pending, tuổi pending lâu nhất, tỷ lệ produce lỗi |
| Cleanup liên quan | Không tự trim; việc đó thuộc Stream Trimmer |

Chi tiết luồng: [WF-CART-005](./workflows.md#wf-cart-005--relay-outbox-từ-redis-stream-sang-kafka).

### Postgres Sync consumer

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Chiếu event xuống cold store |
| Trigger | Message trên `t.cart.events`, group `cart-db-sync` |
| Input/Output | Message Kafka → dòng `carts` và `cart_items` |
| Concurrency | Nhiều instance, không vượt số partition; thứ tự theo giỏ được giữ nhờ partition key |
| Retry và timeout | Backoff ở mức lô; offset chỉ commit khi cả lô thành công |
| Idempotency | Guard `stream_id` trên mọi câu lệnh ghi |
| Chạy lại an toàn | Có; replay từ offset cũ là cách sửa lệch dữ liệu |
| Metric/alert | Consumer lag, độ trễ chiếu, tỷ lệ rollback, số dòng bị guard bỏ qua |
| Cleanup liên quan | Không |

Chi tiết luồng: [WF-CART-006](./workflows.md#wf-cart-006--chiếu-event-xuống-cold-store).

### Order Event consumer

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Dọn các SKU đã mua khỏi giỏ sau khi đơn được thanh toán |
| Trigger | `ORDER_PAID` trên `t.order.events`, group `cart-order-consumer` |
| Input/Output | Message Kafka → thao tác batch delete trên Redis |
| Concurrency | Nhiều instance; khóa idempotency chặn xử lý trùng một `order_id` |
| Retry và timeout | Redelivery của Kafka; TTL của khóa đang-xử-lý phải bao trọn một lần xử lý |
| Idempotency | Khóa `cart:v1:idemp:order-paid:o:<order_token>` cộng bản chất idempotent của thao tác xóa |
| Chạy lại an toàn | Có; lần xóa thứ hai cho kết quả rỗng và không phát event thừa |
| Metric/alert | Consumer lag, số SKU xóa mỗi đơn, tỷ lệ message bị bỏ do lỗi contract |
| Cleanup liên quan | Khóa idempotency tự hết hạn |

Chi tiết luồng: [WF-CART-007](./workflows.md#wf-cart-007--dọn-giỏ-sau-khi-đơn-hàng-được-thanh-toán).

### Abandoned Cart Marker

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Đánh dấu giỏ bỏ quên và phát tín hiệu re-engagement |
| Trigger | Lịch hằng ngày |
| Input/Output | Dòng `carts` quá ngưỡng → trạng thái `ABANDONED` và event `CART_ABANDONED` |
| Concurrency | Singleton; chạy theo lô nhỏ để không giữ lock lớn |
| Retry và timeout | Lô dừng giữa chừng là an toàn; lượt sau tiếp tục từ giỏ cũ nhất |
| Idempotency | Cập nhật có điều kiện trạng thái vẫn là `ACTIVE` |
| Chạy lại an toàn | Có; bước reconcile publish bù cho các dòng chưa có mốc publish |
| Metric/alert | Số giỏ đánh dấu, thời gian chạy, số event publish bù |
| Cleanup liên quan | Không |

Chi tiết luồng: [WF-CART-008](./workflows.md#wf-cart-008--đánh-dấu-giỏ-bị-bỏ-quên).

### Data Archival Cleaner

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Xóa dữ liệu cold store quá hạn theo chính sách retention |
| Trigger | Lịch hằng tuần |
| Input/Output | Dòng quá hạn → bị xóa hẳn |
| Concurrency | Singleton; xóa theo lô |
| Retry và timeout | Mỗi lô là một transaction; dừng giữa chừng an toàn |
| Idempotency | Có; dòng đã xóa không xuất hiện ở lượt sau |
| Chạy lại an toàn | Có |
| Metric/alert | Số dòng xóa theo trạng thái, thời gian chạy |
| Cleanup liên quan | **Thứ tự bắt buộc**: xóa `cart_items` trước, `carts` sau, cùng một transaction; không có `CASCADE` để dựa vào |

Chính sách retention: [Data §9](./data.md#9-data-lifecycle).

### Stream Trimmer

| Thuộc tính | Nội dung |
|---|---|
| Mục đích | Giải phóng bộ nhớ Redis bằng cách cắt các entry đã xử lý xong |
| Trigger | Chu kỳ định kỳ, chạy cùng process với relay |
| Input/Output | Mỗi stream bucket → `XTRIM MINID` tới mốc an toàn |
| Concurrency | Một lượt tại một thời điểm cho mỗi stream bucket |
| Retry và timeout | Bỏ qua bucket và thử lại ở chu kỳ sau khi không chắc chắn |
| Idempotency | Có; trim tới cùng một mốc nhiều lần là vô hại |
| Chạy lại an toàn | Có |
| Metric/alert | Số entry cắt mỗi lượt, độ dài stream bucket sau khi cắt, số lần buộc bỏ qua |
| Cleanup liên quan | Đây chính là cơ chế cleanup của outbox |

Quy tắc an toàn bắt buộc, theo thứ tự:

1. Nếu stream còn message nhưng **chưa có consumer group**, bỏ qua bucket ở chu kỳ
   này. Trim lúc đó sẽ xóa event chưa ai đọc.
2. Nếu consumer group tồn tại nhưng mốc đã giao không tiến trong khoảng thời gian
   dài, coi group là chết: bỏ qua và cảnh báo thay vì cắt.
3. Mốc an toàn là giá trị nhỏ hơn giữa mốc đã giao gần nhất và entry pending cũ
   nhất. Cắt quá mốc đó là mất dữ liệu.
4. `XACK` không giải phóng bộ nhớ; chỉ `XTRIM` mới làm việc đó. Thiếu bước này thì
   bộ nhớ rò rỉ dần dù mọi message đã được xử lý.
