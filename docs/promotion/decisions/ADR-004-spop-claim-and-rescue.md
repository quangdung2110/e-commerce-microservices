---
doc_id: promotion-adr-004
service: promotion
status: accepted
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - docs/promotion/workflows.md
  - docs/promotion/data.md
---

# ADR-004 — SPOP claim với pending rescue

## Status

`Accepted` ở mức thiết kế; chưa implemented.

## Context

Hàng nghìn user tranh số suất hữu hạn trong một giây; DB lock trực tiếp dễ thành bottleneck.
Crash sau khi cấp trên Redis nhưng trước Kafka/DB có thể tạo “vé ảo”.

## Decision

Pre-generate claim rows, preload Redis Set và dùng SPOP O(1). Cùng Lua ghi user flag,
pending Hash + timestamp ZSET. API trả success sau direct publish. Sweeper republish item
cũ hơn 5 phút với event ID mới nhưng claim ID bất biến; không trả claim về pool và chỉ
xóa pending sau DB commit.

## Alternatives considered

- `SELECT FOR UPDATE SKIP LOCKED`: đúng ở DB nhưng tăng contention/I/O.
- Counter decrement không claim ID: khó audit/cứu chính xác từng suất.
- Trả vé về pool khi publish lỗi: có thể cấp cùng suất cho hai user đã thấy success.

## Consequences

DB tránh bão claim, user success được bảo vệ. Đổi lại wallet eventual, cần dedup hai tầng,
pending memory/worker và có nguy cơ backlog nếu Kafka/DB dài hạn lỗi.

## Migration impact

Cần seed claim IDs, atomic preload/rebuild policy, consumer/sweeper, unique constraints và
retention cleanup.

## Verification

Load test 10.000/1.000, kill API ở mọi boundary, duplicate/republish, DB guard conflict và
chứng minh tổng claim không vượt quota.
