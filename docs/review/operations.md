---
doc_id: review-operations
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence: []
verified_against_commit: null
---

# Review Service — Vận hành

> File này trả lời: "Service hỏng thì phát hiện và xử lý thế nào?"
> Mọi con số dưới đây là **mục tiêu đề xuất**, chưa được xác minh bằng đo đạc trên
> hệ thống thật.

## 1. SLO và SLI

| SLI | Cách đo | Mục tiêu đề xuất |
|---|---|---|
| Tính sẵn sàng đường đọc | Tỷ lệ response không phải 5xx của `GET /api/v1/reviews/**` | 99.9% theo tháng |
| Độ trễ đường đọc | p95 của `GET /products/{id}` với 20 mục mỗi trang | < 200 ms |
| Độ trễ internal PDP read | p95 của `GET /internal/v1/reviews/products/{id}/pdp-summary` | < 200 ms |
| Độ trễ đường đọc, trang sâu | p95 ở cursor sau 10.000 mục | < 250 ms — keyset phải giữ độ trễ gần như phẳng |
| Độ trễ đường ghi | p95 của `POST /api/v1/reviews` | < 400 ms |
| Độ hội tụ aggregate | p95 từ lúc đánh giá được commit tới lúc `product_rating_aggregates` đổi | < 5 giây |
| Độ trễ publish | p95 từ lúc dòng outbox được commit tới lúc message có mặt trên Kafka | < 3 giây |
| Consumer lag | Lag của cả ba consumer group | < 1.000 message |
| Độ chính xác aggregate | Tỷ lệ sản phẩm lệch trong lần đối chiếu hằng ngày | < 0,1% |

SLI cuối cùng tồn tại vì rating aggregator cố tình không idempotent
([ADR-005](./decisions/ADR-005-async-rating-aggregation.md)). Không đo nó thì trade-off
đó trở thành rủi ro không quan sát được.

**Không có mục tiêu về độ tươi của rating ở Search.** Đó là SLO của Search; Review
chỉ chịu trách nhiệm tới lúc message rời khỏi Kafka.

## 2. Health endpoints

| Endpoint | Kiểm tra gì | Ảnh hưởng |
|---|---|---|
| `/health/live` | Process còn sống | Kubernetes restart khi thất bại |
| `/health/ready` | Kết nối PostgreSQL còn dùng được | Loại instance khỏi traffic |
| `/health/startup` | Migration đã chạy xong | Chặn traffic tới khi sẵn sàng |

**Kafka không nằm trong readiness của API server.** Nếu Kafka hỏng, đường đọc và
đường ghi vẫn hoạt động: đánh giá được ghi vào PostgreSQL cùng dòng outbox, chỉ có
downstream là trễ. Đưa Kafka vào readiness sẽ biến một sự cố của broker thành sự cố
toàn phần của tính năng đánh giá — điều đúng ra kiến trúc outbox sinh ra để tránh.

Consumer process có readiness riêng, và readiness đó **có** phụ thuộc Kafka.

## 3. Configuration

| Variable | Purpose | Required | Default | Secret | Restart |
|---|---|---|---|---|---|
| `REVIEW_DB_URL` | Kết nối PostgreSQL | Yes | — | Yes | Yes |
| `REVIEW_DB_POOL_SIZE` | Kích thước connection pool | No | 20 | No | Yes |
| `REVIEW_DB_REPLICA_URL` | Read replica cho đường đọc | No | — | Yes | Yes |
| `REVIEW_KAFKA_BROKERS` | Danh sách broker | Yes | — | No | Yes |
| `REVIEW_WINDOW_DAYS` | Cửa sổ được phép đánh giá sau khi đơn hoàn tất | No | 30 | No | No |
| `REVIEW_REPORT_THRESHOLD` | Số báo cáo để tự động ẩn | No | 10 | No | No |
| `REVIEW_MAX_CONTENT_LENGTH` | Độ dài tối đa của nội dung | No | 5000 | No | No |
| `REVIEW_MAX_MEDIA_PER_REVIEW` | Số media tối đa | No | 9 | No | No |
| `REVIEW_LIST_MAX_LIMIT` | Số mục tối đa mỗi trang | No | 50 | No | No |
| `REVIEW_EXPORT_MAX_LIMIT` | Số mục tối đa mỗi trang export | No | 1000 | No | No |
| `REVIEW_PDP_PREVIEW_MAX_LIMIT` | Số review tối đa trong preview PDP | No | 5 | No | No |
| `REVIEW_AGGREGATOR_BATCH_SIZE` | Kích thước micro-batch | No | 200 | No | Yes |
| `REVIEW_CONSUMER_MAX_RETRIES` | Số lần retry trước dead letter | No | 10 | No | Yes |
| `REVIEW_CONSUMER_INITIAL_BACKOFF` | Backoff ban đầu | No | 1s | No | Yes |
| `REVIEW_CONSUMER_MAX_BACKOFF` | Backoff tối đa | No | 5m | No | Yes |
| `REVIEW_OUTBOX_RETENTION_DAYS` | Giữ outbox bao lâu | No | 30 | No | No |
| `REVIEW_OUTBOX_BACKLOG_ALERT_MINUTES` | Ngưỡng cảnh báo backlog | No | 15 | No | No |
| `REVIEW_MEDIA_BUCKET` | Bucket object storage | Yes | — | No | Yes |
| `REVIEW_MEDIA_UPLOAD_TTL` | Hạn của presigned URL | No | 15m | No | No |
| `REVIEW_ANONYMIZED_DISPLAY_NAME` | Tên hiển thị sau khi ẩn danh | No | `Tài khoản đã bị xóa` | No | No |

`REVIEW_REPORT_THRESHOLD` là giá trị **nhạy cảm về vận hành**: đặt quá thấp thì một
nhóm nhỏ có thể ẩn đánh giá thật; đặt quá cao thì nội dung xấu tồn tại lâu. Thay đổi
giá trị này phải được ghi nhận, và không bao giờ được lộ ra API công khai.

## 4. Observability

**Metrics theo workflow:**

| Metric | Loại | Label |
|---|---|---|
| `review_created_total` | counter | `result` |
| `review_updated_total` | counter | `result` |
| `review_reported_total` | counter | `reason` |
| `review_hidden_total` | counter | — |
| `review_takedown_total` | counter | `reason` |
| `review_list_duration_seconds` | histogram | `filter` |
| `review_aggregate_convergence_seconds` | histogram | — |
| `review_aggregate_drift_total` | counter | — |
| `review_outbox_backlog_count` | gauge | — |
| `review_outbox_publish_lag_seconds` | gauge | — |
| `review_consumer_lag` | gauge | `group` |
| `review_snapshot_export_total` | counter | `status` |
| `review_snapshot_export_duration_seconds` | histogram | — |
| `review_pdp_read_total` | counter | `result` |
| `review_pdp_read_duration_seconds` | histogram | — |
| `review_anonymized_reviews_total` | counter | — |
| `review_eligibility_granted_total` | counter | `result` |

**Label bị cấm:** `product_id`, `sku_id`, `user_id`, `review_id`. Số sản phẩm là
không giới hạn; đưa chúng vào label sẽ làm nổ cardinality. Các identifier này thuộc
về log và trace, không thuộc về metric.

**Structured log.** Mỗi event nghiệp vụ ghi một dòng có `correlation_id`,
`review_id`, `product_id`, `user_id`, `event_type` và kết quả.

**Không bao giờ ghi vào log:** `content` của đánh giá, `user_name`, và
`reporter_id`. Hai cái đầu là nội dung do người dùng nhập và có thể chứa thông tin cá
nhân. Cái thứ ba, nếu lộ, cho phép truy ra ai đã báo cáo ai.

**Trace.** `correlation_id` đi từ HTTP request → transaction → dòng outbox → message
Kafka → consumer downstream. Nhờ vậy truy được từ một lượt xem trang Search ngược về
đúng thao tác đã sinh ra dữ liệu đó.

## 5. Alerts

| Alert | Condition | Severity | Runbook |
|---|---|---|---|
| Debezium dừng | Connector ở trạng thái `FAILED` hoặc `PAUSED` quá 2 phút | Critical | [§10.1](#101-debezium-dừng-hoặc-tụt-lại) |
| Outbox backlog | `review_outbox_backlog_count` > 1000 trong 10 phút | Critical | [§10.1](#101-debezium-dừng-hoặc-tụt-lại) |
| Consumer lag cao | `review_consumer_lag` > 10.000 trong 15 phút | Warning | [§10.2](#102-consumer-lag-tăng) |
| Aggregate lệch | `review_aggregate_drift_total` > 0 trong lần đối chiếu | Warning | [§10.3](#103-aggregate-lệch) |
| Aggregate hội tụ chậm | p95 `review_aggregate_convergence_seconds` > 30s | Warning | [§10.2](#102-consumer-lag-tăng) |
| Dead letter có message | Số message trong dead letter > 0 | Warning | [§10.4](#104-message-vào-dead-letter) |
| Export snapshot lỗi | `review_snapshot_export_total{status="error"}` > 0 | Warning | [§10.5](#105-export-snapshot-lỗi-và-chặn-reindex-của-search) |
| Connection pool cạn | Thời gian chờ lấy connection p95 > 1s | Critical | [§10.6](#106-connection-pool-cạn) |
| Thiếu partition outbox | Không có partition cho tháng kế tiếp | Critical | [§10.7](#107-thiếu-partition-outbox) |
| Ẩn danh thất bại kéo dài | `review-privacy-sync` lag > 1 giờ | Critical | Nghĩa vụ tuân thủ; escalate ngay |

## 6. Failure modes

| Thành phần hỏng | Hệ quả |
|---|---|
| PostgreSQL primary không sẵn sàng | Toàn bộ đường ghi và đường đọc dừng. Không có cache nên không có degradation từng phần — đây là cái giá của [ADR-002](./decisions/ADR-002-postgresql-only-read-path.md) |
| Read replica trễ hoặc hỏng | Đường đọc chuyển về primary; độ trễ tăng nhưng vẫn phục vụ |
| Connection pool cạn | Request xếp hàng rồi timeout; thường do một truy vấn chậm hoặc do consumer và API dùng chung pool |
| Kafka không sẵn sàng | **Đường ghi vẫn hoạt động.** Đánh giá được ghi và hiển thị bình thường; event tồn trong outbox. Consumer dừng, aggregate đóng băng ở giá trị cuối |
| Debezium dừng | Như trên; outbox tăng dần. Nguy hiểm thật sự là WAL không được giải phóng vì replication slot không tiến — có thể làm đầy đĩa của primary |
| Rating aggregator dừng | Aggregate đóng băng; đánh giá mới vẫn hiển thị trong danh sách nhưng chưa được tính vào điểm trung bình |
| Object storage hỏng | Không upload được media; đánh giá chỉ có văn bản vẫn gửi được; media cũ không hiển thị |
| Order Service ngừng phát event | Không ai có quyền đánh giá cho đơn mới; đánh giá đã có không bị ảnh hưởng |
| Search ngừng gọi export | Không ảnh hưởng Review |
| Product Detail BFF ngừng gọi internal read | Không ảnh hưởng write path Review; PDP tự degrade review section |
| Triển khai lẫn phiên bản | Consumer phiên bản cũ có thể gặp trường mới trong event; consumer phải bỏ qua trường lạ |

**Chế độ hỏng nguy hiểm nhất** là Debezium dừng lâu mà không ai để ý: outbox phình,
replication slot giữ WAL, và đĩa của primary đầy — lúc đó toàn bộ service dừng. Vì
vậy cảnh báo connector đặt ở mức Critical với ngưỡng 2 phút, chứ không phải Warning.

## 7. Degraded behavior

| Tình huống | Vẫn hoạt động | Không hoạt động |
|---|---|---|
| Kafka hoặc Debezium hỏng | Đọc đánh giá, đọc aggregate, tạo và sửa đánh giá, báo cáo, phản hồi | Aggregate không cập nhật; Search và Notification không nhận được gì |
| Rating aggregator dừng | Mọi thứ trừ việc cập nhật số liệu | Điểm trung bình đứng yên |
| Object storage hỏng | Đánh giá chỉ có văn bản | Upload và hiển thị media |
| Read replica hỏng | Toàn bộ, qua primary | — |
| PostgreSQL hỏng | Không gì cả | Toàn bộ |

Product Detail BFF phải coi lỗi internal PDP read là lỗi enrichment: PDP vẫn phục vụ
catalog/availability với review section `UNAVAILABLE`. Review không trả aggregate 0 khi
database lỗi, vì trạng thái đó chỉ mang nghĩa Product thực sự chưa có review.

**Fallback không được phép:** không trả aggregate ước lượng khi bảng aggregate không
đọc được; không hiển thị đánh giá `HIDDEN_BY_REPORT` khi bộ lọc thất bại (phải trả
lỗi thay vì trả nội dung đã bị ẩn); không bỏ qua bước ghi outbox để "cứu" một request.

## 8. Scaling và capacity

| Thành phần | Scale theo | Trần |
|---|---|---|
| API server | RPS và CPU | Không có trần cứng; giới hạn thực tế là connection pool tới PostgreSQL |
| Consumer | Consumer lag | **Số partition của topic tương ứng.** Thêm instance quá số partition chỉ tạo instance rảnh |
| Reconciliation scheduler | Không scale | Đúng một instance hoạt động |
| Debezium | Không scale ngang | Một replication slot |

**Nút thắt đã biết:**

- Đường đọc chạm database ở mọi request. Sản phẩm bán chạy tạo hot partition đọc.
  Thứ tự xử lý: read replica → cache ở CDN/Gateway → chỉ khi đó mới xét cache trong
  service, kèm ADR mới.
- Rating aggregator bị giới hạn bởi số partition của `t.review.events`, và vì key là
  `product_id`, một sản phẩm viral sẽ dồn tải vào một partition duy nhất. Không thể
  chia nhỏ hơn mà không mất ordering.
- Connection pool dùng chung giữa API và consumer sẽ khiến đợt event dồn dập làm
  chậm đường đọc của người dùng. Chúng phải là process riêng với pool riêng.

Chưa có kết quả load test. Toàn bộ mục này là giả định.

## 9. Deployment và rollback

**Thứ tự triển khai:**

1. Chạy migration (chỉ các thay đổi tương thích ngược).
2. Bảo đảm partition outbox cho tháng hiện tại và tháng kế tiếp đã tồn tại.
3. Triển khai consumer trước, để chúng hiểu được event mới trước khi có event mới.
4. Triển khai API server.
5. Cập nhật cấu hình Debezium nếu cột outbox thay đổi.

**Quy tắc tương thích.** Migration phải chạy được với phiên bản code cũ đang hoạt
động. Thêm cột phải là nullable hoặc có default. Đổi tên cột phải tách thành hai lần
triển khai. Thêm giá trị enum là an toàn; xóa giá trị enum thì không.

**Rollback.** Rollback code là an toàn nếu migration tương thích ngược. Rollback
migration thì thường không — đặc biệt khi đã có dữ liệu ghi theo schema mới. Đường
phục hồi ưu tiên là tiến lên bằng một bản vá, không lùi lại.

**Điểm cần chú ý khi rollback.** Dòng outbox do phiên bản mới ghi có thể mang
`event_type` mà phiên bản cũ không biết. Consumer phiên bản cũ phải bỏ qua event
không nhận ra thay vì lỗi — nếu không, rollback sẽ tạo ra một hàng dài dead letter.

**Zero-downtime.** Đường ghi có thể chịu vài giây gián đoạn. Đường đọc thì không —
nó nằm trên trang sản phẩm, nên triển khai theo kiểu rolling với readiness probe đúng.

## 10. Runbooks

### 10.1 Debezium dừng hoặc tụt lại

**Triệu chứng:** `review_outbox_backlog_count` tăng đều; downstream không nhận event;
connector ở `FAILED` hoặc `PAUSED`; dung lượng đĩa của PostgreSQL primary tăng bất thường.

**Chẩn đoán (an toàn, chỉ đọc):**
1. Xem trạng thái và log lỗi của connector qua Kafka Connect REST API.
2. Kiểm tra replication slot: nó có đang tiến không, và đang giữ lại bao nhiêu WAL.
3. Xem dòng cũ nhất trong `review_outbox` để biết thời điểm bắt đầu tụt lại.

**Khắc phục:**
1. Nếu connector `FAILED` do lỗi tạm thời, restart connector.
2. Nếu do thay đổi schema, sửa cấu hình rồi restart.
3. Nếu WAL đang tăng nhanh tới mức nguy hiểm cho đĩa, đây là lựa chọn giữa mất event
   và mất service: **không xóa replication slot** trừ khi có người ra quyết định rõ
   ràng, vì xóa slot nghĩa là mọi event chưa publish sẽ không bao giờ được publish.
   Ưu tiên mở rộng đĩa để mua thời gian.
4. Trong lúc xử lý, giữ nguyên việc dọn partition ở trạng thái bị khóa.

**Xác nhận phục hồi:** backlog giảm về gần 0; độ trễ publish trở lại dưới 3 giây;
consumer lag giảm.

**Escalate:** chủ sở hữu hạ tầng dữ liệu, nếu WAL vẫn tăng sau khi restart.

### 10.2 Consumer lag tăng

**Triệu chứng:** `review_consumer_lag` tăng; aggregate hội tụ chậm.

**Chẩn đoán:**
1. Xác định consumer group nào đang tụt.
2. Kiểm tra có phải rebalance liên tục không (thường do `max_poll_interval_ms` quá nhỏ).
3. Kiểm tra thời gian xử lý mỗi batch và độ trễ truy vấn database.
4. Kiểm tra số instance so với số partition.

**Khắc phục:**
- Số instance ít hơn số partition → tăng instance, tối đa bằng số partition.
- Đã bằng số partition và database là nút thắt → tăng `REVIEW_AGGREGATOR_BATCH_SIZE`
  hoặc xử lý nút thắt ở database.
- Rebalance liên tục → tăng `max_poll_interval_ms`.
- Một sản phẩm viral dồn tải vào một partition → không có cách khắc phục nhanh; ghi
  nhận và chờ hết đợt.

**Xác nhận phục hồi:** lag giảm liên tục và `review_aggregate_convergence_seconds`
trở lại dưới ngưỡng.

### 10.3 Aggregate lệch

**Triệu chứng:** `review_aggregate_drift_total` khác 0; người dùng báo điểm trung
bình không khớp với danh sách đánh giá.

**Chẩn đoán:**
1. Lấy danh sách sản phẩm lệch từ log của bước đối chiếu.
2. Đếm lại thủ công từ `reviews` cho một vài sản phẩm để xác nhận.
3. Xem có sự cố consumer nào trùng thời điểm không — đếm thừa thường xuất hiện sau
   một lần crash hoặc rebalance.

**Khắc phục:**
1. Chạy lại bước đối chiếu ở `WF-REVIEW-010`, giới hạn vào các sản phẩm bị ảnh hưởng.
2. Xác nhận bản sửa **đã tăng** `review_revision`. Nếu không tăng, downstream sẽ từ
   chối bản sửa và Search sẽ giữ số cũ vĩnh viễn — đây là sai lầm dễ mắc nhất trong
   toàn bộ quy trình này.
3. Xác nhận `REVIEW_METRICS_UPDATED` đã được phát cho từng sản phẩm được sửa.

**Xác nhận phục hồi:** lần đối chiếu kế tiếp cho 0 sản phẩm lệch, và rating hiển thị
ở Search khớp với API aggregate.

### 10.4 Message vào dead letter

**Triệu chứng:** dead letter có message.

**Chẩn đoán:**
1. Đọc message và xác định nguyên nhân: thiếu trường bắt buộc, sai kiểu, hay lỗi
   tạm thời đã hết số lần retry.
2. Với `ORDER_COMPLETED`: thiếu `product_id`/`sku_id` là lỗi contract của Order.
3. Với `REVIEW_UPDATED`: thiếu `old_rating` nghĩa là phiên bản producer và consumer
   không khớp.

**Khắc phục:**
- Lỗi tạm thời → replay message vào topic gốc.
- Lỗi contract → phối hợp với service producer; **không** sửa message rồi replay,
  vì như vậy là bịa dữ liệu.
- Nếu là lỗi phiên bản, triển khai lại consumer đúng phiên bản rồi replay.

**Xác nhận phục hồi:** dead letter rỗng; dữ liệu tương ứng xuất hiện đúng.

### 10.5 Export snapshot lỗi và chặn reindex của Search

**Triệu chứng:** Search báo full reindex không cutover được; `review_snapshot_export_total{status="error"}` tăng.

**Chẩn đoán:**
1. Xem lỗi cụ thể: không chụp được offset Kafka, hay không mở được scan.
2. Kiểm tra Review có kết nối được Kafka không — bước chụp offset cần nó.
3. Kiểm tra tải database trong lúc export.

**Khắc phục:**
1. Khôi phục kết nối Kafka nếu đó là nguyên nhân.
2. Nếu do tải, phối hợp với Search để chạy lại ngoài giờ cao điểm.
3. Nếu `review_aggregate_drift_total` đang khác 0, **xử lý drift trước** rồi mới cho
   Search chạy lại — nếu không Search sẽ materialize số liệu sai.

**Xác nhận phục hồi:** Search hoàn tất một run và cutover thành công.

**Escalate:** chủ sở hữu Search, vì alias index cũ đang được giữ và mọi run đều chặn.

### 10.6 Connection pool cạn

**Triệu chứng:** thời gian chờ connection tăng; timeout rải rác trên cả đường đọc và
đường ghi.

**Chẩn đoán:**
1. Tìm truy vấn đang chạy lâu qua `pg_stat_activity`.
2. Kiểm tra có transaction nào bị treo ở trạng thái idle-in-transaction không.
3. Kiểm tra API và consumer có đang dùng chung pool không.

**Khắc phục:**
- Truy vấn chậm → xem lại kế hoạch thực thi; thường là một bộ lọc mới không dùng
  index nào đã có.
- Idle-in-transaction → tìm đường code quên commit hoặc rollback.
- Dùng chung pool → tách pool cho API và consumer.

**Xác nhận phục hồi:** thời gian chờ connection trở lại bình thường; độ trễ p95 hồi phục.

### 10.7 Thiếu partition outbox

**Triệu chứng:** **mọi** thao tác ghi thất bại với lỗi không tìm được partition.

Đây là sự cố toàn phần của đường ghi, vì mọi transaction ghi đều chèn một dòng outbox.

**Khắc phục:**
1. Tạo ngay partition cho tháng hiện tại và tháng kế tiếp.
2. Sau khi khôi phục, kiểm tra vì sao bước 7 của `WF-REVIEW-010` không chạy.

**Phòng ngừa:** cảnh báo khi không tồn tại partition cho tháng kế tiếp, chứ không
phải khi đã thiếu partition của tháng hiện tại.

## 11. Workers và scheduled jobs

### 11.1 Debezium CDC connector

| Khía cạnh | Nội dung |
|---|---|
| Loại | Kafka Connect source connector |
| Trigger | Liên tục, đọc WAL qua replication slot |
| Input/Output | `review_outbox` → `t.review.events` |
| Concurrency | Một task; không scale ngang |
| Retry | Do Kafka Connect quản lý |
| Idempotency | At-least-once; consumer phải chịu được message trùng |
| Chạy lại an toàn | Restart connector là an toàn; **xóa replication slot thì không** — event chưa publish sẽ mất vĩnh viễn |
| Metric | `review_outbox_publish_lag_seconds`, trạng thái connector |

### 11.2 order-completed consumer

| Khía cạnh | Nội dung |
|---|---|
| Trigger | `ORDER_COMPLETED` trên `t.order.events` |
| Group | `review-order-sync` |
| Concurrency | Tối đa bằng số partition |
| Idempotency | `uq_eligibility_business_key` |
| Chạy lại an toàn | Có; replay event cũ không phá trạng thái eligibility đã dùng |
| Workflow | [`WF-REVIEW-001`](./workflows.md#wf-review-001--cấp-eligibility-từ-đơn-hàng-hoàn-tất) |

### 11.3 privacy consumer

| Khía cạnh | Nội dung |
|---|---|
| Trigger | `USER_DELETED` trên `t.auth.events` |
| Group | `review-privacy-sync` |
| Concurrency | 1–2 instance; lưu lượng thấp |
| Idempotency | Điều kiện `is_anonymized = false` |
| Chạy lại an toàn | Có |
| Đặc thù | Nghĩa vụ tuân thủ — lag kéo dài phải escalate, không được để im lặng vào dead letter |
| Workflow | [`WF-REVIEW-009`](./workflows.md#wf-review-009--ẩn-danh-tác-giả-khi-tài-khoản-bị-xóa) |

### 11.4 rating aggregator consumer

| Khía cạnh | Nội dung |
|---|---|
| Trigger | `t.review.events` (self-consume) |
| Group | `review-rating-aggregator` |
| Concurrency | Tối đa bằng số partition; micro-batch 100–500 |
| Idempotency | **Không có** — xem [ADR-005](./decisions/ADR-005-async-rating-aggregation.md) |
| Chạy lại an toàn | **Không.** Replay sẽ đếm thừa. Muốn chạy lại thì phải dựng lại aggregate từ bảng `reviews` chứ không replay event |
| Metric | `review_aggregate_convergence_seconds`, `review_aggregate_drift_total` |
| Workflow | [`WF-REVIEW-007`](./workflows.md#wf-review-007--cập-nhật-aggregate-và-phát-snapshot) |

Đây là worker duy nhất **không** an toàn khi replay. Nó phải được ghi rõ ở mọi
runbook liên quan.

### 11.5 Reconciliation scheduler

| Khía cạnh | Nội dung |
|---|---|
| Trigger | 5 phút cho bước nhẹ; hằng ngày ngoài giờ cao điểm cho bước nặng |
| Concurrency | Đúng một instance, qua leader election hoặc distributed lock |
| Idempotency | Mọi bước idempotent; bước dọn partition có safety gate thay thế |
| Chạy lại an toàn | Có |
| Cleanup liên quan | Partition outbox 30 ngày; report resolved 12 tháng; media mồ côi 24 giờ |
| Workflow | [`WF-REVIEW-010`](./workflows.md#wf-review-010--reconciliation-định-kỳ) |

Bước tạo partition cho tháng kế tiếp là bước quan trọng nhất trong scheduler: nó
không sửa gì cả, nhưng nếu không chạy thì toàn bộ đường ghi sẽ dừng khi sang tháng.
