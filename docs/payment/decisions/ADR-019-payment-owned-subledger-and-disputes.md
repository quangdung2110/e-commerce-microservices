---
doc_id: payment-adr-019
service: payment
status: accepted
date: 2026-09-22
decision_owners:
  - Payment Service team
  - Order Service team
supersedes: null
evidence:
  - docs/payment/domain.md
  - docs/payment/data.md
  - contracts/events/payment/v2/asyncapi.yaml
---

# ADR-019 — Payment sở hữu operational subledger, dispute và settlement reconciliation

## Trạng thái

`Accepted` — 2026-09-22.

## Bối cảnh

Hệ thống hiện không muốn tách thêm Ledger/Accounting/Dispute Service. Payment đã là
authority của provider money state nhưng schema cũ chỉ có FSM/history, chưa ghi sổ
đối ứng cho capture/refund/reversal/dispute/fee/payout và chưa phát dispute facts.

## Quyết định

1. Payment Service sở hữu toàn bộ capability liên quan tiền trong release này:
   orchestration, single-shot refund, operational double-entry subledger, dispute/
   chargeback, provider balance transaction, fee, payout/settlement reconciliation.
   Không tạo microservice tài chính mới.
2. Subledger là bằng chứng vận hành bất biến, không thay General Ledger pháp lý bên
   ngoài nếu doanh nghiệp bổ sung sau này. Mỗi journal có ít nhất hai line VND, tổng
   debit bằng tổng credit; insert journal + lines + business transition + outbox trong
   cùng PostgreSQL transaction khi cùng một fact gây ra chúng.
3. Account code release đầu: `PROVIDER_RECEIVABLE`, `CUSTOMER_PAYMENT_CLEARING`,
   `CUSTOMER_REFUND_CLEARING`, `DISPUTE_HOLD_OR_LOSS`, `PROCESSING_FEE_EXPENSE`,
   `BANK_SETTLEMENT_CLEARING`. Không update/delete ledger; sửa bằng reversal journal
   tham chiếu journal gốc.
   Posting profile VND bắt buộc:
   - capture: Dr `PROVIDER_RECEIVABLE`, Cr `CUSTOMER_PAYMENT_CLEARING`;
   - refund: Dr `CUSTOMER_REFUND_CLEARING`, Cr `PROVIDER_RECEIVABLE`;
   - refund reversal: Dr `PROVIDER_RECEIVABLE`, Cr `CUSTOMER_REFUND_CLEARING`;
   - dispute withdrawal: Dr `DISPUTE_HOLD_OR_LOSS`, Cr `PROVIDER_RECEIVABLE`;
   - dispute reinstatement: Dr `PROVIDER_RECEIVABLE`, Cr `DISPUTE_HOLD_OR_LOSS`;
   - processing fee: Dr `PROCESSING_FEE_EXPENSE`, Cr `PROVIDER_RECEIVABLE`;
   - payout settlement: Dr `BANK_SETTLEMENT_CLEARING`, Cr `PROVIDER_RECEIVABLE`.
   Không net movement khác loại vào một journal.
4. `provider_balance_transaction_id` unique là dedup money movement. Capture, refund,
   refund reversal, dispute funds withdrawn/reinstated, processing fee và payout tạo
   journal theo balance transaction thực tế; không ghi ledger chỉ từ HTTP intent.
5. `PaymentDispute` là aggregate riêng, lifetime theo `provider_dispute_id`, FSM:
   `OPEN -> UNDER_REVIEW -> WON | LOST | WARNING_CLOSED`. Funds withdrawn/reinstated
   là movement độc lập có ledger journal và financial fact; status outcome không được
   dùng thay movement fact.
6. Payment phát thêm năm fact v2 trên cùng topic/key/version stream:
   `DISPUTE_OPENED`, `DISPUTE_FUNDS_WITHDRAWN`, `DISPUTE_FUNDS_REINSTATED`,
   `DISPUTE_WON`, `DISPUTE_LOST`. Order đưa order vào financial/manual-review lane;
   Analytics consume. Payment tự vận hành evidence/deadline/provider reconciliation.
7. Một order có đúng một refund case/transaction trọn đời. Command có thể chọn một
   tập order line, nhưng mỗi line phải hoàn toàn bộ ordered quantity và chỉ có đúng
   **một lần payout/refund**; không có incremental/second partial refund. Provider
   fail/reversal tiếp tục cùng transaction và được xử lý thủ công, không mở refund mới.
8. Reconciliation có hai lane durable: object-state và balance-ledger. Lane balance
   dùng provider cursor/high-watermark có overlap, ingest balance transactions bằng
   unique ID, đối chiếu tổng capture/refund/dispute/fee/payout và không auto-correct
   ledger bằng mutable update.

## Hệ quả

Payment lớn hơn nhưng ownership tiền không bị phân tán. Mọi thay đổi tiền có journal
đối ứng và reconciliation. Order vẫn không quyết định provider state; Order chỉ quyết
định khi nào gửi single-shot refund và phản ứng với dispute facts.

## Xác minh

Test journal balance/immutability/dedup/reversal, atomicity với transition/outbox,
toàn bộ dispute FSM và năm event, reconciliation cursor overlap/restart, provider
balance orphan, fee/payout totals, và concurrent second refund bị từ chối.
