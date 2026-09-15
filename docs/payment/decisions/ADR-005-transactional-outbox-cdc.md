---
doc_id: payment-adr-005
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/data.md
  - docs/payment/operations.md
---

# ADR-005 — Transactional outbox append-only phát qua Debezium CDC

## Trạng thái

`Accepted` trong thiết kế; thay thế Outbox Polling Worker (legacy, đã deprecated).
Thiết kế cũ ghi Payment "đang trong quá trình migrate từ Polling Pattern sang CDC"
nhưng không có evidence. Ngày quyết định gốc không được ghi lại.

## Bối cảnh

Payment phát `PAYMENT_*`/`REFUND_*` là nguồn sự thật cho trạng thái tiền tệ; Order
consume để signal Temporal workflow. Event phải không bao giờ được publish trước
khi DB transaction commit, và không được mất khi service crash sau commit. Pattern
polling worker cần cột mutable `status/retry_count/backoff` trong outbox và một
worker riêng để relay, retry, DLQ.

## Quyết định

- Mọi state transition insert row vào `outbox_events` **trong cùng transaction**
  với update transaction/refund và `payment_status_history`.
- `outbox_events` là append-only theo canonical CDC Pattern: `event_id` UUID PK,
  `schema_version`, `aggregate_type` (cố định `Payment`), `aggregate_id`
  (`payment_transactions.id` cho PAYMENT_*, `refund_transactions.id` cho
  REFUND_*), `event_type`, `partition_key` (`order_id`), `payload` JSONB,
  `created_at`. **Cấm cột polling-style** — retry/DLQ/backoff chuyển sang Kafka
  Connect runtime.
- Debezium đọc WAL, route `aggregate_type=Payment` → topic `t.payment.events` với
  `partition_key = order_id`.
- Delivery at-least-once; consumer dedup theo `event_id`.

## Phương án đã cân nhắc

- **Outbox Polling Worker (legacy):** application tự poll/relay/retry; cần cột
  mutable trong outbox; thêm worker và độ trễ poll. Deprecated.
- **Publish trực tiếp từ application sau commit:** mất event khi crash giữa commit
  và publish. Không chọn.
- **Debezium CDC:** atomic với commit, không cần worker relay, retry/DLQ do Kafka
  Connect. Được chọn.

## Hệ quả

Tích cực: atomicity giữa state và event, không có dual-write; outbox đơn giản.
Tiêu cực: phụ thuộc WAL/Kafka Connect/Debezium (connector config, restart, lag);
cần cleanup/retention cho outbox (chưa được thiết kế cũ mô tả); migration từ
polling phải tắt worker cũ sau khi connector route đúng.

## Ảnh hưởng migration

Bỏ cột polling-style khỏi outbox; cấu hình Debezium connector cho Payment DB;
tắt Outbox Polling Worker; thống nhất tên bảng (`outbox_events` vs
`payment_outbox` — xem `README.md` §9).

## Xác minh

Cần migration `outbox_events` không có cột mutable, connector config route theo
`aggregate_type`/`partition_key`, test at-least-once và connector restart, metric
outbox publish success rate và latency (SLO gợi ý 99.9% < 60s).
