---
doc_id: inventory-operations
service: inventory
scope: current
status: proposed
owners:
  - Inventory Service team
evidence:
  - docker-compose.yml
  - java/services/inventory-service
verified_against_commit: null
---

# Inventory Service — Operations

> File này trả lời: “Service hỏng thì phát hiện và xử lý thế nào?” Ngưỡng SLO và giá
> trị alert được để `TBD` khi chưa có số liệu production; không đưa ra cam kết hiệu
> năng chưa đo được.

## 1. SLO và SLI

| SLI | Đo cái gì | Vì sao quan trọng | Mục tiêu |
|---|---|---|---|
| Tỉ lệ thành công của Reserve | Reserve trả kết quả nghiệp vụ (kể cả hết hàng) thay vì lỗi hạ tầng | Reserve hỏng là đơn hàng không đặt được | `TBD` |
| Độ trễ Reserve/Deduct/Release | Thời gian từ khi nhận Activity tới khi script trả về | Nằm trên đường nóng checkout | `TBD` |
| **Độ trễ materialize** | Từ lúc mutation vào Redis Stream tới lúc transaction PostgreSQL commit | Đây là độ rộng cửa sổ phân kỳ Redis ↔ PostgreSQL | `TBD` |
| Consumer lag `inventory-ledger-writer` | Số message chưa xử lý | Lag tăng nghĩa là sổ cái đang tụt lại | `TBD` |
| Độ trễ CDC | Từ commit tới khi event lên Kafka | Ảnh hưởng độ tươi của Search | `TBD` |
| **Số lần phát hiện drift** | Đếm mã drift từ Lua script | Mục tiêu duy nhất không phải `TBD`: **bằng 0** | 0 |
| Số stock item đang bị đóng băng | Đếm key suspension | Mỗi item bị đóng băng là một SKU không bán được | 0 |
| Tỉ lệ `INSUFFICIENT_RESERVED` khi deduct | Khách đã trả tiền nhưng hàng không còn giữ chỗ | Sự cố nghiệp vụ nghiêm trọng | ~0 |

Không dùng những từ như "hiệu năng cao" hay "độ khả dụng cao" cho service này khi chưa
có load test và số liệu production.

## 2. Health endpoints

| Probe | Phụ thuộc | Hành vi khi dependency hỏng |
|---|---|---|
| Liveness | Không phụ thuộc gì | Chỉ báo process còn sống |
| Readiness | **Redis** và PostgreSQL | Thiếu Redis thì đường nóng không chạy được; instance phải bị loại khỏi traffic |
| Startup | Kết nối Redis, PostgreSQL, Kafka | Không nhận traffic trước khi đủ kết nối |

Điểm cần cân nhắc khi cấu hình: mất Kafka **không** làm đường nóng chết ngay (Redis
vẫn commit được), nhưng làm dữ liệu ngừng chảy về PostgreSQL. Đưa Kafka vào readiness
sẽ chặn cả traffic mà service vẫn phục vụ được; để ngoài readiness thì phải có alert
riêng. Lựa chọn cụ thể là `TBD`, cần quyết định có chủ đích chứ không mặc định.

Định nghĩa path và ngưỡng probe cần lấy từ runtime configuration; tài liệu này không
bịa ra.

## 3. Configuration

| Variable/Property | Purpose | Required | Default | Secret | Restart |
|---|---|---|---|---|---|
| PostgreSQL URL/user/password | Kết nối database | Yes | — | Yes (password) | Yes |
| Redis endpoint / cluster nodes | Hot path và cache | Yes | — | Tùy hạ tầng | Yes |
| Kafka bootstrap servers | Produce và consume | Yes | — | No | Yes |
| Topic `t.inventory.events` | Topic phát event | Yes | `t.inventory.events` | No | Yes |
| Consumer group relay | Redis Stream relay | Yes | `inventory-stream-relay` | No | Yes |
| Consumer group ledger | Ghi sổ | Yes | `inventory-ledger-writer` | No | Yes |
| Poll interval của Ledger Worker | Nhịp poll Kafka | No | 100 ms | No | Yes |
| TTL idempotency key | Chống replay hot path | Yes | 30 ngày | No | Yes |
| TTL route plan | Hạn dùng của kế hoạch kho | Yes | 20 phút | No | Yes |
| TTL suspension | Dọn rác cờ đóng băng | Yes | 24 giờ | No | Yes |
| Lịch outbox cleanup | Dọn outbox | Yes | 02:00 UTC hằng ngày | No | Yes |
| Watermark outbox cleanup | Khoảng an toàn trước khi xóa | Yes | 24 giờ | No | Yes |
| Kích thước lô xóa outbox | Tránh khóa bảng | No | 10.000 dòng | No | Yes |
| Temporal namespace / task queue | Nhận Activity | Yes | — | No | Yes |
| Debezium connector config | Slot, bảng outbox, routing | Yes | — | Yes (DB credential) | Kafka Connect |

**Không** phải cấu hình của Inventory: thời hạn giữ chỗ 30 phút. Giá trị đó nằm trong
Temporal workflow của Order — xem
[ADR-006](./decisions/ADR-006-reservation-expiry-owned-by-order.md). Inventory chỉ ghi
`expires_at` lên reservation theo giá trị Order truyền xuống.

Giá trị thật của endpoint và secret không được ghi trong tài liệu.

## 4. Observability

**Metrics bắt buộc** (label phải có cardinality an toàn — dùng `warehouse_id` và
`change_type`, **không** dùng `sku_id` hay `order_id` làm label):

- Đếm kết quả Lua script theo mã trả về và loại thao tác.
- Độ trễ script và độ trễ end-to-end của mỗi Activity.
- Độ sâu Redis Stream chưa ack, theo slot.
- Consumer lag của cả hai consumer group.
- Số dòng `inventory_outbox` chưa được cleanup, độ trễ replication slot.
- Số stock item đang bị đóng băng.
- Tỉ lệ đồng bộ Redis thất bại sau khi restock commit.

**Structured log events:** mọi mutation ghi `correlation_id`, `sku_id`,
`warehouse_id`, `order_id`, `change_type` và mã kết quả. Bốn thời điểm bắt buộc phải
có log riêng: phát hiện drift, đặt cờ đóng băng, mở khóa, và thất bại đồng bộ Redis
sau commit.

**Identifier để trace:** `correlation_id` là Temporal Workflow ID với đường nóng, hoặc
`X-Correlation-Id` từ header với đường quản trị (tự sinh nếu thiếu). Nó được ghi vào
`inventory_ledger` nên có thể truy ngược từ sổ cái về nguyên nhân.

**Dashboard nên có:** một biểu đồ duy nhất so sánh tổng khả dụng theo Redis và theo
`physical_inventory` cho các SKU có lưu lượng cao. Khoảng cách giữa hai đường chính là
cửa sổ phân kỳ, và nó là chỉ số sức khỏe quan trọng nhất của service này.

## 5. Alerts

| Alert | Điều kiện | Severity | Owner | Runbook |
|---|---|---|---|---|
| Phát hiện drift | Bất kỳ lần nào script trả mã drift | Critical | Inventory on-call | [§10.1](#101-drift-và-stock-item-bị-đóng-băng) |
| Stock item bị đóng băng | Tồn tại key suspension quá `TBD` phút | Critical | Inventory on-call | [§10.1](#101-drift-và-stock-item-bị-đóng-băng) |
| Stream tồn đọng | Độ sâu stream chưa ack vượt `TBD` | High | Inventory on-call | [§10.2](#102-relay-worker-tắc) |
| Ledger consumer lag | Lag vượt `TBD` trong `TBD` phút | High | Inventory on-call | [§10.3](#103-ledger-worker-tụt-lại-hoặc-dlq-có-message) |
| DLQ có message | Số message DLQ > 0 | Critical | Inventory on-call | [§10.3](#103-ledger-worker-tụt-lại-hoặc-dlq-có-message) |
| Replication slot phình | Độ trễ slot vượt `TBD` | High | Platform + Inventory | [§10.4](#104-debezium-dừng-hoặc-replication-slot-phình) |
| Outbox không được dọn | Số dòng vượt `TBD` | Medium | Inventory on-call | [§10.4](#104-debezium-dừng-hoặc-replication-slot-phình) |
| Redis stale sau restock | Bất kỳ lần đồng bộ thất bại nào | High | Inventory on-call | [§10.5](#105-redis-lệch-postgresql-sau-restock) |
| `INSUFFICIENT_RESERVED` khi deduct | Bất kỳ lần nào | Critical | Inventory on-call | [§10.6](#106-deduct-thất-bại-sau-khi-đã-thu-tiền) |
| Redis không khả dụng | Health check Redis fail | Critical | Inventory on-call | [§10.7](#107-dependency-outage) |

## 6. Failure modes

**Redis không khả dụng.** Đường nóng dừng hoàn toàn: reserve, deduct, release đều trả
lỗi hạ tầng; lập route plan và tra cứu plan cũng hỏng, nên checkout không submit được.
Đường quản trị (restock, ship-to-customer) vẫn chạy vì chúng commit ở PostgreSQL —
nhưng bước đồng bộ Redis sau commit sẽ thất bại, để lại lệch dữ liệu cần xử lý khi
Redis quay lại. **Không** có fallback đọc `physical_inventory` để reserve: làm vậy sẽ
bỏ qua mọi mutation chưa materialize và dẫn tới bán vượt.

**PostgreSQL không khả dụng.** Đường nóng **vẫn chạy** — đây là đặc điểm dễ gây hiểu
nhầm nhất của service. Redis tiếp tục nhận reserve/deduct/release, còn Ledger Worker
dừng, nên sổ cái và projection đứng yên trong khi tồn kho thật vẫn biến động. Cửa sổ
phân kỳ nới rộng theo thời gian outage. Khi PostgreSQL trở lại, worker xử lý dồn và
hội tụ; rủi ro là Search hiển thị tồn kho cũ trong suốt thời gian đó.

**Kafka không khả dụng.** Relay Worker không produce được, không `XACK`, message ở lại
Redis Stream. Tương tự PostgreSQL: đường nóng vẫn chạy, phần ghi sổ và công bố dừng.
Redis Stream tích tụ, cần theo dõi bộ nhớ.

**Consumer lag tăng.** Ledger Worker tụt lại làm `physical_inventory` stale, kéo theo
Warehouse Selection Engine chọn kho dựa trên số cũ. Hệ quả thực tế: route plan trỏ vào
kho đã hết hàng, và reserve sẽ trả hết hàng. Đây là suy giảm chấp nhận được nhưng làm
xấu trải nghiệm checkout.

**Debezium dừng.** Outbox tích tụ, replication slot giữ WAL và ổ đĩa PostgreSQL phình.
Không mất dữ liệu, nhưng nếu để lâu có thể làm đầy đĩa và hạ cả database. Cleanup job
**không** giải quyết được: nó xóa theo watermark thời gian mà không kiểm tra CDC đã
đọc chưa, nên nếu Debezium dừng quá 24 giờ, job có thể xóa dòng chưa được publish. Đây
là rủi ro đã biết của thiết kế cleanup hiện tại.

**Triển khai nửa vời (mixed version).** Hai phiên bản Ledger Worker cùng chạy phải
thống nhất định dạng payload event tồn kho. Vì event hiện không mang revision, consumer
không có cách nào phân biệt event của bản cũ với bản mới ngoài thứ tự Kafka — rollout
phải giữ payload tương thích cả hai chiều trong suốt thời gian chuyển tiếp.

## 7. Degraded behavior

| Tình huống | Vẫn phục vụ | Bị từ chối | Dữ liệu có thể cũ |
|---|---|---|---|
| PostgreSQL chậm/hỏng | Reserve, deduct, release | Restock, ship-to-customer, export | `physical_inventory`, Search projection |
| Kafka hỏng | Toàn bộ đường nóng và đường quản trị | — | Sổ cái, projection, Search |
| Redis hỏng | Restock, ship-to-customer | Reserve, deduct, release, route plan | Redis hash |
| Ledger Worker tụt lại | Tất cả | — | Kết quả chọn kho, tồn kho hiển thị |
| Một stock item bị đóng băng | Mọi SKU khác | Mọi mutation của SKU đó | — |

**Fallback được phép:** không có. Cụ thể là không được đọc `physical_inventory` để
thay Redis ở đường nóng, và không được tự sửa dữ liệu khi phát hiện drift.

## 8. Scaling và capacity

| Thành phần | Scale theo | Giới hạn |
|---|---|---|
| API server | Request rate và độ trễ | Kích thước connection pool PostgreSQL |
| Temporal Activity worker | Độ sâu task queue | Số kết nối Redis |
| Relay Worker | Số stream đang hoạt động | Không có gì bảo đảm thứ tự **giữa** các stream |
| Ledger Worker | Consumer lag | **Không vượt số partition** của `t.inventory.events` |
| Debezium | Không scale ngang | Một replication slot; hai connector trên cùng slot sẽ hỏng |
| Outbox cleanup cron | Không scale | Phải singleton, nếu không hai job xóa chồng nhau |

**Bottleneck đã biết:** số partition của topic quyết định trần thông lượng ghi sổ. Vì
key là `sku_id`, một SKU cực kỳ nóng luôn rơi vào một partition và không thể chia nhỏ
hơn. Tăng số partition sau khi đã chạy sẽ **đổi ánh xạ key → partition**, làm sai thứ
tự trong giai đoạn chuyển tiếp; đây là thao tác cần kế hoạch riêng, không phải một
thay đổi cấu hình thông thường.

Chưa có kết quả load test. Mọi giả định về capacity trong tài liệu này là `TBD`.

## 9. Deployment và rollback

**Thứ tự triển khai:**

1. Chạy thay đổi schema trước, và phải additive (thêm cột nullable, thêm index) để bản
   cũ vẫn chạy được.
2. Triển khai Ledger Worker trước API nếu có thay đổi định dạng event, để consumer
   hiểu được event mới trước khi producer sinh ra chúng.
3. Cập nhật Debezium connector sau khi bảng outbox đã có cấu trúc mới.
4. Triển khai API server cuối cùng.

**Rollback:** đảo ngược thứ tự trên. Điểm không thể rollback: event đã publish. Quay
lui code không quay lui được state của consumer; muốn sửa phải phát lại state đúng.

**Ràng buộc zero-downtime:** stock hash Redis không được xóa hay đổi định dạng key
trong lúc chạy. Đổi cách đặt tên key là một cuộc migration dữ liệu, không phải thay
đổi cấu hình.

**Sau mọi lần restore PostgreSQL:** phải dựng lại toàn bộ stock hash Redis từ
`physical_inventory` **trước khi** mở traffic. Restore database về mốc cũ mà giữ
nguyên Redis sẽ tạo drift trên diện rộng.

## 10. Runbooks

Mỗi runbook dưới đây cần được kiểm chứng bằng một lần diễn tập trước khi coi là hoàn
chỉnh; hiện chưa runbook nào được diễn tập.

### 10.1 Drift và stock item bị đóng băng

**Triệu chứng:** alert drift; reserve/deduct trả `SKU_SUSPENDED`; tồn tại key
`inventory:suspended:*`.

**Chẩn đoán (chỉ đọc):** đọc stock hash của `(sku, kho)`; đọc dòng
`physical_inventory` tương ứng; tính tổng ledger cho `(sku, kho)` và so với
`actual_quantity`; đếm reservation đang `RESERVED` và so với `reserved_quantity`; kiểm
tra consumer lag để biết PostgreSQL có đang tụt lại không.

**Khắc phục:** xác định bên nào đúng. Nếu ledger và `physical_inventory` khớp nhau thì
PostgreSQL đúng, sửa Redis theo PostgreSQL — nhưng chỉ sau khi consumer lag về 0, nếu
không sẽ ghi đè mất mutation chưa materialize. Nếu ledger và `physical_inventory` lệch
nhau thì vấn đề nằm ở đường ghi sổ, cần điều tra trước khi động vào bất cứ thứ gì.

**Xác nhận phục hồi:** stock hash thỏa `available + reserved == total`; tổng khớp
`physical_inventory`; một reserve thử nghiệm nhỏ thành công.

**Escalation:** Inventory on-call → owner service → nếu số liệu tài chính bị ảnh hưởng
thì báo cả Order và Payment.

**Chỉ sau đó** mới gọi API mở khóa, kèm lý do và mã phiên vận hành.

### 10.2 Relay Worker tắc

**Triệu chứng:** độ sâu stream chưa ack tăng; PostgreSQL không nhận mutation mới trong
khi Redis vẫn đổi.

**Chẩn đoán:** kiểm tra Relay Worker còn sống không; kiểm tra kết nối Kafka; đọc pending
entries của consumer group `inventory-stream-relay` để tìm entry bị treo.

**Khắc phục:** khôi phục Kafka hoặc khởi động lại worker. Entry chưa ack sẽ được đọc
lại; event trùng là an toàn vì Ledger Worker dedupe theo `event_id`. **Không** xóa
entry khỏi stream để "làm sạch" — mỗi entry bị xóa là một mutation vĩnh viễn không vào
sổ cái.

**Xác nhận phục hồi:** độ sâu stream về gần 0; tổng `physical_inventory` bắt kịp Redis.

### 10.3 Ledger Worker tụt lại hoặc DLQ có message

**Triệu chứng:** consumer lag tăng, hoặc DLQ có message.

**Chẩn đoán:** đọc message trong DLQ để biết `event_id`, `sku_id`, `change_type`; kiểm
tra `inventory_ledger` xem `event_id` đó đã được ghi chưa; kiểm tra lỗi PostgreSQL
(constraint vi phạm, pool cạn).

**Khắc phục:** nếu lỗi do dependency tạm thời, khôi phục rồi replay DLQ. Nếu lỗi do vi
phạm constraint, **không** replay mù — một message không ghi được nghĩa là Redis đã
mutate nhưng sổ cái từ chối, và cần đối chiếu tay trước khi quyết định.

**Xác nhận phục hồi:** DLQ rỗng; lag về 0; tổng `physical_inventory` khớp Redis cho
các SKU liên quan.

### 10.4 Debezium dừng hoặc replication slot phình

**Triệu chứng:** event không tới Search; số dòng outbox tăng; dung lượng WAL tăng.

**Chẩn đoán:** kiểm tra trạng thái connector trên Kafka Connect; kiểm tra độ trễ của
replication slot; kiểm tra dung lượng đĩa PostgreSQL.

**Khắc phục:** khởi động lại connector. **Trước khi làm bất cứ điều gì khác, tạm dừng
outbox cleanup job** — nếu connector đã dừng quá 24 giờ, job sẽ xóa những dòng chưa
được publish. Sau khi connector bắt kịp mới bật lại job.

**Xác nhận phục hồi:** độ trễ slot về mức bình thường; số dòng outbox giảm sau lần
cleanup kế tiếp; Search nhận được snapshot mới.

**Cảnh báo:** không bao giờ xóa replication slot để giải phóng đĩa. Xóa slot làm mất
vĩnh viễn mọi event chưa đọc, và cách phục hồi duy nhất sau đó là bắt Search full
reindex qua export API.

### 10.5 Redis lệch PostgreSQL sau restock

**Triệu chứng:** log báo đồng bộ Redis thất bại sau khi restock commit; hàng vừa nhập
không bán được.

**Chẩn đoán:** so `actual_quantity` trong `physical_inventory` với `total` trong stock hash.

**Khắc phục:** ghi lại stock hash theo `physical_inventory`, nhưng phải chắc chắn
không có mutation nào đang bay — kiểm tra consumer lag và độ sâu stream về 0 trước khi
ghi đè.

**Xác nhận phục hồi:** một reserve thử nghiệm thành công với số lượng vừa nhập.

### 10.6 Deduct thất bại sau khi đã thu tiền

**Triệu chứng:** alert `INSUFFICIENT_RESERVED`; Order Saga treo ở bước sau thanh toán.

**Chẩn đoán:** tìm reservation của `(order, sku, kho)`; đọc ledger theo
`correlation_id` để dựng lại chuỗi sự kiện; xác định giữ chỗ đã bị release trước đó
hay chưa bao giờ được tạo.

**Khắc phục:** đây là quyết định nghiệp vụ, không phải kỹ thuật. Hoặc nhập bù hàng vào
kho rồi deduct lại, hoặc hủy đơn và hoàn tiền. Cả hai đều cần Order và Payment tham
gia. **Không** tự sửa `reserved` trong Redis để deduct đi qua.

**Escalation:** ngay lập tức tới Order và Payment on-call.

### 10.7 Dependency outage

**Redis:** dừng nhận traffic đường nóng (readiness sẽ tự loại instance). Khi Redis trở
lại và **dữ liệu đã mất** (Redis không bật persistence, hoặc failover mất dữ liệu),
phải dựng lại toàn bộ stock hash từ `physical_inventory` trước khi mở traffic — và
phải chờ consumer lag về 0 trước, nếu không sẽ mất các mutation chưa materialize.

**PostgreSQL:** đường nóng vẫn chạy nhưng phân kỳ nới rộng. Cân nhắc chủ động dừng
nhận reserve nếu outage kéo dài, vì càng chạy lâu thì khối lượng cần hội tụ lại càng
lớn. Ngưỡng quyết định là `TBD`.

**Kafka:** theo dõi bộ nhớ Redis vì stream tích tụ. Nếu Redis sắp đầy, đó là lúc phải
dừng đường nóng.

## 11. Workers và scheduled jobs

### 11.1 Stream Relay Worker

| Khía cạnh | Nội dung |
|---|---|
| Mục đích | Forward event mutation từ Redis Stream sang Kafka |
| Trigger | Chạy liên tục; consumer group `inventory-stream-relay` |
| Input/Output | Redis Stream entry → message Kafka trên `t.inventory.events`, key `sku_id` |
| Concurrency | Nhiều instance chia stream qua consumer group |
| Retry/timeout | Exponential backoff cho tới khi Kafka nhận; **`XACK` chỉ sau khi Kafka xác nhận** |
| Idempotency | Không tự dedupe; dựa vào `event_id` ở Ledger Worker |
| Chạy lại an toàn | Có — entry chưa ack được đọc lại |
| Metric/alert | Độ sâu stream, độ trễ forward, tỉ lệ lỗi produce |
| Cleanup | Chính sách trim stream **chưa được ghi nhận** — cần chốt để stream không phình vô hạn |

### 11.2 Kafka Ledger Worker

| Khía cạnh | Nội dung |
|---|---|
| Mục đích | Materialize mutation thành ledger, reservation, `physical_inventory` và outbox |
| Trigger | Poll Kafka 100 ms; consumer group `inventory-ledger-writer` |
| Input/Output | `INVENTORY_STOCK_MUTATION` → một transaction PostgreSQL |
| Concurrency | Tối đa bằng số partition; thứ tự trong một SKU được giữ |
| Retry/timeout | Retry theo consumer; lỗi vĩnh viễn vào DLQ |
| Idempotency | `inventory_ledger.event_id UNIQUE` |
| Chạy lại an toàn | Có, với điều kiện dedupe hoạt động |
| Metric/alert | Lag, DLQ, thời gian transaction, số lần dedupe |
| Lưu ý | **Phải lọc theo `event_type`**, nếu không sẽ tự tiêu thụ event mình sinh ra |

### 11.3 Debezium CDC connector

| Khía cạnh | Nội dung |
|---|---|
| Mục đích | Đọc WAL bảng outbox và publish lên Kafka |
| Trigger | Liên tục qua logical replication slot |
| Input/Output | Dòng `inventory_outbox` → message Kafka, key lấy từ cột `partition_key` |
| Concurrency | Một connector duy nhất trên một slot |
| Retry | Do Kafka Connect runtime xử lý |
| Idempotency | Consumer dedupe theo CloudEvents `id` |
| Chạy lại an toàn | Có; offset được giữ trong Connect |
| Metric/alert | Trạng thái connector, độ trễ slot, dung lượng WAL |
| Ràng buộc | Không bóc JSON payload ở tầng Connect |

### 11.4 Outbox cleanup cron

| Khía cạnh | Nội dung |
|---|---|
| Mục đích | Giữ bảng outbox ở kích thước ổn định |
| Trigger | 02:00 UTC hằng ngày |
| Input/Output | Xóa dòng cũ hơn watermark, theo lô 10.000 |
| Concurrency | Bắt buộc singleton |
| Retry | Chạy lại ở lần lịch kế tiếp |
| Idempotency | Có — xóa dòng đã xóa là không tác dụng |
| Chạy lại an toàn | Có, **trừ khi Debezium đang dừng** |
| Metric/alert | Số dòng đã xóa, thời gian chạy, số dòng còn lại |
| Rủi ro đã biết | Watermark dựa trên thời gian chứ không dựa trên offset CDC đã đọc. Nếu Debezium dừng quá 24 giờ, job có thể xóa dòng chưa publish. Runbook [§10.4](#104-debezium-dừng-hoặc-replication-slot-phình) yêu cầu tạm dừng job trong sự cố CDC. |

### 11.5 Reservation archive job

| Khía cạnh | Nội dung |
|---|---|
| Mục đích | Chuyển reservation đã kết thúc quá 90 ngày sang cold storage |
| Trigger | Định kỳ; lịch cụ thể `TBD` |
| Concurrency | Singleton |
| Idempotency | Theo lô, có thể chạy lại |
| Metric/alert | Số dòng archive, thời gian chạy |
| Lưu ý | Chỉ archive dòng `CANCELLED`/`DEDUCTED`; dòng `RESERVED` treo lâu là dấu hiệu Saga hỏng và cần điều tra, không phải archive |

**Không có** worker quét reservation hết hạn. Việc đó thuộc Temporal timer của Order —
xem [ADR-006](./decisions/ADR-006-reservation-expiry-owned-by-order.md). Index
`idx_reservations_status_expires` tồn tại để truy vấn dashboard, không phải để một job
nào đó tự hủy giữ chỗ.

**Không có** worker reconciliation Redis ↔ PostgreSQL. Đối soát là quy trình thủ công
theo runbook [§10.1](#101-drift-và-stock-item-bị-đóng-băng).
