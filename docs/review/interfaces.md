---
doc_id: review-interfaces
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
verified_against_commit: null
---

# Review Service — Giao diện tích hợp

> File này trả lời: "Service nhận và gửi thông tin gì?" Contract machine-readable
> là nguồn chuẩn cho payload; file này giải thích semantics và hành vi.

## 1. Danh mục contract

| Interaction | Direction | Protocol | Provider | Consumers | Contract | Status |
|---|---|---|---|---|---|---|
| Đọc đánh giá và aggregate | Inbound | HTTP | Review | Client qua Gateway | Chưa có OpenAPI | proposed |
| Ghi đánh giá | Inbound | HTTP | Review | Client qua Gateway | Chưa có OpenAPI | proposed |
| Kiểm duyệt và phản hồi | Inbound | HTTP | Review | Admin console | Chưa có OpenAPI | proposed |
| PDP aggregate + review preview | Inbound | HTTP nội bộ | Review | Product Detail BFF | [Review internal OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) | proposed |
| Export rating aggregate snapshot | Inbound | HTTP nội bộ | Review | Search | [Review internal OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) | proposed |
| Rating aggregate snapshot | Outbound | Kafka | Review | Search | [AsyncAPI Review v1](../../contracts/events/review/v1/asyncapi.yaml) | proposed |
| Event vòng đời đánh giá | Outbound | Kafka | Review | Notification, Analytics | Chưa có AsyncAPI | proposed |
| `ORDER_COMPLETED` | Inbound | Kafka | Order | Review | Chưa có AsyncAPI cho subset Review dùng | proposed |
| `USER_DELETED` | Inbound | Kafka | Auth | Review | Chưa có AsyncAPI | proposed |

Bốn interaction chưa có contract machine-readable là gap đã biết. Chúng phải có
specification trước khi bất kỳ consumer nào được coi là `verified`.

## 2. HTTP API công khai

Toàn bộ nằm dưới `/api/v1/reviews`, đi qua API Gateway. Gateway đã xác thực JWT và
truyền `user_id` canonical xuống; Review không tự xác thực credential nhưng **luôn**
tự kiểm tra quyền sở hữu tài nguyên.

### 2.1 Đọc đánh giá của một sản phẩm

`GET /api/v1/reviews/products/{product_id}`

Không yêu cầu đăng nhập. Trả danh sách đánh giá đang ở `APPROVED` của một sản phẩm,
mặc định sắp xếp mới nhất trước.

- **Lọc:** `sku_id` (chỉ biến thể cụ thể), `rating` (chỉ một mức sao),
  `has_media=true` (chỉ đánh giá có ảnh/video). Mỗi bộ lọc tương ứng với một index
  ở [Data, mục 5](./data.md#5-indexes-và-query-patterns).
- **Phân trang:** keyset qua `cursor` mã hóa `(created_at, id)`, kèm `limit`. Không
  hỗ trợ `offset`; sản phẩm bán chạy có quá nhiều đánh giá để `OFFSET` sâu là an toàn.
- **Nội dung trả về:** điểm, nội dung, media, tên hiển thị tác giả (đã ẩn danh nếu
  tài khoản bị xóa), biến thể đã mua, và phản hồi của quản trị viên nếu có.
- **Không bao giờ trả về:** `reporter_id`, `report_count`, `eligibility_id`, hoặc
  bất kỳ đánh giá nào không ở `APPROVED`.
- **Error:** `404` nếu `product_id` không có aggregate và không có đánh giá nào —
  Review không phân biệt được "sản phẩm không tồn tại" với "sản phẩm chưa có đánh
  giá", nên trả danh sách rỗng với `200` là hành vi đúng; `404` chỉ dành cho cursor
  không hợp lệ.

### 2.2 Đọc số liệu tổng hợp

`GET /api/v1/reviews/products/{product_id}/aggregate`

Không yêu cầu đăng nhập. Trả điểm trung bình, tổng số đánh giá, phân bố theo sao và
số đánh giá có media.

- **Độ trễ dữ liệu:** aggregate được cập nhật bất đồng bộ, thường trễ 1–5 giây so
  với đánh giá mới nhất. Client hiển thị hai thứ trên cùng một trang cần biết rằng
  danh sách đánh giá có thể "mới hơn" con số tổng hợp.
- **Sản phẩm chưa có đánh giá:** trả `200` với toàn bộ giá trị bằng 0, không phải
  `404`. Việc khởi tạo giá trị mặc định cho SKU mới thuộc về Search; xem
  [Search ADR-016](../search/decisions/ADR-016-review-rating-snapshot-reindex.md).

### 2.3 Danh sách chờ đánh giá

`GET /api/v1/reviews/me/pending`

Yêu cầu đăng nhập. Trả các eligibility đang ở `PENDING` và chưa hết hạn của chính
người gọi, kèm `product_id`, `sku_id`, `variant_name` snapshot và `expires_at`.

- **Authorization:** chỉ trả dữ liệu của `user_id` trong token; không nhận tham số
  `user_id` từ client.

### 2.4 Tạo đánh giá

`POST /api/v1/reviews`

Yêu cầu đăng nhập. Body mang `eligibility_id`, `rating`, `content` tùy chọn và danh
sách object key của media đã upload.

- **Precondition:** eligibility thuộc người gọi, đang ở `PENDING`, chưa hết hạn.
- **Postcondition:** eligibility chuyển `REVIEWED` với `attempt_count = 1`; một
  đánh giá `APPROVED` tồn tại; một event `REVIEW_CREATED` nằm trong outbox.
- **Idempotency:** không dùng header `Idempotency-Key`. Bản thân việc chuyển trạng
  thái eligibility đã là khóa: gửi lại cùng request sẽ thất bại với
  `ALREADY_REVIEWED` chứ không tạo đánh giá thứ hai. Thiết kế cũ có nhắc tới
  `Idempotency-Key` nhưng không mô tả nơi lưu kết quả; nó không cần thiết ở đây.
- **Response:** `201` kèm toàn bộ đánh giá vừa tạo, để tác giả đọc được thay đổi của
  chính mình ngay lập tức mà không phải chờ pipeline bất đồng bộ.
- **Error:** `403 NOT_ELIGIBILITY_OWNER`, `409 ALREADY_REVIEWED`,
  `410 ELIGIBILITY_EXPIRED`, `400` cho các lỗi validate nội dung và media.

### 2.5 Sửa đánh giá

`PATCH /api/v1/reviews/{review_id}`

Yêu cầu đăng nhập, chỉ tác giả. Cho phép đổi `rating`, `content` và danh sách media.

- **Precondition:** đánh giá ở `APPROVED`; eligibility ở `REVIEWED` với
  `attempt_count = 1`.
- **Postcondition:** eligibility chuyển `EDIT_LOCKED` với `attempt_count = 2`; event
  `REVIEW_UPDATED` mang cả điểm cũ và điểm mới nằm trong outbox.
- **Replay:** gọi lại lần thứ hai trả `409 EDIT_QUOTA_EXHAUSTED`. Đây là hành vi
  đúng chứ không phải lỗi idempotency — hạn mức sửa là một quy tắc nghiệp vụ, không
  phải hiệu ứng phụ của việc gửi trùng.
- **Error:** `403 NOT_REVIEW_AUTHOR`, `409 EDIT_QUOTA_EXHAUSTED`,
  `409 REVIEW_NOT_EDITABLE`.

### 2.6 Xin URL upload media

`POST /api/v1/reviews/media/upload-urls`

Yêu cầu đăng nhập. Trả một hoặc nhiều presigned URL trỏ vào namespace tạm của chính
người dùng, kèm object key tương ứng.

- File **không** đi qua Review; client upload thẳng lên object storage.
- Object key chứa `user_id` để Review kiểm tra được quyền sở hữu khi người dùng gửi
  đánh giá. Key thuộc namespace của người khác bị từ chối với
  `400 INVALID_MEDIA_REFERENCE`.
- Presigned URL có hạn ngắn. Object đã upload nhưng không bao giờ được gắn vào đánh
  giá sẽ bị dọn sau 24 giờ; xem [Data, mục 9](./data.md#9-data-lifecycle).

### 2.7 Báo cáo đánh giá

`POST /api/v1/reviews/{review_id}/reports`

Yêu cầu đăng nhập. Body mang `reason` và `detail` tùy chọn.

- **Precondition:** người gọi không phải tác giả; chưa từng báo cáo đánh giá này.
- **Postcondition:** một dòng report `PENDING`; `report_count` tăng; nếu đạt ngưỡng
  thì đánh giá chuyển `HIDDEN_BY_REPORT` và event `REVIEW_HIDDEN` nằm trong outbox.
- **Idempotency:** `uq_report_review_reporter`. Báo cáo lại trả `409 ALREADY_REPORTED`.
- **Response:** `202` với thông báo trung tính. Response **không** tiết lộ
  `report_count` hiện tại hay việc đánh giá đã bị ẩn hay chưa — nếu tiết lộ, một
  nhóm người dùng có thể dò ra chính xác ngưỡng và phối hợp để ẩn đánh giá thật.

## 3. HTTP API quản trị

Dưới `/api/v1/admin/reviews`, yêu cầu role admin.

| Endpoint | Mục đích | Ghi chú |
|---|---|---|
| `GET /reports?status=PENDING` | Hàng đợi kiểm duyệt | Phân trang theo `created_at` |
| `POST /{review_id}/replies` | Tạo hoặc sửa phản hồi chính thức | Upsert; chỉ lần **tạo mới** phát `ADMIN_REPLY_CREATED`, sửa nội dung không phát event để tránh gửi thông báo lặp |
| `POST /{review_id}/dismiss-reports` | Bác bỏ báo cáo và hiện lại đánh giá | Đánh giá về `APPROVED`, report về `DISMISSED`, phát `REVIEW_APPROVED` |
| `POST /{review_id}/takedown` | Gỡ nội dung theo yêu cầu pháp lý | Bắt buộc `legal_case_id` và người phê duyệt khác người yêu cầu; phát `REVIEW_REMOVED` |

`REVIEW_APPROVED` chỉ có đúng một nguồn phát: thao tác bác bỏ báo cáo ở trên. Thiết
kế cũ định nghĩa event này nhưng không có luồng nào tạo ra nó — gắn nó vào luồng
hiện-lại-đánh-giá làm cho nó có nghĩa và cho phép aggregator cộng lại đánh giá đã bị
trừ khi ẩn.

## 4. HTTP API nội bộ

### 4.1 PDP aggregate và review preview

`GET /internal/v1/reviews/products/{product_id}/pdp-summary` —
[contract](../../contracts/http/review/internal/v1/openapi.yaml)

Chỉ dành cho service identity của Product Detail BFF. Endpoint trả aggregate hiện tại
và tối đa `preview_limit` review `APPROVED` mới nhất, kèm author display name đã ẩn danh
nếu cần. Không trả eligibility, report counter, reporter, moderation state hoặc
`review_revision`.

Product chưa có review trả `200` với aggregate 0 và preview rỗng. PostgreSQL/lỗi hạ tầng
trả `503`; BFF chịu trách nhiệm map thành review section `UNAVAILABLE`, không được đổi
thành aggregate 0. Endpoint chỉ đọc và không thay thế public pagination/filter API.

### 4.2 Export rating aggregate snapshot

`GET /internal/v1/reviews/rating-aggregates` —
[contract](../../contracts/http/review/internal/v1/openapi.yaml)

Chỉ dành cho service identity của Search. Không đi qua API Gateway và phải bị chặn ở
ingress đối với traffic bên ngoài. Endpoint này **không** thay thế API aggregate công
khai ở mục 2.2.

**Ngữ nghĩa quan trọng nhất — `replay_from_offsets`:**

1. Khi bắt đầu một export session, Review chụp offset hiện tại của **mọi** partition
   của `t.review.events` **trước** khi bắt đầu scan.
2. Tập offset này được trả lại **không đổi** ở mọi trang của cùng session.
3. Offset là vị trí đầu tiên Search phải replay, tính **inclusive**.
4. Search import toàn bộ snapshot xong mới seek vào các offset đó và replay
   `REVIEW_METRICS_UPDATED`.

Vì offset được chụp trước khi scan, mọi mutation xảy ra trong lúc export đều nằm sau
watermark và sẽ được replay. Mutation xảy ra trước watermark đã có trong snapshot.
Guard `review_revision` xử lý phần chồng lấn: event cũ hơn hoặc bằng revision đã
materialize là no-op.

**Phân trang:** keyset theo `product_id` qua `after_product_id`, mặc định 500 mục,
tối đa 1000.

**Thất bại:** nếu Review không mở được scan nhất quán hoặc không chụp được offset,
trả `503`. Search **không được** cutover index mới; nó phải hủy hoặc chạy lại run.
Điều này biến Review thành dependency chặn full reindex của Search — đánh đổi có chủ
đích, xem [ADR-006](./decisions/ADR-006-rating-snapshot-export.md).

## 5. Event phát ra

Tất cả trên topic `t.review.events`, `partition_key = product_id`, delivery
at-least-once, phát qua outbox + Debezium
([ADR-003](./decisions/ADR-003-outbox-cdc-publication.md)).

| Event | Phát khi | `aggregate_id` | Consumer chính |
|---|---|---|---|
| `REVIEW_CREATED` | Đánh giá được tạo | `review_id` | Rating aggregator, Notification, Analytics |
| `REVIEW_UPDATED` | Tác giả sửa đánh giá, hoặc tác giả bị ẩn danh | `review_id` | Rating aggregator, Analytics |
| `REVIEW_HIDDEN` | `report_count` đạt ngưỡng | `review_id` | Rating aggregator, Notification, Analytics |
| `REVIEW_APPROVED` | Admin bác bỏ báo cáo, đánh giá hiện lại | `review_id` | Rating aggregator, Notification, Analytics |
| `REVIEW_REMOVED` | Gỡ theo yêu cầu pháp lý | `review_id` | Rating aggregator, Analytics |
| `ADMIN_REPLY_CREATED` | Phản hồi được **tạo mới** | `review_id` | Notification |
| `REVIEW_METRICS_UPDATED` | Aggregate thay đổi | `product_id` | Search |

### 5.1 `REVIEW_UPDATED` phải mang giá trị cũ

Đây là thay đổi so với thiết kế cũ và là điều kiện để aggregate đúng.

Payload bắt buộc có `old_rating`, `new_rating`, `old_has_media`, `new_has_media`
ngoài `review_id` và `product_id`. Rating aggregator cần cả hai giá trị để trừ ở
bucket cũ và cộng ở bucket mới trong một câu lệnh.

Thiết kế cũ chỉ mang `fields_changed` và không đưa `REVIEW_UPDATED` vào danh sách
event mà aggregator xử lý. Hệ quả: người dùng sửa từ 5 sao xuống 1 sao thì điểm
trung bình của sản phẩm không đổi, vĩnh viễn. Xem
[Domain, mục 9](./domain.md#9-business-examples).

Event này còn được phát khi tác giả bị ẩn danh, với `old_rating = new_rating`. Khi
đó aggregate không đổi; consumer content/analytics có thể cập nhật snapshot tác giả.
Search hiện chỉ consume `REVIEW_METRICS_UPDATED` và không lưu tên tác giả.

### 5.2 `REVIEW_METRICS_UPDATED`

Contract canonical: [`com.ecommerce.review.metrics-updated.v1`](../../contracts/events/review/v1/asyncapi.yaml).

Event mang **toàn bộ** aggregate hiện tại chứ không phải delta: `product_id`,
`average_rating`, `total_reviews`, `total_with_media`, `review_revision`,
`observed_at`. Nhờ vậy consumer bỏ lỡ một event vẫn hội tụ được khi nhận event kế
tiếp.

**Quy tắc bắt buộc cho consumer:**

- Áp dụng khi và chỉ khi `event.review_revision > document.review_revision`. Đây
  không phải tối ưu hóa mà là điều kiện đúng đắn: snapshot export và event replay
  chạy song song trong lúc Search reindex.
- `observed_at` chỉ dùng cho quan sát và gỡ lỗi. Nó **không** phải khóa thứ tự — hai
  mutation có thể trùng timestamp và đồng hồ không phải nguồn thứ tự.
- `partition_key = product_id` giữ thứ tự trong một partition nhưng không thay thế
  guard.

Xem [ADR-004](./decisions/ADR-004-review-revision-ordering-guard.md) và
[Search ADR-016](../search/decisions/ADR-016-review-rating-snapshot-reindex.md).

### 5.3 Envelope

Envelope CloudEvents được Debezium dựng từ các cột metadata của outbox
(`event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `correlation_id`,
`created_at`), còn `payload` chứa dữ liệu nghiệp vụ.

Tài liệu Review cũ mô tả một envelope với các trường viết thường
(`event_id`, `event_type`, `source`…) khác với `application/cloudevents+json` mà
[AsyncAPI v1](../../contracts/events/review/v1/asyncapi.yaml) quy định. Tài liệu này
lấy contract làm chuẩn. Envelope cũ chỉ tồn tại nếu có giai đoạn dual-publish, và
giai đoạn đó chưa được lên kế hoạch — xem [README, mục 9](./README.md#9-known-gaps).

## 6. Event tiêu thụ

### 6.1 `ORDER_COMPLETED`

**Topic:** `t.order.events` · **Consumer group:** `review-order-sync` ·
**Producer:** Order Service

Review chỉ dùng một tập con của payload: `order_id`, `user_id`, `status`,
`completed_at`, và với mỗi item là `product_id`, `sku_id`, `variant_name`.

- **Hành động:** upsert một eligibility cho mỗi item, với
  `expires_at = completed_at + review_window_days`.
- **Idempotency:** `uq_eligibility_business_key` với `ON CONFLICT DO UPDATE` chỉ
  chạm các dòng còn ở `PENDING`. Nhận lại cùng một đơn không làm mất trạng thái của
  eligibility đã được dùng.
- **Ordering:** không yêu cầu. Mỗi đơn độc lập với đơn khác.
- **Trường thiếu:** `variant_name` có thể null — ghi log cảnh báo nhưng vẫn xử lý,
  vì nó chỉ phục vụ hiển thị. Thiếu `product_id` hoặc `sku_id` là lỗi contract:
  không xử lý, đưa vào dead letter.
- **Trường lạ:** bỏ qua. Review không được vỡ khi Order thêm trường mới.
- **Retry:** tối đa 10 lần, backoff 1s → 5m, sau đó vào dead letter.

### 6.2 `USER_DELETED`

**Topic:** `t.auth.events` · **Consumer group:** `review-privacy-sync` ·
**Producer:** Auth Service

Auth là nguồn chân lý cho việc xóa tài khoản và phát event này trực tiếp. Review ẩn
danh dữ liệu do chính mình sở hữu, độc lập với tiến độ dọn dẹp của User Service.

- **Payload dùng đến:** `user_id`, `deleted_at`, `reason`
  (`GDPR_REQUEST` hoặc `ADMIN_DELETION`), `hard_delete_after`.
- **Hành động:** đặt `user_name` thành giá trị ẩn danh và `is_anonymized = true`
  cho mọi đánh giá của người này; ghi log compliance; phát `REVIEW_UPDATED` cho từng
  đánh giá bị chạm để downstream cập nhật.
- **Idempotency:** thao tác đặt giá trị cố định nên idempotent theo bản chất.
- **Không làm:** không xóa đánh giá, không đổi điểm, không đụng vào aggregate. Xem
  [Domain, mục 9](./domain.md#9-business-examples) để hiểu vì sao.

### 6.3 `t.review.events` (self-consume)

**Consumer group:** `review-rating-aggregator`

Review đọc lại chính topic của mình để cập nhật aggregate.

- **Event xử lý:** `REVIEW_CREATED`, `REVIEW_UPDATED`, `REVIEW_APPROVED`,
  `REVIEW_HIDDEN`, `REVIEW_REMOVED`.
- **Event bỏ qua:** `ADMIN_REPLY_CREATED`, `REVIEW_METRICS_UPDATED`. Bỏ qua
  `REVIEW_METRICS_UPDATED` là bắt buộc — nếu xử lý, consumer sẽ tự kích hoạt chính
  mình thành vòng lặp vô hạn.
- **Ordering:** bắt buộc trong phạm vi một `product_id`, đã được bảo đảm bởi
  partition key.
- **Idempotency:** **không có.** Xem [Data, mục 8](./data.md#8-outbox-và-idempotency).

## 7. Compatibility

**Backward compatibility cho event.** Thêm trường tùy chọn là thay đổi tương thích.
Xóa trường, đổi kiểu, đổi ngữ nghĩa của trường đang tồn tại, hoặc đổi `partition_key`
là breaking change và cần version mới của message.

**Đổi `partition_key` là breaking change nặng nhất.** `product_id` không chỉ là cách
phân tải; nó là điều kiện để mọi thay đổi của một sản phẩm được xử lý theo thứ tự.
Đổi nó sẽ làm hỏng aggregate và mọi projection downstream, kể cả khi payload không đổi.

**Versioning.** Event dùng version trong tên type
(`com.ecommerce.review.metrics-updated.v1`). Version mới chạy song song với version
cũ trên cùng topic cho tới khi mọi consumer đã chuyển.

**Deprecation.** Contract cũ được hỗ trợ tối thiểu 90 ngày sau khi version mới được
công bố, và chỉ gỡ khi mọi consumer đã xác nhận chuyển đổi.

**Với HTTP.** Thêm trường vào response là tương thích; client phải bỏ qua trường lạ.
Thêm bộ lọc hoặc tham số tùy chọn là tương thích. Đổi ngữ nghĩa của một mã lỗi là
breaking change.

**Chứng minh trạng thái `verified`.** Một interaction chỉ được đánh dấu `verified`
khi có: specification machine-readable, contract test phía provider chạy trong CI,
và ít nhất một consumer test chứng minh consumer xử lý đúng cả trường hợp event cũ
và event trùng. Với `REVIEW_METRICS_UPDATED`, contract test phải chứng minh snapshot
export và event mang **cùng một** `review_revision` cho cùng một trạng thái — đây là
điều kiện Search dựa vào để reindex mà không mất rating.
