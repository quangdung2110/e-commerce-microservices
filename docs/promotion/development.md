---
doc_id: promotion-development
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
verified_against_commit: null
---

# Promotion Service — Hướng dẫn phát triển

> Promotion Service chưa có source module. Không có command build/run/test thực tế để
> cung cấp; phần này xác định điều kiện cần đáp ứng khi bắt đầu implementation.

## 1. Prerequisites

Theo định hướng repository: JDK 21, Spring Boot 4/Maven, PostgreSQL, Redis Cluster có
Lua và RedisBloom, Kafka, container runtime. Phiên bản chính xác phải được khóa trong
`pom.xml`, Compose và CI tương lai, không suy ra từ tài liệu.

## 2. Chạy local

Chưa khả dụng vì `java/services/promotion-service` chưa tồn tại. Implementation phải bổ
sung command reproducible để build/start, health check và stop/cleanup an toàn; không
được yêu cầu developer chạy trực tiếp DDL thiết kế trên môi trường dùng chung.

## 3. Local dependencies

PostgreSQL, Redis/RedisBloom và Kafka là bắt buộc để integration test đầy đủ. Product,
Checkout, Order, Auth, Notification và Analytics nên được thay bằng contract fixture hoặc
test container khi test Promotion độc lập. Temporal cần fixture cho allocation lifecycle.

## 4. Local configuration

Cần profile local với DB/Redis/Kafka endpoints, topic/group, TTL/schedule/batch settings.
Secret dùng environment/secret store, không commit credential/token hoặc production data.

## 5. Build, lint và format

Chưa có command chuẩn. Khi scaffold service, Maven build/checkstyle/format command phải
trùng CI; generated sources không được chỉnh tay.

## 6. Tests bắt buộc

| Loại | Phạm vi |
|---|---|
| Unit | AST mọi operator, empty/missing semantics, decimal/cap/waterfall, reason mapping |
| Property/concurrency | Determinism, budget invariant, refund clamp, state transitions |
| PostgreSQL integration | CHECK/unique/partial index, guarded claim, idempotency conflict, ledger refund |
| Redis integration | Script 1/2B/3/4/5 result code, rollback từng failure point, TTL/ZSET cleanup, cluster hash-slot |
| Kafka | Envelope/version, key ordering, duplicate event+business ID, retry/parking-lot, lost-send observability |
| Worker | pending rescue, reservation race, markdown fallback/startup rebuild, DB-first reconciliation |
| HTTP | AuthZ, pure preview, save state guards, 409 recalculation, late-payment error |
| Load | 10.000 contender/1.000 claim; discovery p99; Lua bounded runtime; DB/Redis/Kafka capacity |
| End-to-end | Preview→Submit→confirm/cancel; full/partial refund; markdown→Product→Search |

`RuleEngineDemo.java` chỉ là executable example. Production test phải chứng minh Jackson
polymorphism (`node_type`), `children`, numeric safety và NOT_IN collection semantics.

## 7. Contract code generation

Chưa có OpenAPI/AsyncAPI/Promotion schema. Lần chuẩn hóa contract sau phải tạo nguồn
machine-readable, pin generator version, generate adapter/model vào output path rõ ràng
và có CI drift/breaking-change check. Không dùng payload Markdown làm schema chuẩn.

## 8. Database migration

Tạo baseline migration additive từ data model trong `data.md`, gồm enum/check/unique/
index và seed/test data. Cần test forward compatibility, rollback code và backfill Redis
budget/claim/schedule. Không coi SQL fragment trong tài liệu là migration.

## 9. Test data và fixtures

Fixture tối thiểu: unlimited/platform voucher, percentage/fixed, nested AST, campaign
active/ended, wallet SAVED/USED/EXPIRED, finite/unlimited amount, partial/full refund,
stale/missing reservation và duplicate/mismatched event. Dùng UUID giả; không dùng PII
hay dump production.

## 10. Debugging

- Trace correlation/workflow/event/order/promotion/claim IDs.
- Inspect PostgreSQL idempotency + usage trước khi sửa Redis counter.
- Inspect reservation/pending/schedule index và payload bằng read-only command.
- Kiểm tra Kafka lag/event key/schema và direct-producer failure.
- Không SADD claim trở lại pool, xóa reservation/index, sửa counter hay offset bằng tay
  nếu chưa theo runbook và đối chiếu DB.

## 11. Lỗi local phổ biến cần tài liệu hóa khi có code

- Redis Cluster `CROSSSLOT` do multi-key Lua không cùng hash tag.
- RedisBloom module không được nạp.
- Snapshot version mismatch/cache miss liên tục.
- Consumer group/topic chưa tạo hoặc schema version không hỗ trợ.
- PostgreSQL partial unique/CHECK migration thiếu.
- Clock/timezone sai; mọi persisted/event time phải UTC.

## 12. Definition of Done

- Build, lint, unit/integration/concurrency/contract/load test phù hợp đều pass.
- Migration và Redis key/Lua version tương thích rollout/rollback.
- OpenAPI/AsyncAPI được cập nhật nếu interface thay đổi.
- Mọi mutation test duplicate, timeout, crash trước/sau DB/Redis/Kafka boundary.
- Budget/claim/wallet/usage invariants và DB-first reconciliation được chứng minh.
- Dashboard, alert, runbook, retention và security review hoàn thành.
- Evidence/frontmatter được cập nhật; chỉ nâng `implemented/verified` khi có source/test.
- ADR mới được thêm nếu đổi boundary, storage, consistency, caching hoặc messaging.
