---
doc_id: auth-adr-003
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/domain/service/key_lifecycle_service.go
  - go/services/auth-service/internal/infrastructure/redis/jwks_notifier.go
  - go/services/auth-service/test/unit/service/key_lifecycle_test.go
---

# ADR-003 — Publish JWKS with key-rotation overlap

## Context

Gateway cần verify JWT cục bộ mà không nhận private key, đồng thời key phải được
rotate an toàn.

## Decision

Auth ký JWT bằng asymmetric key, công bố public keys qua JWKS, dùng trạng thái
`ACTIVE`, `PHASE_OUT`, `RETIRED` và giữ phase-out key đến hết maximum access-token
lifetime. `JWKS_UPDATED` là cache-invalidation signal.

## Alternatives considered

- Shared symmetric key: đơn giản nhưng mở rộng trust boundary.
- Gateway gọi Auth để verify: giảm phân phối key nhưng thêm latency/dependency.
- Retire ngay key cũ: dễ vận hành nhưng làm token hợp lệ thất bại.

## Consequences

Key lifecycle cần scheduler, lock, alert và Gateway fallback refresh khi gặp
unknown `kid`.

## Migration impact and verification

Đã implement: worker `key-rotation` (mặc định 30 ngày) và `key-retirement` (key `PHASE_OUT`
chỉ `RETIRED` sau `keys.retirement_delay`, mặc định 24h, và cấu hình bị từ chối nếu ngắn hơn
access TTL). Private key mã hóa AES-256-GCM bằng master key cấp qua secret. Tín hiệu
invalidation là `{"type":"all"}` trên `auth:jwks:update`. Chưa có metric key-age (service
chưa export Prometheus metric).
