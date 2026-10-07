---
doc_id: order-adr-003
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/order/data.md
  - docs/order/interfaces.md
---

# ADR-003 — Transactional outbox append-only được phát bằng CDC

## Trạng thái

`Accepted` trong thiết kế; chưa có connector/migration evidence.

> Current authority: ADR-016 mở rộng outbox sang allowlisted multi-destination
> notification commands và yêu cầu exact delivery receipt trước cleanup.

## Bối cảnh

Ghi FSM và publish Kafka theo hai thao tác độc lập có thể tạo state không có event
hoặc event cho transaction rollback. Application-managed outbox poller có mutable
status/retry làm tăng write contention và logic vận hành.

## Quyết định

Với hai transition có registered consumer (`PAID`, `COMPLETED`), update domain state,
tăng per-order integration sequence và insert `outbox_events` trong cùng PostgreSQL
transaction. Transition khác không tạo outbox. Outbox append-only, có partition key
bắt buộc. Debezium/Kafka Connect đọc WAL và route `Order` sang `t.order.events` với
at-least-once delivery. Retry/DLQ/backoff thuộc connector, không lưu mutable outbox
status. Xóa row trên 15 ngày chỉ sau watermark an toàn.

## Phương án đã cân nhắc

- Publish Kafka trực tiếp trong transaction logic: dual-write gap; không chọn.
- Application poller có status: triển khai được nhưng thêm polling/locking/mutation.
- WAL CDC append-only: giữ atomic source commit và throughput; được chọn.

## Hệ quả

Không mất event do crash giữa DB/Kafka; vẫn có duplicate nên consumer phải
idempotent. Hệ thống phụ thuộc WAL retention, connector offset/router và disk
outbox; cleanup sai watermark có thể mất khả năng replay.

## Ảnh hưởng migration

Cần outbox schema/index, Debezium Event Router, connector security/monitoring,
partition/topic mapping và trimmer. Chỉ `com.ecommerce.order.paid.v1` và `com.ecommerce.order.completed.v1`
được allowlist tại router; rollout connector trước khi dọn event cũ.

## Xác minh

Test atomic rollback/commit, connector restart/duplicate, ordering theo order,
schema version, outage backlog và trimmer không xóa trước watermark.
