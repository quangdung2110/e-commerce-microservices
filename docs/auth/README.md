---
doc_id: auth-overview
service: auth
scope: target
status: proposed
owners:
  - Auth Service team
evidence: []
verified_against_commit: null
---

# Auth Service — Service Overview

> Điểm bắt đầu để hiểu Auth Service. Tài liệu này mô tả thiết kế mục tiêu từ bộ
> tài liệu Auth hiện có; chưa được xác minh theo implementation.

## 1. Service status

Auth Service là Identity Provider của hệ thống. Thiết kế hiện ở trạng thái
`proposed` và phạm vi là `target`: các chi tiết runtime, contract
machine-readable và bằng chứng implementation chưa thuộc phạm vi tài liệu này.

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
rate limit, OTP/reset token); không thay thế PostgreSQL cho refresh token.
Chi tiết ở [Data Design](./data.md).

## 5. Capabilities

- Đăng ký và xác minh account.
- Xác thực bằng credential và chống brute-force.
- Cấp, refresh, rotate và thu hồi token/session.
- Đổi email có xác minh OTP, đổi hoặc reset mật khẩu; vô hiệu hóa phiên cũ khi chính sách yêu cầu.
- Cung cấp JWKS để Gateway xác minh JWT cục bộ.
- Quản lý trạng thái `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`, `DELETED`.
- Xóa account theo luồng GDPR: anonymize ngay và hard delete sau 90 ngày.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required | Contract |
|---|---|---|---|---|---|
| Inbound | Client/API Gateway | HTTP | Authentication, session và JWKS | Required | [Interfaces](./interfaces.md) |
| Outbound | PostgreSQL | Database | Credential, session, key material | Required | [Data](./data.md) |
| Outbound | Redis | Cache / PubSub | Revocation, rate limit, OTP và JWKS invalidation | Required for security controls | [Data](./data.md) |
| Outbound | Kafka | Events | Thông báo thay đổi identity | Required for asynchronous integrations | [Interfaces](./interfaces.md) |
| Outbound | User Service | Kafka consumer | Khởi tạo/dọn profile từ identity event | Optional by workflow | [Interfaces](./interfaces.md) |
| Outbound | Notification Service | Kafka consumer | Gửi OTP và security notification | Optional by workflow | [Interfaces](./interfaces.md) |

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
| [Interfaces](./interfaces.md) | API, JWKS và event semantics | Tích hợp service |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Sửa một use case |
| [Data](./data.md) | Persistence, cache và lifecycle | Sửa schema/dữ liệu |
| [Operations](./operations.md) | Vận hành, worker và runbook | Deploy/xử lý sự cố |
| [Development](./development.md) | Local development và Definition of Done | Phát triển/kiểm thử |
| [Decisions](./decisions/README.md) | Quyết định kiến trúc | Đánh giá trade-off |

## 9. Known gaps

- HTTP public, internal delivery API và Kafka event đã có contract machine-readable
  trong [`contracts/`](../../contracts/); việc phù hợp với implementation chưa được xác minh.
- Event được phát trực tiếp sau commit, không dùng outbox; retry, reconciliation
  và observability của direct publisher cần được xác minh trước khi production.
- SLO, runbook, runtime configuration và hướng dẫn chạy local cần được xác minh
  bằng implementation hoặc hạ tầng trước khi chuyển trạng thái `verified`.
