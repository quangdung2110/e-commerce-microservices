---
doc_id: user-adr-004
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/interfaces.md
  - docs/user/architecture.md
---

# ADR-004 — Độ tin cậy của direct event publishing

## Bối cảnh

Thiết kế kế thừa commit PostgreSQL rồi publish Kafka event trực tiếp. Nó không có
outbox được tài liệu hóa. Publish trước commit có thể expose data rồi rollback;
publish sau commit có thể mất event khi process/broker lỗi.

## Quyết định

Giữ direct publish-after-commit là target design hiện tại, với at-least-once
delivery chỉ sau khi Kafka nhận event và cửa sổ có thể thiếu event sau DB commit.
Producer retry có giới hạn; consumer deduplicate theo stable event ID/business
state. Service phải expose failure và có reconciliation/replay path được chấp
thuận. ADR này không tuyên bố exactly-once delivery.

## Các phương án đã cân nhắc

- Transactional outbox với relay/CDC: khép cửa sổ mất DB-to-broker nhưng thêm
  table, relay, cleanup và operational complexity.
- Publish trong DB transaction: không thể atomically commit Kafka và DB trong
  design này, đồng thời có nguy cơ emit rolled-back data.
- Best-effort fire-and-forget: đơn giản hơn nhưng để lại data divergence im lặng.

## Hệ quả

Projection có thể stale hoặc bỏ lỡ update đến khi reconciliation. Metrics, alerts,
stable event identity, consumer idempotency và operational ownership là bắt buộc.
Một ADR outbox trong tương lai có thể thay thế quyết định này nếu rủi ro không
chấp nhận được.

## Ảnh hưởng migration

Cần producer retry policy, reconciliation source/procedure và contract schema có
ID/version. Việc đưa outbox vào sau này cần migration, relay, backfill/replay và
deployment plan.

## Xác minh

Failure-injection test phải chứng minh không có pre-commit event, có thể phát hiện
post-commit publish failure, consumer an toàn trước duplicate và reconciliation
drill thành công.
