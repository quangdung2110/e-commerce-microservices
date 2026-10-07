---
doc_id: user-adr-005
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/data.md
  - docs/user/workflows.md
---

# ADR-005 — GDPR anonymization và retention purge hai giai đoạn

## Bối cảnh

Auth khởi tạo account deletion, nhưng User sở hữu PII đáng kể. Không thể dựa vào
Kafka retention để trigger deletion sau 90 ngày. Downstream cần prompt signal để
hide/anonymize projection.

## Quyết định

Khi nhận `USER_DELETED`, User tạo một durable retention record, anonymize profile
PII và hard-delete address/preferences trong một transaction. Sau commit, nó emit
`ACCOUNT_DELETED`. Compliance worker hard-delete anonymized profile cùng retention
record chỉ tại `hard_delete_after` do Auth phát, kế thừa là 90 ngày. Retention row tự
thân biểu thị pending purge; không cần field `status` không được tài liệu hóa.

## Các phương án đã cân nhắc

- Trì hoãn mọi deletion 90 ngày: để PII có thể định danh bị expose không cần thiết.
- Dùng Kafka retention làm scheduler: lỗi khi event hết hạn hoặc consumer down.
- Hard delete ngay: loại recovery/retention window và có thể mâu thuẫn compliance.

## Hệ quả

Service giữ non-PII deletion record và cần scheduled worker, due-backlog alert,
clock correctness và backup/restore policy. Downstream vẫn có nghĩa vụ độc lập sau
`ACCOUNT_DELETED`. Deletion là terminal: verification event cũ không được tạo lại
profile.

## Ảnh hưởng migration

Cần deletion table có unique user ID/deadline index, PII scrubbing rule, consumer
idempotency, worker claim/lock và event contract. Deployment phải đưa safe schema
vào trước khi consumer/worker activation.

## Xác minh

Test immediate anonymization, duplicate deletion, deadline enforcement, worker
crash/resume, downstream signal và controlled retention-purge drill.
