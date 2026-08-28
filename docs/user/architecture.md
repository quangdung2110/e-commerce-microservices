---
doc_id: user-architecture
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence: []
verified_against_commit: null
---

# User Service — Kiến trúc

> File này mô tả cấu trúc mục tiêu, không phải sơ đồ implementation đã xác minh.

## 1. Bối cảnh kiến trúc

User nằm sau API Gateway đối với request đồng bộ. Auth là upstream bất đồng bộ
cho bootstrap và xóa account. PostgreSQL là system of record; Redis chỉ tăng tốc
đọc danh sách address. Chat và Notification nhận event User để tự duy trì
projection; User, Chat, Notification và Review nhận tín hiệu xóa trực tiếp từ
Auth. Checkout gọi internal HTTP/mTLS để
resolve address snapshot sau khi user chọn address, thay vì đọc database User.

```mermaid
flowchart LR
  C[Client] --> G[API Gateway]
  G --> H[User HTTP adapter]
  CO[Checkout Service] --> H
  H --> A[Application and domain layer]
  A --> P[(User PostgreSQL)]
  A --> R[(Redis address cache)]
  A --> K[Kafka producer]
  K --> CH[Chat Service]
  K --> N[Notification Service]
  AU[Auth Service] --> KA[Kafka: auth events]
  KA --> EC[Auth-event consumer]
  KA --> CH
  KA --> N
  KA --> RV[Review Service]
  EC --> A
  W[Compliance worker] --> A
```

## 2. Thành phần và trách nhiệm

| Thành phần | Trách nhiệm | Không được làm | Phụ thuộc |
|---|---|---|---|
| HTTP adapter | Nhận public request qua Gateway và Checkout internal request qua mTLS, ánh xạ error | Chứa rule nghiệp vụ hoặc tin `user_id` từ client | Application layer |
| Application/domain layer | Validate rule, ownership, transaction và điều phối side effect | Phụ thuộc HTTP/Kafka framework | Repository, cache, publisher |
| Profile/address/preferences repository | Persist data User sở hữu | Đọc/ghi database service khác | PostgreSQL |
| Address cache adapter | Đọc cache-aside và invalidation sau commit | Là nguồn chân lý cho address | Redis |
| Auth-event consumer | Nhận verification/deletion và gọi command idempotent | Thay đổi state ngoài application layer | Kafka, application layer |
| User-event producer | Phát profile/preferences event theo contract | Tự thay đổi transaction data hoặc republish account deletion | Kafka |
| Compliance worker | Tìm retention record đến hạn, hard delete an toàn | Hard delete trước deadline hoặc bỏ qua observability | Application layer |

## 3. Mô hình xử lý

Profile, address và preferences được xử lý synchronous, mỗi command ghi trong
một PostgreSQL transaction. Address cache bị invalidated sau commit; cache lỗi
không được rollback transaction. Read address dùng cache-aside và fallback DB.

Consumer xử lý `USER_VERIFIED_SUCCESS` và `USER_DELETED` bất đồng bộ, phải
idempotent theo business key/event identity. Compliance worker chạy bất đồng bộ
theo schedule. Thiết kế cũ phát User event trực tiếp sau commit, không outbox;
điều này tạo cửa sổ mất event nếu Kafka publish thất bại — xem ADR-004.

## 4. Tính nhất quán và đồng thời

Profile, preferences, ownership và default address cần strong consistency trong
PostgreSQL. Partial unique constraint cho default address là safety net; thao tác
set-default phải serialize các address của cùng user bằng lock hoặc cơ chế
tương đương được xác minh sau. Hai mutation cache liên tiếp có thể chỉ tạo nhiều
`DEL`, điều này an toàn.

Projection downstream, cache refresh và Kafka delivery là eventual consistency.
Address cache được phép stale từ lúc commit đến invalidation/read refill; khi
Redis unavailable, đọc trực tiếp DB và mutation vẫn hoàn tất. Consumer trùng
phải không tạo duplicate profile/retention record hay phát side effect vô hạn.

## 5. Mô hình triển khai

Mục tiêu gồm API server, Kafka consumer và compliance scheduler/worker; chúng có
thể cùng image nhưng cần scale độc lập về vận hành. API scale theo RPS/DB pool;
consumer theo partition và lag, đồng thời giữ ordering theo `user_id`; worker
cần distributed lock hoặc cơ chế claim row để không hard-delete cùng record hai
lần. PostgreSQL, Redis và Kafka là runtime dependency.

## 6. Ràng buộc kiến trúc

- Không truy cập trực tiếp database của Auth, Order, Chat, Notification hay Review.
- Không đặt PII không cần thiết trong Kafka event hoặc log.
- Không publish event trước khi transaction data commit.
- Cache không thay thế PostgreSQL và cache failure không làm mất address mutation đã commit.
- Consumer/worker phải idempotent, có retry/DLQ và observable trước khi production.
- Adapter không chứa business rule; domain/application layer không phụ thuộc transport.

## 7. Đánh đổi và quyết định

Các đánh đổi được quản lý tại [Decision index](./decisions/README.md): bootstrap
bằng Auth event, cache-aside cho address, invariant default address, GDPR retention
và reliability của direct event publishing. Tất cả vẫn là `proposed`.
