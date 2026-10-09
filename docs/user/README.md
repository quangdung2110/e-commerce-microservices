---
doc_id: user-overview
service: user
scope: current
status: implemented
owners:
  - User Service team
evidence:
  - go/services/user-service/cmd/server/main.go
  - go/services/user-service/internal/api/router/internal_router.go
  - go/services/user-service/migrations/0001_initial_user_schema.up.sql
  - go/services/user-service/test
verified_against_commit: null
---

# User Service — Tổng quan dịch vụ

> Runtime hiện hữu được mô tả trong [development.md](./development.md) và README
> module. Các mục tiêu production/SLO chưa có bằng chứng vẫn là thiết kế.

## 1. Trạng thái dịch vụ

User Service là bounded context sở hữu hồ sơ khách hàng sau khi account đã được
xác minh bởi Auth Service. Runtime Go 1.25 nằm ở `go/services/user-service`, có public
API 8082, internal checkout API 8083, consumer cho Auth event (`t.auth.events`) và
Notification outcome (`t.notification.outcomes.v1`), cùng lifecycle workers. Root Compose
chạy migration có version (`0001`, `0002`) trước User. Contract chuẩn nằm ở
`contracts/http/user/v1` và `contracts/events/user/v1`; Stripe checkout E2E được ghi riêng.

## 2. Mục tiêu

Service quản lý dữ liệu hồ sơ, địa chỉ nhận hàng và tùy chọn ứng dụng của khách
hàng. Nó cho phép checkout đọc địa chỉ hiệu quả, đồng bộ tên hiển thị và tùy chọn
thông báo sang các service khác, đồng thời xử lý phần dữ liệu do mình sở hữu khi
account bị xóa.

## 3. Ranh giới trách nhiệm

User Service chịu trách nhiệm về profile, address book, preferences và lifecycle
xóa các dữ liệu đó. Auth Service sở hữu email, credential, role và trạng thái
identity; Order Service sở hữu snapshot địa chỉ trong đơn hàng. User không gửi
email, xác thực credential, quản lý token hay sửa dữ liệu của service khác.

Mỗi transaction chỉ bao gồm PostgreSQL của User. Cache invalidation và Kafka
event là side effect liên-service, không mở rộng transaction sang Redis, Kafka
hay database service khác.

## 4. Quyền sở hữu dữ liệu

| Nhóm dữ liệu | Nguồn chân lý | Quyền ghi |
|---|---|---|
| User profile, address, preferences | PostgreSQL schema `"user"` | Chỉ User Service |
| Bản ghi retention khi xóa account | PostgreSQL schema `"user"` | Chỉ User Service/worker của nó |
| Address-list cache | Redis, bản sao TTL | Chỉ User Service; không phải nguồn chân lý |
| Account identity/credential | Auth Service | User chỉ tham chiếu bằng `user_id` |

Không service nào được đọc hoặc ghi trực tiếp database User.

## 5. Năng lực cung cấp

- Bootstrap profile và preferences mặc định (marketing tắt) sau identity verification.
- Đọc và cập nhật profile của chính chủ.
- Tạo, đọc, sửa, đặt mặc định và xóa địa chỉ của chính chủ (tối đa 50 địa chỉ).
- Quản lý notification preferences (`language` và `currency` cố định `vi`/`VND`, chỉ đọc).
- Phát event thay đổi profile/preferences cho projection downstream.
- Tắt `marketing_enabled` ở nguồn khi Notification báo người dùng đánh dấu spam.
- Anonymize dữ liệu khi account bị xóa, rồi hard delete sau retention window.

## 6. Phụ thuộc

| Hướng | Dịch vụ/Hệ thống | Giao thức | Mục đích | Bắt buộc | Contract |
|---|---|---|---|---|---|
| Đầu vào | Client/API Gateway | HTTP | Quản lý profile, address, preferences | Có | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) |
| Đầu ra | Checkout Service | Internal HTTP (mTLS do mesh thực thi) | Resolve address snapshot đã kiểm tra ownership | Có cho checkout | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) |
| Đầu vào | Auth Service | Kafka | Khởi tạo và tín hiệu xóa | Có | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) |
| Đầu vào | Notification Service | Kafka | `complaint-received` để tắt marketing ở nguồn; các outcome khác bị bỏ qua | Không (thiếu thì marketing tắt ở Notification nhưng nguồn lệch) | [Notification AsyncAPI v1](../../contracts/events/notification/v1/asyncapi.yaml) |
| Đầu ra | PostgreSQL | Database | Dữ liệu User sở hữu | Có | [Data](./data.md) |
| Đầu ra | Redis | Cache | Cache danh sách address | Tùy chọn khi đọc; cần cho tối ưu cache | [Data](./data.md) |
| Đầu ra | Kafka | Events | Thông báo thay đổi của User | Cần cho tích hợp bất đồng bộ | [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) |
| Đầu ra | Chat, Notification | Kafka consumer | Duy trì projection | Tùy theo consumer | [Interfaces](./interfaces.md) |
| Đầu vào | Auth Service | Kafka | Tín hiệu xóa account cho User; đồng thời Auth phát trực tiếp cho Chat, Notification và Review | Có | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) |

## 7. Bất biến quan trọng

- `USER-INV-001`: một address chỉ được owner của nó đọc hoặc thay đổi.
- `USER-INV-002`: mỗi profile có tối đa một địa chỉ `is_default = true`.
- `USER-INV-003`: profile bootstrap và preference bootstrap phải idempotent theo `user_id`.
- `USER-INV-004`: address snapshot gồm province, district và ward trong Order không bị thay đổi khi User xóa address.
- `USER-INV-005`: khi nhận `USER_DELETED` hợp lệ, PII do User sở hữu bị anonymize và owner không đọc/ghi lại được row ẩn danh cho tới khi purge; User không republish sự kiện xóa account.
- `USER-INV-006`: `user_profiles.id` luôn bằng `user_id` Auth phát trong event bootstrap.
- `USER-INV-007`: marketing là opt-in; User và Notification cùng coi thiếu consent là `marketing_enabled=false`.

Quy tắc và domain error tương ứng nằm trong [Domain Model](./domain.md).

## 8. Bản đồ tài liệu

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Component và processing model | Thay đổi cấu trúc service |
| [Domain](./domain.md) | Rule, ownership và lifecycle | Thay đổi business behavior |
| [Interfaces](./interfaces.md) | HTTP và event semantics | Tích hợp service |
| [Workflows](./workflows.md) | Luồng end-to-end, lỗi và recovery | Sửa use case |
| [Data](./data.md) | Persistence, cache, retention | Sửa schema/dữ liệu |
| [Operations](./operations.md) | Vận hành và runbook | Deploy/xử lý sự cố |
| [Development](./development.md) | Local development và DoD | Phát triển/kiểm thử |
| [Decisions](./decisions/README.md) | Quyết định kiến trúc | Đánh giá trade-off |

## 9. Khoảng trống đã biết

- [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) và
  [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) vẫn mang
  `x-adoption-status: proposed` vì chưa có contract test tự động validate response/event
  với schema; không suy ra contract certification từ runtime implemented. Code và
  contract đã được đối chiếu thủ công từng route, status và mã lỗi. Migration chuẩn
  nằm ở `go/services/user-service/migrations`.
- Runtime có direct publish/retry/reconciliation/DLQ; unit/integration kiểm tra
  lifecycle, repository, HTTP và consumer. Broker E2E/observability cần bằng chứng riêng.
- Root local config/migration và listener đã có runbook; production SLO/capacity,
  metrics Prometheus và contract-schema test tự động chưa hoàn chỉnh.
- Checkout snapshot đã có implementation; full Stripe checkout E2E là gate riêng.
- Backfill marketing của migration `0002` chỉ tắt các row owner chưa từng chạm vào;
  user chỉ đổi cờ email/in-app trước đó vẫn giữ `marketing_enabled=true` mà không có
  bằng chứng đồng ý riêng (xem [ADR-009](./decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md)).
- Notification còn xác thực cổng internal bằng mTLS ở tầng ứng dụng, khác mô hình mesh mà User
  ([ADR-008](./decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md)) và Auth
  ([Auth ADR-007](../auth/decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md)) đã chuyển sang.
- Chưa có single-flight chống cache stampede; epoch chỉ chặn fill muộn, không chặn nhiều
  request cùng miss.
