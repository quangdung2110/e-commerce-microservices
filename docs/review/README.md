---
doc_id: review-overview
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

# Review Service — Service Overview

> Điểm bắt đầu để hiểu Review Service. Tài liệu này mô tả thiết kế mục tiêu,
> được refactor từ bộ tài liệu Review cũ; chưa được xác minh theo implementation.

## 1. Service status

| Khía cạnh | Trạng thái |
|---|---|
| Service | `planned` — chưa có implementation trong repository |
| Runtime / ngôn ngữ | Chưa chốt (`contracts/catalog/services.yaml` ghi `runtime: undecided`) |
| Đường dẫn implementation | Chưa có |
| Tài liệu | `proposed`, `scope: target` |
| Contract adoption | `not_started` — internal OpenAPI phục vụ BFF/Search và AsyncAPI rating cho Search đã ở mức `proposed`, nhưng chưa có implementation nào adopt; contract public HTTP và event nghiệp vụ chưa được viết |

Trạng thái triển khai của service và độ tin cậy của tài liệu được tách riêng: tài
liệu mô tả thiết kế đích và chưa có evidence từ code.

## 2. Mục tiêu

Review Service quản lý vòng đời đánh giá sản phẩm của người mua, từ lúc quyền
đánh giá được cấp sau khi đơn hàng hoàn tất cho đến khi đánh giá được hiển thị,
chỉnh sửa, ẩn hoặc gỡ bỏ.

Kết quả nghiệp vụ:

- Người mua đã nhận hàng có thể để lại đánh giá đúng một lần cho mỗi SKU đã mua,
  và sửa lại đúng một lần nếu đổi ý.
- Trang sản phẩm có danh sách đánh giá và số liệu tổng hợp đáng tin cậy.
- Search, Notification và Analytics nhận được số liệu rating và sự kiện
  đánh giá mà không cần truy vấn trực tiếp dữ liệu của Review.
- Nội dung vi phạm bị ẩn tự động khi vượt ngưỡng báo cáo, và bị gỡ khi có yêu cầu
  pháp lý.

## 3. Boundary

**Review Service chịu trách nhiệm:**

- Quyền đánh giá (eligibility) theo bộ khóa `(order_id, product_id, sku_id, user_id)`.
- Nội dung đánh giá: điểm, văn bản, media metadata.
- Báo cáo vi phạm và quyết định ẩn theo ngưỡng.
- Phản hồi của quản trị viên cho một đánh giá.
- Số liệu rating tổng hợp theo `product_id`, gồm cả revision đơn điệu phục vụ
  downstream.

**Review Service không chịu trách nhiệm:**

- Trạng thái đơn hàng và điều kiện "đã hoàn tất" — thuộc Order Service. Review chỉ
  phản ứng với `ORDER_COMPLETED`.
- Danh mục, giá, tên sản phẩm và tên biến thể — thuộc Product Service. Review chỉ
  giữ `product_id`, `sku_id` và snapshot `variant_name` phục vụ hiển thị.
- Định danh và hồ sơ người dùng — thuộc Auth và User Service. Review chỉ giữ
  snapshot `user_name` tại thời điểm tạo đánh giá.
- Hiển thị rating trong kết quả tìm kiếm — thuộc Search Service. Review cấp dữ
  liệu, Search sở hữu projection của mình.
- Ghép rating/review preview vào Product Detail response — thuộc Product Detail BFF.
  Review chỉ cấp internal read; BFF không sở hữu Review domain.
- Gửi thông báo cho người dùng — thuộc Notification Service.
- Lưu trữ file media — thuộc object storage; Review chỉ lưu metadata và URL.

Transaction boundary của Review chỉ bao gồm dữ liệu Review sở hữu. Mọi tác động
sang service khác là side effect bất đồng bộ qua event.

## 4. Data ownership

Review là system of record cho eligibility, review, media metadata, report, admin
reply và rating aggregate. Chi tiết ở [Data Design](./data.md).

| Kho lưu trữ | Dữ liệu | Nguồn chân lý |
|---|---|---|
| PostgreSQL `review` | Eligibility, review, media, report, admin reply, aggregate, outbox | Review Service |
| Kafka `t.review.events` | Event nghiệp vụ và snapshot rating | Không phải database nguồn |
| Object storage | File ảnh/video của đánh giá | Storage; Review sở hữu metadata |

Dữ liệu chỉ được snapshot từ service khác: `variant_name` (Product), `user_name`
(Auth/User). Hai trường này **không** được đồng bộ lại khi nguồn thay đổi; xem
[ADR-007](./decisions/ADR-007-author-name-snapshot.md).

Không service nào được đọc hoặc ghi trực tiếp vào database của Review. Không có
cache trung gian; xem [ADR-002](./decisions/ADR-002-postgresql-only-read-path.md).

## 5. Capabilities

- Cấp quyền đánh giá theo từng item đã mua khi đơn hàng hoàn tất.
- Liệt kê các item đang chờ đánh giá của một người dùng.
- Nhận đánh giá mới kèm điểm, nội dung và media.
- Cho phép sửa đánh giá đúng một lần.
- Trả danh sách đánh giá theo sản phẩm với lọc theo SKU, theo điểm và theo có media.
- Trả số liệu rating tổng hợp theo sản phẩm.
- Nhận báo cáo vi phạm và tự động ẩn đánh giá khi vượt ngưỡng.
- Cho phép quản trị viên phản hồi một đánh giá và bác bỏ báo cáo sai.
- Phát sự kiện đánh giá và snapshot rating cho downstream.
- Export snapshot rating aggregate phục vụ full reindex của Search.
- Ẩn danh tác giả khi tài khoản bị xóa; gỡ nội dung khi có yêu cầu pháp lý.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Client / API Gateway | HTTP | Đọc và ghi đánh giá | Required | [Interfaces](./interfaces.md) |
| Inbound | Product Detail BFF | HTTP nội bộ | Aggregate và preview hữu hạn cho PDP | Required cho Review enrichment | [Interfaces](./interfaces.md) |
| Inbound | Search Service | HTTP nội bộ | Export rating aggregate snapshot | Required cho full reindex của Search | [Review internal OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) |
| Inbound | Order Service | Kafka | `ORDER_COMPLETED` để cấp eligibility | Required | [Interfaces](./interfaces.md) |
| Inbound | Auth Service | Kafka | `USER_DELETED` để ẩn danh tác giả | Required cho compliance | [Interfaces](./interfaces.md) |
| Outbound | PostgreSQL | Database | Toàn bộ dữ liệu nghiệp vụ và outbox | Required | [Data](./data.md) |
| Outbound | Debezium / Kafka Connect | CDC | Publish outbox sang Kafka | Required | [ADR-003](./decisions/ADR-003-outbox-cdc-publication.md) |
| Outbound | Kafka | Events | Phát event nghiệp vụ và rating snapshot | Required | [AsyncAPI Review v1](../../contracts/events/review/v1/asyncapi.yaml) |
| Outbound | Object storage | HTTP | Presigned upload cho media của đánh giá | Required cho đánh giá có media | [Workflows](./workflows.md) |
| Outbound | Search Service | Kafka consumer | Chiếu rating vào index sản phẩm | Optional by workflow | [Interfaces](./interfaces.md) |
| Outbound | Notification Service | Kafka consumer | Thông báo cho tác giả đánh giá | Optional by workflow | [Interfaces](./interfaces.md) |
| Outbound | Analytics Service | Kafka consumer | Báo cáo và phân tích | Optional by workflow | [Interfaces](./interfaces.md) |

## 7. Critical invariants

- `REVIEW-INV-001`: mỗi bộ `(order_id, product_id, sku_id, user_id)` có tối đa một eligibility.
- `REVIEW-INV-002`: mỗi eligibility sinh tối đa một review.
- `REVIEW-INV-003`: `attempt_count` chỉ tăng, nằm trong `[0, 2]`, và không bao giờ giảm.
- `REVIEW-INV-004`: một review chỉ tồn tại khi eligibility của nó đã rời trạng thái `PENDING`.
- `REVIEW-INV-005`: `total_reviews` bằng tổng các bucket sao, và `average_rating` được derive từ bucket, không được ghi bằng giá trị do consumer tự tính.
- `REVIEW-INV-006`: mọi thay đổi aggregate phải tăng `review_revision` trong cùng transaction với outbox event mang giá trị mới.
- `REVIEW-INV-007`: mọi event rời khỏi service đều đi qua bảng outbox; không có đường publish trực tiếp.

Giải thích và nơi enforcement ở [Domain Model](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Component, processing và concurrency model | Thay đổi cấu trúc service |
| [Domain](./domain.md) | State machine, business rule và domain error | Thay đổi business behavior |
| [Interfaces](./interfaces.md) | HTTP API và event semantics | Tích hợp với service |
| [Workflows](./workflows.md) | 11 luồng end-to-end kèm failure path | Sửa một use case |
| [Data](./data.md) | Ownership, schema, index và lifecycle | Sửa schema hoặc dữ liệu |
| [Operations](./operations.md) | SLO, worker, alert và runbook | Deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy và kiểm thử local | Phát triển service |
| [Decisions](./decisions/README.md) | Architecture Decision Records | Đánh giá trade-off |

## 9. Known gaps

- Chưa có implementation; runtime, framework và cấu trúc thư mục code chưa được chọn.
- Contract machine-readable mới phủ internal read cho BFF/Search
  ([internal OpenAPI](../../contracts/http/review/internal/v1/openapi.yaml)) và rating
  event cho Search ([AsyncAPI](../../contracts/events/review/v1/asyncapi.yaml)).
  Public HTTP API và năm event nghiệp vụ còn lại chưa có specification.
- Envelope CloudEvents trong tài liệu cũ khác với
  `com.ecommerce.review.metrics-updated.v1`. Tài liệu này mô tả contract vNext;
  giai đoạn dual-publish chưa được lên kế hoạch chi tiết.
- Report Service chưa tồn tại trong hệ thống. Review tạm sở hữu toàn bộ luồng báo
  cáo; xem [ADR-009](./decisions/ADR-009-review-owns-report-flow.md).
- SLO, ngưỡng alert, cấu hình runtime và capacity assumption chưa được xác minh
  bằng đo đạc; toàn bộ là giá trị đề xuất.
- Luồng media giả định object storage hỗ trợ presigned upload; nhà cung cấp cụ thể
  và policy quét nội dung chưa được chốt.
