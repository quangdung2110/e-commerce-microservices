---
doc_id: payment-adr-006
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/architecture.md
  - docs/payment/data.md
  - java/services/payment-service/src/main/resources/db/migration/V1__payment_baseline.sql
---

# ADR-006 — PostgreSQL optimistic lock, lease và fencing cho concurrency

## Trạng thái

`Accepted`, được hiệu chỉnh theo implementation Gate 3 ngày 2026-09-25. Ngày quyết
định gốc không được ghi lại. Redis lock trong bản thiết kế đầu đã bị loại khỏi
runtime vì durable DB lease/fencing giải quyết đầy đủ quyền thực thi và recovery.

## Bối cảnh

Payment cần xử lý race condition và idempotency mạnh: webhook Stripe có thể ghi
đè nhau, frontend và webhook có thể tác động cùng transaction, cronjob nhiều
instance có thể quét trùng, và lệnh gọi Stripe là side effect ngoài không rollback
được qua DB. Cần một cơ chế vừa cấp quyền gọi provider, vừa từ chối stale completion
sau crash/takeover mà không giữ DB transaction mở trong lúc gọi mạng.

## Quyết định

- **Optimistic locking/CAS** trên `version` là guard cho mọi state transition.
- HTTP command claim row `payment_idempotency` bằng execution lease và tăng fencing
  token. Cùng fingerprint replay response; khác fingerprint bị reject.
- Mọi side effect Stripe có một `provider_operations` row commit ở TX1 với stable
  provider idempotency key, lease và fencing token. Provider call nằm ngoài DB
  transaction; TX2 chỉ bind kết quả nếu token vẫn hiện hành.
- Worker claim inbox/operation bằng row lock ngắn, lease có hạn và fencing. Crash
  cho phép takeover; stale worker không thể commit.
- Unique constraint bảo vệ attempt/refund/provider key; reconciliation singleton
  dùng partial unique index, retention singleton dùng PostgreSQL advisory lock.
- Redis không phải dependency Gate 3. Namespace trong `data.md §6` chỉ là dự trù
  accelerator tương lai và không được thay đổi quyền thực thi hoặc kết quả.

## Phương án đã cân nhắc

- **Redis lock cho transition/side effect:** thêm một failure domain, không tạo
  durable recovery record và không chặn stale completion sau TTL. Không chọn.
- **Chỉ optimistic lock:** không đủ cho provider call ngoài transaction. Không chọn.
- **Pessimistic row lock:** giữ lock lâu trong khi gọi Stripe. Không chọn.
- **Durable operation + lease/fencing:** DB transaction ngắn, provider call ngoài
  transaction, recovery có bằng chứng. Được chọn.

## Hệ quả

Tích cực: một durable authority, crash/takeover quan sát được, stale completion bị
fence, không có Redis readiness/degradation mode. Tiêu cực: claim/bind phải chia
TX1/TX2; mọi adapter phải giữ đúng token và provider key; conflict cần durable retry.

## Ảnh hưởng migration

Có cột `version`, `payment_idempotency` PK `(idempotency_key, action)`, lease/fencing
fields trong idempotency/inbox/provider operation, và các unique/partial indexes ở
`V1__payment_baseline.sql`. Không cần Redisson client.

## Xác minh

ArchUnit/domain tests và PostgreSQL integration tests kiểm concurrent claim, crash
takeover, stale fencing reject, duplicate provider key, webhook retry và singleton
workers. `mvn verify` là bằng chứng executable.
