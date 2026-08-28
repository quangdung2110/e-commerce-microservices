---
doc_id: <service-id>-data
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-migrations>
  - <path-to-entities-or-models>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Data Design

> File này trả lời: “Dữ liệu được sở hữu, lưu trữ và duy trì tính đúng đắn thế nào?”

## 1. Data ownership

Liệt kê:

- Table/collection/stream nào service sở hữu.
- Dữ liệu external nào được replicate, cache hoặc materialize.
- Source of truth của từng nhóm dữ liệu.
- Read/write boundary với service khác.

## 2. Logical data model

Mô tả entity, quan hệ, cardinality và lifecycle. Dùng ERD khi nó giúp hiểu mô
hình tốt hơn. Phân biệt rõ logical model với physical schema.

## 3. Schema sources

Liên kết tới:

- Database migration.
- Entity/model.
- Schema generation hoặc verification tool.
- Phiên bản schema đang được production sử dụng nếu biết.

Không coi câu SQL copy trong Markdown là schema chuẩn.

## 4. Constraints

Mô tả:

- Primary key.
- Unique constraint.
- Foreign key.
- Check constraint.
- Business constraint không thể biểu diễn bằng database.
- Nơi constraint được enforcement và test.

## 5. Indexes và query patterns

Với mỗi index, nêu query pattern, ordering/filter cần phục vụ và trade-off khi
ghi. Không chỉ liệt kê tên index.

## 6. Transaction boundaries

Mô tả:

- Dữ liệu nào được cập nhật trong cùng transaction.
- Isolation level.
- Locking strategy.
- Cách chống lost update và write skew.
- Side effect nào nằm ngoài transaction.

## 7. Caching

Với mỗi nhóm cache, mô tả:

- Key format.
- Value/schema.
- TTL.
- Source of truth.
- Read/write pattern.
- Invalidation.
- Cache stampede protection.
- Stale-data policy.
- Hành vi khi cache unavailable.

## 8. Outbox/inbox và idempotency storage

Mô tả schema, lifecycle, deduplication key, relay/consumer behavior, retention và cleanup.

## 9. Data lifecycle

Mô tả:

- Retention.
- Archive.
- Hard delete/soft delete.
- Migration và backfill.
- Backup và restore.
- Reconciliation.

## 10. Security và privacy

Mô tả:

- PII hoặc sensitive data.
- Encryption at rest/in transit.
- Masking/redaction trong log.
- Audit trail.
- Access control.
- GDPR/data deletion requirement.
