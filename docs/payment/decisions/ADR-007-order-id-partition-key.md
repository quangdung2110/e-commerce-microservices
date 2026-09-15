---
doc_id: payment-adr-007
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/interfaces.md
  - docs/payment/data.md
---

# ADR-007 — Partition key `order_id` cho `t.payment.events`, Order route theo `payload.order_id`

## Trạng thái

`Accepted` trong thiết kế; chưa xác minh implementation. Ngày quyết định gốc không
được ghi lại.

## Bối cảnh

Payment phát cả `PAYMENT_*` (aggregate là payment transaction) và `REFUND_*`
(aggregate là refund transaction) cho cùng một đơn hàng. Order Service consumer
cần strict ordering giữa các event của cùng đơn (ví dụ `PAYMENT_SUCCESS` trước
`REFUND_PROCESSING`) để signal Temporal workflow đúng thứ tự. Nếu partition theo
`transaction_id`, event payment và refund của cùng order có thể rơi vào partition
khác nhau.

## Quyết định

- Topic `t.payment.events` partition theo **`order_id`** thay vì `transaction_id`;
  `outbox_events.partition_key = order_id` để mọi event payment + refund của cùng
  đơn vào cùng partition → strict ordering cho Order consumer.
- Envelope giữ `aggregate_type = Payment` (kể cả REFUND_*), `aggregate_id` là
  transaction-level (`payment_transaction_id` cho PAYMENT_*,
  `refund_transaction_id` cho REFUND_*), `business_id = order_id`.
- **Routing contract:** Order Service route workflow theo `payload.order_id`,
  **không** route theo `aggregate_id`.
- `correlation_id` là Temporal Workflow ID (ví dụ `wf-10001`) để truy vết E2E.
- Bất biến này được giữ kể cả khi tách Refund Service
  ([ADR-002](./ADR-002-refund-inside-payment-service.md)).

## Phương án đã cân nhắc

- **Partition theo `transaction_id`/`aggregate_id`:** phân tán đều hơn nhưng mất
  ordering giữa payment và refund của cùng order. Không chọn.
- **Partition theo `order_id`:** ordering đúng theo nhu cầu Order consumer; hot
  partition không đáng kể vì mỗi order có ít event. Được chọn.

## Hệ quả

Tích cực: Order consumer nhận đúng thứ tự cho từng đơn; đơn giản hóa guard phía
consumer. Tiêu cực: `aggregate_id` và routing key khác nhau, consumer phải đọc
`payload.order_id`; `aggregate_type` không phân biệt Payment/Refund nên
`event_type` là thứ phân biệt.

## Ảnh hưởng migration

`outbox_events.partition_key` NOT NULL = `order_id`; Debezium connector dùng
`partition_key` làm Kafka key; Order consumer route theo payload.

## Xác minh

Cần connector config, test nhiều event cùng order vào cùng partition và đúng thứ
tự, test Order consumer route theo `payload.order_id`.
