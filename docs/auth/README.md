---
doc_id: auth-overview
service: auth
scope: current
status: implemented
owners:
  - Auth Service team
evidence:
  - go/services/auth-service/cmd/server/main.go
  - go/services/auth-service/internal/api/router/router.go
  - go/services/auth-service/internal/api/router/internal_router.go
  - go/services/auth-service/migrations/0001_initial_auth_schema.up.sql
  - go/services/auth-service/test
verified_against_commit: null
---

# Auth Service — Service Overview

> Điểm bắt đầu để hiểu Auth Service. Runtime hiện hữu được mô tả trong
> [development.md](./development.md) và README của module. Mục tiêu production
> (SLO, metric, runbook) chưa có bằng chứng đo nên vẫn là thiết kế.

## 1. Service status

Auth Service là Identity Provider của hệ thống. Runtime Go nằm ở
`go/services/auth-service`: listener public (cổng 8080; root Compose publish ra 8086)
phục vụ đăng ký, đăng nhập, session, mật khẩu, đổi email, xóa account và JWKS;
listener internal riêng (cổng 8081, không bao giờ qua Gateway) phục vụ Notification
Service; worker nền chạy cleanup, mở khóa, xoay/retire khóa ký và hard delete.
Contract machine-readable nằm ở `contracts/http/auth/v1`,
`contracts/http/auth/internal/v1` và `contracts/events/auth/v1`; schema PostgreSQL
`docs/auth/schema/0001_initial_auth_schema.sql` giống từng byte với migration.

## 2. Mục tiêu

Service cung cấp định danh, xác thực và vòng đời phiên đăng nhập an toàn. Kết
quả chính là cấp thông tin xác thực để Gateway bảo vệ API, đồng thời cho phép
thu hồi phiên/token khi có logout, đổi mật khẩu hoặc thay đổi trạng thái account.

## 3. Boundary

Auth Service chịu trách nhiệm về credentials, role, trạng thái account phục vụ
xác thực, access token, refresh token/session và khóa ký JWT/JWKS. Service không
sở hữu profile, địa chỉ hay dữ liệu nghiệp vụ của người dùng; những dữ liệu đó thuộc
User Service hoặc service chuyên trách tương ứng. Không service nào được ghi
trực tiếp vào database Auth.

Transaction boundary của Auth chỉ bao gồm dữ liệu do Auth sở hữu. Việc tạo profile
hoặc gửi thông báo là side effect liên-service và được thực hiện qua contract.

## 4. Data ownership

Auth là system of record cho account credential, role, refresh-token family và JWT
signing key. Redis chỉ lưu trạng thái bảo mật có TTL (denylist, mốc revoke,
rate limit, OTP/reset token, trạng thái giao thông báo); không thay thế PostgreSQL
cho refresh token. Chi tiết ở [Data Design](./data.md).

## 5. Capabilities

- Đăng ký và xác minh account.
- Xác thực bằng credential và chống brute-force.
- Cấp, refresh, rotate và thu hồi token/session.
- Đổi email có xác minh OTP, đổi hoặc reset mật khẩu; vô hiệu hóa phiên cũ khi chính sách yêu cầu.
- Cung cấp JWKS để Gateway xác minh JWT cục bộ.
- Quản lý trạng thái `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`, `DELETED`.
- Xóa account theo luồng GDPR: anonymize ngay và hard delete sau 90 ngày.
- Cho Notification Service resolve nội dung email (OTP, reset URL) và email người nhận
  theo `user_id` qua listener internal.

## 6. Dependencies

Cột Direction tính theo Auth: *Inbound* là lời gọi đi vào Auth, *Outbound* là Auth ghi
hoặc phát đi.

| Direction | Service/System | Protocol | Purpose | Required | Contract |
|---|---|---|---|---|---|
| Inbound | Client qua API Gateway | HTTP (public) | Authentication, session | Required | [Interfaces](./interfaces.md) |
| Inbound | `gateway-auth` / API Gateway | HTTP | Đọc JWKS để verify JWT cục bộ | Required | [Interfaces](./interfaces.md) |
| Inbound | Notification Service | HTTP (internal, mesh) | Resolve nội dung email và `notification-contact` | Required cho email | [Internal OpenAPI](../../contracts/http/auth/internal/v1/openapi.yaml) |
| Outbound | PostgreSQL | Database | Credential, session, key material | Required | [Data](./data.md) |
| Outbound | Redis | Key-value / Pub-Sub | Revocation, rate limit, OTP/reset, delivery state, worker lock, JWKS invalidation | Required (fail closed) | [Data](./data.md) |
| Outbound | Kafka | Events | Phát identity event và yêu cầu thông báo; Auth không consume event nào | Required cho tích hợp bất đồng bộ | [AsyncAPI](../../contracts/events/auth/v1/asyncapi.yaml) |
| Outbound (consumer của `USER_VERIFIED_SUCCESS`, `USER_DELETED`) | User Service | Kafka | Khởi tạo profile; dọn profile/address khi account bị xóa | Optional by workflow | [Interfaces](./interfaces.md) |
| Outbound (consumer của `USER_DELETED`) | Notification, Review, Chat, Cart Service | Kafka | Tự cleanup/anonymize dữ liệu sở hữu khi account bị xóa (Cart xóa giỏ hàng) | Optional by workflow | [Interfaces](./interfaces.md) |
| Outbound (consumer của security notification) | Notification Service | Kafka | Gửi OTP và security notification | Optional by workflow | [Interfaces](./interfaces.md) |

## 7. Critical invariants

- `AUTH-INV-001`: chỉ account `ACTIVE` được cấp token.
- `AUTH-INV-002`: refresh token bị revoke hoặc hết hạn không được dùng lại.
- `AUTH-INV-003`: private JWT key không được công bố qua JWKS hoặc log.
- `AUTH-INV-004`: access token bị revoke phải bị Gateway từ chối theo denylist
  hoặc mốc `revoked_before` trong thời hạn hiệu lực của token.

Các quy tắc và cơ chế enforcement được mô tả tại [Domain Model](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Component và processing model | Thay đổi cấu trúc service |
| [Domain](./domain.md) | State, rule và error nghiệp vụ | Thay đổi business behavior |
| [Interfaces](./interfaces.md) | API, JWKS, event semantics và error catalog | Tích hợp service |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Sửa một use case |
| [Data](./data.md) | Persistence, cache và lifecycle | Sửa schema/dữ liệu |
| [Operations](./operations.md) | Cấu hình, worker và runbook | Deploy/xử lý sự cố |
| [Development](./development.md) | Chạy local, test và Definition of Done | Phát triển/kiểm thử |
| [Decisions](./decisions/README.md) | Quyết định kiến trúc | Đánh giá trade-off |

## 9. Known gaps

- Contract `proposed` → `implemented` còn thiếu test tự động validate response với
  OpenAPI và consumer test từ Gateway/Notification thật; xem
  [ADOPTION](../../contracts/ADOPTION.md).
- Event được phát trực tiếp sau commit, không dùng outbox ([ADR-005](./decisions/ADR-005-event-publication-reliability.md),
  [ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)): retry cạn chỉ ghi log
  `error`. Chưa có metric, alert hay quy trình reconciliation; đây là việc phải đóng trước production.
- Service chưa export metric Prometheus; SLO, alert threshold và runbook trong
  [operations.md](./operations.md) vẫn là thiết kế.
- Redis là điểm lỗi chung của login, refresh, đăng ký, reset và thu hồi (mọi adapter fail closed,
  lỗi trả `500 DEPENDENCY_UNAVAILABLE` vì contract public chưa định nghĩa `503`).
- Listener internal chỉ kiểm allowlist `X-Service-Identity`; admission thật do mesh thực thi
  ([ADR-007](./decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md)). Docker Compose không có
  mesh nên header không được xác thực ở đó.
