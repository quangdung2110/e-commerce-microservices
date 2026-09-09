---
doc_id: user-overview
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence: []
verified_against_commit: null
---

# User Service — Tổng quan dịch vụ

> Điểm bắt đầu để hiểu thiết kế mục tiêu của User Service. Tài liệu được tái cấu
> trúc từ thiết kế cũ và không xác minh implementation hiện hữu.

## 1. Trạng thái dịch vụ

User Service là bounded context sở hữu hồ sơ khách hàng sau khi account đã được
xác minh bởi Auth Service. Thiết kế đang ở mức `proposed`, phạm vi `target`.
Runtime, source path, contract machine-readable và bằng chứng test sẽ chỉ được
ghi khi được xác minh riêng.

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

- Bootstrap profile và preferences mặc định sau identity verification.
- Đọc và cập nhật profile của chính chủ.
- Tạo, đọc, sửa, đặt mặc định và xóa địa chỉ của chính chủ.
- Quản lý language, currency và notification preferences.
- Phát event thay đổi profile/preferences cho projection downstream.
- Anonymize dữ liệu khi account bị xóa, rồi hard delete sau retention window.

## 6. Phụ thuộc

| Hướng | Dịch vụ/Hệ thống | Giao thức | Mục đích | Bắt buộc | Contract |
|---|---|---|---|---|---|
| Đầu vào | Client/API Gateway | HTTP | Quản lý profile, address, preferences | Có | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) |
| Đầu ra | Checkout Service | Internal HTTP/mTLS | Resolve address snapshot đã kiểm tra ownership | Có cho checkout | [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml) |
| Đầu vào | Auth Service | Kafka | Khởi tạo và tín hiệu xóa | Có | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) |
| Đầu ra | PostgreSQL | Database | Dữ liệu User sở hữu | Có | [Data](./data.md) |
| Đầu ra | Redis | Cache | Cache danh sách address | Tùy chọn khi đọc; cần cho tối ưu cache | [Data](./data.md) |
| Đầu ra | Kafka | Events | Thông báo thay đổi của User | Cần cho tích hợp bất đồng bộ | [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) |
| Đầu ra | Chat, Notification, API Gateway | Kafka consumer | Duy trì projection | Tùy theo consumer | [Interfaces](./interfaces.md) |
| Đầu vào | Auth Service | Kafka | Tín hiệu xóa account cho User; đồng thời Auth phát trực tiếp cho Chat, Notification, Review và API Gateway | Có | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) |

## 7. Bất biến quan trọng

- `USER-INV-001`: một address chỉ được owner của nó đọc hoặc thay đổi.
- `USER-INV-002`: mỗi profile có tối đa một địa chỉ `is_default = true`.
- `USER-INV-003`: profile bootstrap và preference bootstrap phải idempotent theo `user_id`.
- `USER-INV-004`: address snapshot gồm province, district và ward trong Order không bị thay đổi khi User xóa address.
- `USER-INV-005`: khi nhận `USER_DELETED` hợp lệ, PII do User sở hữu bị anonymize; User không republish sự kiện xóa account.
- `USER-INV-006`: `user_profiles.id` luôn bằng `user_id` Auth phát trong event bootstrap.

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
  [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) đang ở trạng
  thái `proposed`; migration vẫn chưa có nguồn chuẩn.
- Thiết kế cũ nêu direct Kafka publish sau commit, nhưng chưa chứng minh retry,
  reconciliation, DLQ hoặc observability; xem ADR-004.
- Cơ chế xác thực identity giữa Gateway và User API, SLO, runtime configuration,
  deployment topology và lệnh local chưa được xác minh.
- OpenAPI chưa được kiểm chứng với implementation; public API và internal Checkout
  endpoint chưa được đánh dấu `implemented` hoặc `verified`.
