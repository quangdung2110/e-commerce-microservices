---
doc_id: order-adr-016
service: order
status: accepted
date: 2026-10-05
decision_owners:
  - Order Service team
  - Notification Service team
supersedes: null
evidence:
  - docs/order/data.md
  - docs/order/interfaces.md
  - docs/order/operations.md
---

# ADR-016 — Multi-destination outbox và delivery receipts

## Trạng thái

`Accepted` — 2026-10-05. Refine retention/event scope của ADR-003.

## Quyết định

Một append-only `outbox_events` phục vụ allowlisted destinations. `INTEGRATION_FACT`
(`order.paid`, `order.completed`) tăng per-order integration sequence;
`NOTIFICATION_COMMAND` dùng schema/dedup key của Notification và không tiêu thụ
**integration fact sequence**. Order giữ một monotonic
notification sequence riêng, tăng cùng command transaction và bắt buộc gửi trên wire để
Notification serialize/supersede theo subject. Mỗi row chứa destination/topic, class,
stable CloudEvents ID và payload đã validate.

`order_notification_commands(order_id,dedup_key)` là durable ledger theo vòng đời order,
giữ notification sequence, event ID, canonical payload và digest. Outbox unique chỉ là
guard khi row còn sống; trimmer không được làm mất business idempotency. Replay trùng trả
identity cũ, còn cùng dedup key khác digest bị quarantine.

Receipt observer đọc từng destination và insert idempotent
`outbox_delivery_receipts(event_id,destination_topic,partition,offset,observed_at)`.
Trimmer chỉ xóa row quá minimum age khi có exact event/topic receipt và safety delay.
Connector watermark là health signal, không phải per-message delivery proof. Observer
outage giữ row và gây alert/storage growth, không gây data loss.

Notification payload chỉ dùng Order snapshots/config deterministic; không runtime enrich
từ User/Product và không đưa email/phone/full address vào message.

## Xác minh

Provider/schema test cho cả hai destinations và năm templates; test connector/observer
restart, duplicate delivery, wrong-topic receipt và trimmer refusal khi thiếu receipt.
Kiểm tra đồng thời hai transaction phát command không cấp trùng sequence; duplicate
milestone/dedup key kể cả sau outbox trim không tăng sequence; digest collision bị
quarantine; Notification contract từ chối thiếu sequence,
phát hiện same-sequence/different-payload collision, supersede lower sequence và serialize
provider call theo từng order mà không chặn order khác.
