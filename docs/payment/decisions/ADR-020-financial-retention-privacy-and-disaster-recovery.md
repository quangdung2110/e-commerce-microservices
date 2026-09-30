---
doc_id: payment-adr-020
service: payment
status: accepted
date: 2026-09-22
decision_owners:
  - Payment Service team
  - Platform team
refines:
  - payment-adr-013
evidence:
  - docs/payment/data.md
  - docs/payment/operations.md
---

# ADR-020 — Financial retention hữu hạn, bảo vệ PII và disaster recovery fail-closed

## Trạng thái

`Accepted` — 2026-09-22. Giữ quyết định một OLTP schema/không partition của ADR-013
nhưng thay retention vô thời hạn bằng policy hữu hạn có thể thực thi.

## Quyết định

1. Payment/refund/dispute, ProviderOperation, journal/ledger, status history và review
   case giữ 7 năm từ financial close; retention là baseline kỹ thuật và phải tăng nếu
   yêu cầu pháp lý tại jurisdiction go-live dài hơn. Legal hold chặn deletion.
2. Verified webhook raw body và signature được application-envelope-encrypt, chỉ giữ
   90 ngày; metadata không PII giữ 12 tháng. Sau 90 ngày xóa ciphertext/key reference,
   không xóa inbox identity/status cần cho dedup trong 12 tháng.
3. Khi nhận verified user-deletion instruction qua thao tác compliance nội bộ, Payment
   pseudonymize `user_id` bằng irreversible tombstone UUID sau khi không còn active
   payment/refund/dispute/review case. Order/payment/provider identities và financial
   amounts được giữ theo nghĩa vụ audit; không lưu email/tên/PAN.
4. Backup PostgreSQL dùng encrypted PITR. Mục tiêu committed financial data `RPO=0`
   trong failure domain đã thiết kế và `RTO <= 60 phút`; nếu không chứng minh được
   consistency state/ledger/outbox/inbox sau restore, Payment giữ write path đóng.
5. Restore drill hàng tháng kiểm tra unique identities, balanced journals, state↔ledger,
   outbox range/connector offset, inbox checkpoints và provider reconciliation trước
   khi mở write.
6. Kafka là transport, không là backup authority. Rebuild consumer/outbox delivery từ
   PostgreSQL evidence; không synthesize event id mới cho fact đã commit.

## Xác minh

Test crypto-shredding raw body, pseudonymization guard, legal hold, trimmer batch,
PITR restore, journal balance/state/outbox validation, connector offset recovery và
drill RTO. Retention/config phải có metric và alert trước production.
