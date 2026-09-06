---
doc_id: review-development
service: review
scope: current
status: proposed
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
verified_against_commit: null
---

# Review Service — Development Guide

> Review Service **chưa có implementation**. Runtime, ngôn ngữ và framework chưa được
> chọn, nên file này mô tả những gì đã xác định được — dependency, schema, fixture,
> tiêu chí hoàn thành — và đánh dấu rõ phần nào còn chờ quyết định về runtime.

## 1. Prerequisites

| Công cụ | Mục đích | Trạng thái |
|---|---|---|
| Docker và Docker Compose | Chạy PostgreSQL, Kafka và Kafka Connect | Đã xác định |
| PostgreSQL 14+ với `wal_level = logical` | Database và CDC | Đã xác định |
| Kafka + Kafka Connect + Debezium PostgreSQL connector | Publish outbox | Đã xác định |
| Object storage tương thích S3 (MinIO cho local) | Media của đánh giá | Đã xác định |
| Runtime của service | Chạy service | **Chưa chọn** |
| Migration tool | Áp dụng schema | **Chưa chọn** |

Người dùng repository này build Java bằng Docker Maven vì máy không có JDK cục bộ;
nếu Review chọn runtime JVM thì cần theo cách đó.

## 2. Chạy local

Cho tới khi runtime được chọn, phần chạy được là hạ tầng và schema:

```bash
docker compose up -d postgres kafka kafka-connect minio
```

Áp dụng schema:

```bash
docker compose exec -T postgres psql -U postgres -d ecommerce < docs/review/schema/0001_initial_review_schema.sql
```

Kiểm tra schema đã tạo đúng:

```bash
docker compose exec postgres psql -U postgres -d ecommerce -c "\dt review.*"
```

Các bước còn thiếu cho tới khi có runtime: cài dependency, build, khởi động service,
gọi health endpoint, dừng và dọn dẹp.

## 3. Local dependencies

| Dependency | Port mặc định | Bắt buộc | Mock được không |
|---|---|---|---|
| PostgreSQL | 5432 | Có | Không — mọi invariant nằm trong constraint của database |
| Kafka | 9092 | Có cho luồng event | Có, khi chỉ test đường HTTP |
| Kafka Connect + Debezium | 8083 | Chỉ khi test luồng publish | Có — test có thể đọc thẳng bảng `review_outbox` |
| MinIO | 9000 | Chỉ khi test media | Có |
| Order Service | — | Không | Dùng event fixture |
| Auth Service | — | Không | Dùng event fixture |
| Search Service | — | Không | Search là caller, không phải dependency |

**Không cần Redis.** Review không có cache; xem
[ADR-002](./decisions/ADR-002-postgresql-only-read-path.md). Nếu ai đó thêm Redis vào
compose cho Review, đó là dấu hiệu thiết kế đã đi lệch khỏi ADR.

Phần lớn test không cần Debezium: transaction ghi outbox là ranh giới trách nhiệm của
service, và việc kiểm tra "có đúng một dòng outbox với `partition_key` đúng" đủ để
xác nhận hành vi. Debezium chỉ cần cho test end-to-end thật sự.

## 4. Local configuration

Biến môi trường theo bảng ở [Operations, mục 3](./operations.md#3-configuration).

Giá trị nên đổi khi phát triển local:

| Biến | Giá trị local | Lý do |
|---|---|---|
| `REVIEW_WINDOW_DAYS` | `1` hoặc nhỏ hơn | Test được luồng hết hạn mà không phải chờ 30 ngày |
| `REVIEW_REPORT_THRESHOLD` | `2` | Chạm ngưỡng ẩn mà không cần tạo 10 tài khoản |
| `REVIEW_CONSUMER_MAX_RETRIES` | `2` | Thấy dead letter nhanh thay vì chờ backoff tới 5 phút |

**Không bao giờ commit:** chuỗi kết nối database thật, khóa object storage, và bất kỳ
dump dữ liệu nào có đánh giá thật — nội dung do người dùng viết là PII.

## 5. Build, lint và format

Chờ quyết định về runtime. Khi có, các lệnh này phải trùng với lệnh CI chạy.

Phần đã kiểm tra được ngay: schema SQL phải áp dụng sạch vào một database rỗng. CI
nên chạy chính lệnh ở mục 2 trên container mới và coi lỗi là fail.

## 6. Tests

**Unit test.** Business rule ở [Domain, mục 4](./domain.md#4-business-rules) — chuyển
trạng thái eligibility, hạn mức sửa, tính ngưỡng báo cáo, phép tính delta của
aggregate. Không cần database.

**Integration test.** Cần PostgreSQL thật, không dùng in-memory database: gần như mọi
invariant của service đều nằm trong constraint, và constraint chỉ tồn tại ở PostgreSQL.

Bắt buộc phủ:

- Mọi constraint ở [Data, mục 4](./data.md#4-constraints) thực sự từ chối dữ liệu sai.
- Tạo đánh giá song song chỉ thành công một lần.
- Nhiều báo cáo đồng thời quanh ngưỡng chỉ sinh một `REVIEW_HIDDEN`.
- Mỗi bộ lọc của đường đọc dùng đúng index dự kiến, kiểm bằng `EXPLAIN`.
- Mỗi transaction ghi để lại đúng số dòng outbox mong đợi, với `partition_key` là
  `product_id`.
- Aggregate hội tụ đúng qua chuỗi tạo → sửa → ẩn → hiện lại → gỡ.
- Rating aggregator bỏ qua `REVIEW_METRICS_UPDATED` và không tự tạo vòng lặp.

**Contract test.** Hai file contract đã có phải được kiểm chứng:

- [`asyncapi.yaml`](../../contracts/events/review/v1/asyncapi.yaml): payload
  `REVIEW_METRICS_UPDATED` khớp schema.
- [`openapi.yaml`](../../contracts/http/review/internal/v1/openapi.yaml): response
  export khớp schema, `replay_from_offsets` giống hệt nhau ở mọi trang của một session;
  PDP read chỉ chấp nhận service identity BFF và phân biệt aggregate 0 với lỗi 503.

Test bắt buộc quan trọng nhất: **snapshot export và event phát ra cho cùng một trạng
thái phải mang cùng một `review_revision`.** Đây là điều kiện Search dựa vào để
reindex mà không mất rating; xem
[Search ADR-016](../search/decisions/ADR-016-review-rating-snapshot-reindex.md).

**End-to-end test.** Cần đủ PostgreSQL, Kafka và Debezium: ghi một đánh giá, xác nhận
message xuất hiện trên `t.review.events` với đúng key, và aggregate thay đổi.

## 7. Contract code generation

Chờ quyết định về runtime. Yêu cầu cố định: model của event và của response export
phải được **sinh ra** từ file contract, không viết tay. Nếu viết tay, contract và code
sẽ lệch nhau mà không có gì phát hiện được.

CI phải chạy lại bước generate và fail nếu output khác với code đã commit.

Ba interaction chưa có contract — public HTTP API, event vòng đời đánh giá, và subset
của `ORDER_COMPLETED`/`USER_DELETED` mà Review dùng — cần specification trước khi
generate có ý nghĩa. Xem [README, mục 9](./README.md#9-known-gaps).

## 8. Database migration

[`0001_initial_review_schema.sql`](./schema/0001_initial_review_schema.sql) là schema
khởi tạo. Khi chọn migration tool, migration đầu tiên phải sinh ra schema giống hệt
file này.

**Quy tắc cho migration về sau:**

- Phải chạy được với phiên bản code cũ đang hoạt động.
- Thêm cột thì nullable hoặc có default.
- Đổi tên cột phải tách thành hai lần triển khai.
- Thêm giá trị enum là an toàn; xóa thì không.
- Mọi index trên bảng lớn phải tạo bằng `CREATE INDEX CONCURRENTLY`.
- Đổi schema của `review_outbox` phải phối hợp với cấu hình Debezium.

**Partition.** Trước khi triển khai, phải chắc chắn có partition outbox cho tháng
hiện tại và tháng kế tiếp. Thiếu partition làm **mọi** thao tác ghi thất bại; xem
[Operations §10.7](./operations.md#107-thiếu-partition-outbox).

**Seed dữ liệu test.** Đặt trong fixture, không trong migration.

## 9. Test data và fixtures

Fixture cần có:

| Fixture | Nội dung |
|---|---|
| `order-completed.json` | Đơn một item và đơn nhiều item; đơn có `completed_at` cũ hơn cửa sổ đánh giá; đơn thiếu `variant_name` |
| `user-deleted.json` | Cả hai giá trị `reason` |
| `review-metrics-updated.json` | Snapshot hợp lệ; snapshot có revision cũ hơn; snapshot trùng |
| Seed đánh giá | Sản phẩm không có đánh giá; sản phẩm có vài đánh giá; sản phẩm có hàng nghìn đánh giá để test phân trang sâu |

**Tuyệt đối không** dùng đánh giá thật từ production làm fixture. Nội dung đánh giá là
văn bản do người dùng viết và có thể chứa thông tin cá nhân. Sinh dữ liệu giả.

## 10. Debugging

**Kiểm tra outbox** — nơi cần nhìn đầu tiên khi downstream không nhận được gì:

```bash
docker compose exec postgres psql -U postgres -d ecommerce -c "SELECT event_type, partition_key, created_at FROM review.review_outbox ORDER BY created_at DESC LIMIT 20;"
```

**Kiểm tra aggregate so với số đếm thực** — cách nhanh nhất để xác nhận có drift:

```bash
docker compose exec postgres psql -U postgres -d ecommerce -c "SELECT a.product_id, a.total_reviews, count(r.id) AS actual FROM review.product_rating_aggregates a LEFT JOIN review.reviews r ON r.product_id = a.product_id AND r.status = 'APPROVED' GROUP BY a.product_id, a.total_reviews HAVING a.total_reviews <> count(r.id);"
```

**Đọc message trên topic:**

```bash
docker compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic t.review.events --from-beginning --property print.key=true
```

**Trạng thái Debezium:**

```bash
curl -s http://localhost:8083/connectors/review-outbox-connector/status
```

**Thao tác an toàn khi debug:** đọc bảng, đọc topic, restart connector, chạy lại
reconciliation.

**Thao tác không an toàn, kể cả ở local nếu đang chia sẻ môi trường:** xóa
replication slot (mất event chưa publish), `DROP` partition outbox, sửa
`review_revision` bằng tay (làm downstream mắc kẹt vĩnh viễn ở guard).

## 11. Các lỗi local phổ biến

| Triệu chứng | Nguyên nhân thường gặp | Cách kiểm tra |
|---|---|---|
| Debezium không bắt được thay đổi | `wal_level` không phải `logical` | `SHOW wal_level;` |
| Ghi thất bại với lỗi partition | Thiếu partition outbox cho tháng hiện tại | `\d+ review.review_outbox` |
| Aggregate không đổi sau khi tạo đánh giá | Rating aggregator không chạy, hoặc Kafka chưa lên | Kiểm tra outbox có dòng không; kiểm tra consumer lag |
| Aggregate cao hơn số đánh giá thực | Event đã bị xử lý lại — đúng như hạn chế đã biết | Chạy truy vấn đối chiếu ở mục 10 |
| Consumer chạy vòng lặp vô tận | Aggregator đang xử lý `REVIEW_METRICS_UPDATED` | Kiểm tra bộ lọc `event_type` của consumer |
| Test lật trang cho kết quả không ổn định | Dùng `OFFSET` thay vì keyset | Xem lại truy vấn |
| Tạo đánh giá luôn trả `409` | Eligibility đã ở `REVIEWED` từ lần chạy trước | Kiểm tra `review_eligibilities` |
| Ghi vào eligibility bị từ chối không rõ lý do | `ck_eligibility_status_matches_attempts` — `status` và `attempt_count` không khớp nhau | Đọc thông báo lỗi constraint |

## 12. Definition of Done

Một thay đổi của Review Service chỉ hoàn thành khi, tương ứng với phạm vi thay đổi:

- Build, lint và test chạy thành công.
- Contract machine-readable được cập nhật nếu interface thay đổi, và contract test
  chứng minh chúng khớp.
- Internal PDP read có provider test phía Review và consumer test phía BFF; BFF không
  truy cập schema Review hoặc nhận command Review.
- [`0001_initial_review_schema.sql`](./schema/0001_initial_review_schema.sql) và
  [Data](./data.md) được cập nhật cùng nhau nếu schema thay đổi. Cập nhật một trong
  hai là để lại tài liệu sai.
- [Workflows](./workflows.md) hoặc [Domain](./domain.md) được cập nhật nếu hành vi
  thay đổi, bao gồm cả failure path chứ không chỉ happy path.
- ADR mới được thêm nếu có quyết định kiến trúc; ADR bị thay thế được đánh dấu
  `Superseded` và liên kết hai chiều.
- Tính tương thích ngược được kiểm tra: migration chạy được với code cũ; consumer cũ
  bỏ qua được trường mới.
- Nếu thay đổi chạm vào aggregate hoặc `review_revision`, phải chứng minh downstream
  vẫn hội tụ — bao gồm trường hợp event cũ, event trùng, và snapshot import chạy song
  song với replay.
- Trạng thái và `evidence` trong front-matter của tài liệu bị ảnh hưởng được cập nhật.
