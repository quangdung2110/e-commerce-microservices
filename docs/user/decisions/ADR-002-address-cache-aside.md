---
doc_id: user-adr-002
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/data.md
  - docs/user/workflows.md
---

# ADR-002 — Cache-aside chỉ cho danh sách address

> Cập nhật: phần cửa sổ stale sau invalidation được thay thế bởi
> [ADR-011](./ADR-011-address-cache-invalidation-epoch.md) (epoch chống refill muộn).

## Bối cảnh

Checkout đọc danh sách address thường xuyên. Profile và preferences không được giả
định có cùng read pattern. PostgreSQL phải luôn là source of truth.

## Quyết định

Chỉ cache `user:caches:addresses:{user_id}` trong Redis theo cache-aside. TTL list
thông thường là 12 giờ; list rỗng có TTL ngắn. Mọi address mutation commit vào
PostgreSQL trước rồi mới invalidate key. Profile và preferences bypass cache.

## Các phương án đã cân nhắc

- Cache mọi User resource: có thể giảm read nhưng tăng invalidation, stale data và
  rủi ro quyền riêng tư mà chưa có lợi ích được đo lường.
- Cache Redis write-through/source-of-truth: khiến availability và correctness phụ
  thuộc Redis, đồng thời làm transaction recovery phức tạp.
- Không cache: mô hình correctness đơn giản nhất nhưng để checkout address read
  load trên DB.

## Hệ quả

Redis failure làm read degrade sang PostgreSQL và mutation vẫn thành công. Có stale
window nếu post-commit invalidation thất bại; stampede protection chưa được chọn.
Cần có cache metrics, invalidation retry/reconciliation và redaction an toàn cho
PII được cache.

## Ảnh hưởng migration

Cần key namespace compatibility, serialization versioning và TTL được tài liệu hóa.
Đổi key/schema phải hỗ trợ value cũ đến khi toàn bộ reader tương thích.

## Xác minh

Thêm cache hit/miss/fallback/invalidation test, Redis outage test và load data để
chứng minh cache trước khi đánh dấu ADR này là verified.
