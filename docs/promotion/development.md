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

> Promotion Service chưa có source module. Phần này đặt Definition of Done cho
> implementation tương lai, không tuyên bố command/runtime hiện đã tồn tại.

## 1. Prerequisites và local dependencies

Định hướng: JDK 21, Spring Boot 4/Maven, PostgreSQL logical replication, Redis Cluster
có Lua/RedisBloom, Kafka và Debezium/Kafka Connect. Product/Checkout/Order/Auth nên dùng
contract fixtures hoặc test containers khi test Promotion độc lập; Temporal cần fixture
cho allocation lifecycle.

## 2. Configuration

Cần profile local cho DB/Redis/Kafka/connector, topic/group, reservation TTL, pending
rescue, campaign loop/fallback và batch size. Mọi time UTC, money integer VND. Secret
đến từ environment/secret store, không commit credential.

## 3. Tests bắt buộc

| Loại | Phạm vi |
|---|---|
| Unit | AST operators, missing/empty, integer discount/cap, campaign phase `[start,end)` |
| Domain | validity modes, natural end giữ voucher, customer refund no-op |
| PostgreSQL | bảy bảng, CHECK/unique, wallet transitions, outbox dedup/source revision |
| Redis | claim/reserve/confirm/cancel/cancel-confirmed Lua, integer counters, hash slot |
| Concurrency | 10.000 contender, duplicate claim, confirm/cancel/sweep races |
| Schedule | stale revision, delayed START, Redis loss, fallback/rebuild, commit-before-ZREM |
| CDC/Kafka | outbox atomicity, duplicate delivery, connector recovery, Product stale revision |
| HTTP | AuthZ, discovery/save, no refund endpoint, stable allocation retry keys |
| Reconciliation | expected DB state + valid reservations, one-way repair |
| End-to-end | Publish→START/END→Product; save→reserve→confirm/cancel-confirmed |

Phải có negative integration test chứng minh `ORDER_REFUNDED` không mutate wallet/budget
và Promotion không yêu cầu `promotion_refund_allocations[]`.

## 4. Rule Engine

`RuleEngineDemo.java` chỉ là executable example. Production phải có JSON Schema,
Jackson sealed hierarchy/Visitor, depth/size limits, numeric safety và golden/property
tests. `rule_schema_version` version hóa AST; `campaigns.revision` version hóa business
configuration. Không cache object AST trong JVM.

## 5. Database migration

Baseline migration tạo:

- `campaigns`
- `promotions`
- `promotion_budgets`
- `coupon_claims`
- `user_coupon_wallets`
- `markdown_rules`
- `promotion_outbox_events`
- sequence cho markdown `source_revision`
- CHECK, FK, unique/partial unique và query indexes trong `data.md`

Không tạo `coupon_usages` hoặc `promotion_event_idempotency`. Nếu migrate từ prototype
cũ, phải backfill current wallet/budget state trước khi drop table; không suy diễn
customer refund thành technical compensation.

## 6. Redis implementation

- Mọi multi-key promotion Lua dùng `{promotion_id}`.
- Claim Lua ghi claimed-user + pending atomically với SPOP.
- Reserve index là per-promotion để cùng cluster slot.
- Campaign schedule dùng member chứa revision; worker không destructive pop.
- Amount dùng `HINCRBY`, không `HINCRBYFLOAT`.
- Lua phải versioned, bounded và có structured result codes.

## 7. Outbox/contract generation

Outbox payload là CloudEvent hoàn chỉnh; connector route theo `event_type`/topic và giữ
partition key. Pin connector/generator version; CI kiểm schema compatibility và fixture.
Markdown dùng contract hiện hành
`com.ecommerce.promotion.product-markdown-snapshot.v1`, không tạo ba delta event legacy.

Promotion HTTP cần OpenAPI trước implementation. Contract không được chứa refund endpoint
hoặc SKU-level voucher allocation.

## 8. Test data

Fixture tối thiểu:

- campaign DRAFT/UPCOMING/ACTIVE/ENDED/PAUSED/CANCELLED và stale revision;
- voucher percentage/fixed, ba validity mode, limited/public/direct grant;
- wallet SAVED/USED/EXPIRED, technical compensation còn hạn/hết hạn;
- finite/unlimited qty/VND budget, stale/missing reservation;
- markdown nhiều campaign cùng product và source revision out-of-order;
- customer partial/full refund event làm Promotion no-op.

Order/Payment partial refund count/amount không được hard-code trong Promotion fixture vì
thiết kế hai service đó chưa chốt.

## 9. Debugging

- Trace correlation/workflow/event/order/promotion/claim/campaign ID.
- Inspect wallet/budget/outbox trước Redis counter.
- Inspect pending/reservation/schedule bằng read-only command.
- Kiểm tra DB campaign revision/time trước khi sửa ZSET.
- Kiểm tra connector offset/outbox age/Product source revision khi markdown stale.
- Không SADD claim, xóa reservation/outbox/index hoặc sửa counter thủ công ngoài runbook.

## 10. Definition of Done

- Build/lint/unit/integration/concurrency/contract/load tests pass.
- Migration và Redis key/Lua version có rollout/rollback plan.
- OpenAPI/AsyncAPI/JSON Schema và examples nhất quán.
- Mọi crash boundary DB–Redis–outbox/CDC có replay/reconciliation test.
- Campaign không activate sớm, stale job no-op và fallback hội tụ.
- Customer refund không thay đổi Promotion; technical compensation trả voucher còn hạn.
- Dashboard, alerts, runbooks, CDC retention/watermark và security review hoàn tất.
- Chỉ nâng `implemented/verified` khi có source/test evidence.
