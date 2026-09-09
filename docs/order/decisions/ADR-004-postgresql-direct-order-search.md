---
doc_id: order-adr-004
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: order-elasticsearch-read-model
evidence:
  - docs/order/data.md
  - docs/order/workflows.md
---

# ADR-004 — Tìm kiếm/lọc order trực tiếp trên PostgreSQL

## Trạng thái

`Accepted` từ Phase 15; supersede kiến trúc CQRS Elasticsearch cũ. Ngày gốc và
benchmark chưa được ghi.

## Bối cảnh

Buyer/admin cần lọc lịch sử order và tìm theo tên sản phẩm snapshot. Elasticsearch
read model kéo theo projector, retry/DLQ topic, reindex, lag và runbook trong khi
nhu cầu text search chỉ là substring cơ bản.

## Quyết định

PostgreSQL vừa là source of truth vừa là read path. Class A dùng B-tree/partial
indexes; class B dùng `pg_trgm + unaccent` trên `snapshot_name`; buyer tab qua view;
pagination keyset `(created_at,id)`; default buyer range 90 ngày. Loại bỏ ES,
projector/reindex, Kafka CQRS retry/DLQ và order metrics projection.

## Phương án đã cân nhắc

- Elasticsearch CQRS: search giàu tính năng nhưng vận hành/consistency phức tạp.
- PostgreSQL FTS linguistic: tốt hơn tokenization nhưng vượt nhu cầu cũ.
- Trigram substring: đơn giản, accent-insensitive; được chọn.

## Hệ quả

Read-after-write theo DB và ít component hơn. Mất fuzzy, scoring, tokenization,
highlight, autocomplete; GIN tốn 3–5x disk; admin full-scope và subquery card có
thể chậm. Materialized view chỉ được thêm sau benchmark/ADR.

## Ảnh hưởng migration

Bật extensions, tạo indexes/view, bỏ projector/topic/index ES và sửa API card/
cursor. Giữ toàn bộ order trong OLTP sau Phase 16, không cold cutoff.

## Xác minh

Cần migration, EXPLAIN/load test class A/B, Vietnamese accent cases, cursor
correctness, disk monitoring và chứng minh không còn search worker/config ES.
