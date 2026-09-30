---
doc_id: payment-adr-018
service: payment
status: accepted
date: 2026-09-22
decision_owners:
  - Payment Service team
refines:
  - payment-adr-014
  - payment-adr-015
evidence:
  - docs/payment/domain.md
  - docs/payment/interfaces.md
  - docs/payment/data.md
---

# ADR-018 — Provider checkpoint, conflict resolution và Stripe event contract

## Trạng thái

`Accepted` — 2026-09-22.

## Bối cảnh

FSM cũ nói precedence theo `event.created` nhưng aggregate không lưu checkpoint;
timestamp Stripe có thể trùng giây. Contract refund dùng `charge.refund.updated`,
event chỉ bao phủ một số payment method, và coi PaymentIntent `processing` là không
thể cancel tuyệt đối dù provider có thể cho phép trong trường hợp hiếm.

## Quyết định

1. PaymentTransaction lưu checkpoint PaymentIntent
   `(last_provider_event_created_at, last_provider_event_id)`; RefundTransaction và
   PaymentDispute có checkpoint riêng cùng cấu trúc.
2. Event có `created` nhỏ hơn checkpoint là stale. Cùng timestamp và cùng id là
   duplicate; cùng timestamp khác id hoặc facts mâu thuẫn thì retrieve provider
   object hiện tại và áp fact hiện hành, không chọn bằng lexical event id.
   `PaymentIntent.retrieve`, `Refund.retrieve` và `Dispute.retrieve` map lifecycle
   snapshot về fact canonical; trạng thái hiện tại chưa tạo transition thì chỉ
   advance checkpoint. Lỗi transport retry qua durable inbox; lỗi retrieve 4xx mở
   `PROVIDER_RETRIEVE_REJECTED`. Riêng tie giữa hai money-movement dispute
   `funds_withdrawn`/`funds_reinstated` không được suy ra từ lifecycle snapshot:
   advance checkpoint và mở `DISPUTE_FUNDS_TIE_UNRESOLVED`; lane balance-ledger
   reconciliation quyết định bằng balance transaction thật.
3. Terminal fact không bị rollback bởi snapshot cũ. `succeeded` đối nghịch một
   terminal non-capture đã commit mở `CAPTURE_AFTER_CANCEL` và incident.
4. Webhook endpoint subscribe:
   `payment_intent.processing`, `payment_intent.succeeded`,
   `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`,
   `refund.updated`, `refund.failed`, `charge.dispute.created`,
   `charge.dispute.updated`, `charge.dispute.closed`,
   `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`.
   `charge.refund.updated` không còn là contract canonical.
5. Cancel command/scanner có thể thử cancel một intent `processing`. Kết quả provider
   quyết định: `canceled` -> terminal non-capture; `succeeded` -> success; lỗi
   not-cancelable hoặc vẫn `processing` -> `CANCEL_PENDING`, retrieve/recovery tiếp.
6. API version Stripe được pin bằng một giá trị release cụ thể trong deployment;
   nâng version cần ADR + fixture/provider-contract test trước rollout.

> Cập nhật 2026-09-30: cách map `charge.refunded`/`refund.updated` và các status
> `canceled`/`requires_action` được tinh chỉnh bởi [ADR-022](./ADR-022-refund-lifecycle-hardening.md);
> tập 12 event ở mục 4 không đổi.

## Hệ quả

Ordering correctness không phụ thuộc Kafka partition hay timestamp đơn. Refund failure
và reversal được nhận cho mọi payment method hỗ trợ. FSM phản ánh response provider
thay vì giả định cứng về `processing`.

## Xác minh

Test older/newer/tied event timestamps, conflicting facts, retrieve resolution,
duplicate event id, refund `updated/failed`, dispute lifecycle và cancel processing
trả canceled/succeeded/not-cancelable.
