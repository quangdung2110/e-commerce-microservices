---
doc_id: promotion-overview
service: promotion
scope: target
status: implemented
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/events/promotion/v1/asyncapi.yaml
  - contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json
verified_against_commit: null
---

# Promotion Service — Tổng quan

Promotion Service sở hữu hai loại lợi ích khác nhau dưới cùng một campaign: voucher
toàn đơn và markdown theo sản phẩm. Campaign là lớp quản trị/lịch chung; voucher đã
được lưu là quyền lợi của người dùng, còn markdown chỉ có hiệu lực khi campaign đang
hoạt động.

## 1. Trạng thái service

| Thuộc tính | Trạng thái |
|---|---|
| Vòng đời service | `implemented` — service code, Flyway migration, Lua scripts và test suite nằm trong repository; chưa deploy production |
| Runtime | Java 21, Spring Boot 3.3 (đồng bộ với các service Java khác), PostgreSQL 16, Redis 7, Kafka + Debezium |
| Implementation path | `java/services/promotion-service` (package `com.platform.promotionservice`) |
| Độ tin cậy tài liệu | `implemented`; tài liệu đã được đồng bộ với source/migration trong lần implement đầu; `verified_against_commit` sẽ được ghi khi merge |
| Contract adoption | HTTP Promotion v1 và Promotion Events v1 (markdown snapshot) `implemented`: provider test MockMvc cho HTTP, JSON Schema contract test cho CloudEvent ACTIVE/INACTIVE; consumer Product đã chấp nhận INACTIVE tombstone |

Các ADR trong `decisions/` là quyết định thiết kế, không phải bằng chứng code production.

## 2. Mục tiêu

- Cho admin tạo và vận hành campaign, voucher và markdown product-scope.
- Tách đúng vòng đời campaign, claim voucher và hạn sử dụng voucher đã lưu.
- Đánh giá rule AST xác định, không query database/network trong từng node.
- Bảo vệ quota/ngân sách khi nhiều người dùng claim hoặc submit đồng thời.
- Kích hoạt/kết thúc markdown không sớm, tự phục hồi khi Redis/worker lỗi và phát markdown
  snapshot bền vững qua transactional outbox + CDC.
- Đóng claim đúng hard boundary bằng Redis time/revision gate và dọn markdown reference
  khi Product bị hard delete.
- Giữ PostgreSQL là source of truth và tự sửa drift của Redis hot state.

## 3. Boundary

Promotion chịu trách nhiệm:

- `campaigns`, `promotions`, `promotion_budgets`, `coupon_claims`,
  `user_coupon_wallets`, `markdown_rules`, `catalog_product_tombstones`,
  `promotion_command_receipts` và `promotion_outbox_events`.
- Một slot `PLATFORM_VOUCHER` cho toàn đơn, Rule Engine, eligibility và discount.
- Claim/save, reserve, confirm, cancel và `cancel-confirmed` do lỗi kỹ thuật.
- Campaign schedule, pending-claim rescue, reservation expiry và reconciliation.
- State-discriminated product markdown snapshot cho Product và consume Product tombstone.

Promotion không chịu trách nhiệm:

- Giá gốc/giá bán công bố, category membership hoặc catalog; Product sở hữu các dữ liệu đó.
- Search/filter/ranking; Search đọc giá do Product công bố.
- Tính tiền refund, refund scope, số lần partial refund hoặc Payment state machine.
- Phân bổ voucher toàn đơn xuống SKU/order line.
- Hoàn budget hay tái cấp voucher khi khách hàng refund partial/full.
- Inventory, shipping discount, seller/merchant promotion.

`ORDER_REFUNDED` không phải input của Promotion. Order/Payment có thể thay đổi chính sách
partial refund sau khi được refactor mà không làm thay đổi Promotion boundary này.

## 4. Data ownership

| Nhóm dữ liệu | Source of truth |
|---|---|
| Campaign, voucher, AST, markdown rule | PostgreSQL Promotion |
| Budget cấu hình và số đã claim/redeem | `promotion_budgets` |
| Claim inventory | `coupon_claims` |
| Quyền sở hữu, expiry và redemption voucher | `user_coupon_wallets` |
| Durable confirm/technical-cancel command | `promotion_command_receipts` |
| Integration event (chỉ markdown snapshot ở v1) | `promotion_outbox_events` trong cùng transaction với business state |
| Reservation đang hiệu lực | Redis runtime; không persist `RESERVED` |
| Claim pool/pending claim | Redis runtime có đường cứu về PostgreSQL |
| Claim time/revision gate | Fields trong Redis runtime hash, rebuild từ campaign/promotion PostgreSQL |
| Product deletion watermark | `catalog_product_tombstones`; chặn rule xuất hiện sau tombstone |
| Counter budget hot path | Redis projection có thể rebuild/repair từ PostgreSQL + reservation hợp lệ |

Không có `coupon_usages` hoặc bảng idempotency event tổng quát. Final command dùng typed
`promotion_command_receipts`; receipt cũng là durable trigger cho Redis finalize/repair qua
`redis_finalized_at`. Outbox chỉ lưu publication record và logical-event identity của
markdown snapshot. Kafka vẫn at-least-once; consumer dedupe delivery retry bằng stable
CloudEvents `id`.

## 5. Capabilities

| ID | Capability | Kết quả nghiệp vụ |
|---|---|---|
| `PROMO-CAP-01` | Quản trị campaign | Publish/pause/cancel và lịch dùng chung |
| `PROMO-CAP-02` | Quản trị voucher/markdown | Definition bất biến sau publish; thay đổi bằng campaign/child identity mới |
| `PROMO-CAP-03` | Tính discount/discovery | Eligibility, reason và một voucher toàn đơn |
| `PROMO-CAP-04` | Claim/save voucher | Atomic time/revision gate, gán duy nhất và snapshot `expired_at` |
| `PROMO-CAP-05` | Budget reservation | TRY/CANCEL runtime bằng Redis; confirm fence rồi PostgreSQL-first |
| `PROMO-CAP-06` | Technical compensation | PostgreSQL-first `cancel-confirmed`, sau đó repair Redis |
| `PROMO-CAP-07` | Markdown propagation | `ACTIVE` full rule hoặc `INACTIVE` tombstone, causal dedup + `source_revision` |
| `PROMO-CAP-08` | Recovery/reconciliation | Cứu claim, nhả reservation, rebuild schedule và sửa drift |

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose |
|---|---|---|---|
| Inbound | Admin qua Gateway | HTTP | Quản trị campaign/voucher/markdown |
| Inbound | Checkout | HTTP | Discovery, save và calculate discount |
| Inbound | Order/Temporal | HTTP | Reserve, confirm, cancel, `cancel-confirmed` |
| Inbound | Product | Kafka | `product-deleted.v1` để invalidate dangling markdown rule |
| Outbound | Product | Kafka/CDC | `product-markdown-snapshot.v1` |
| Runtime | PostgreSQL | SQL/WAL | Dữ liệu bền vững và transactional outbox |
| Runtime | Redis Cluster | Redis/Lua | Cache, claim, budget, reservation, schedule |
| Runtime | Debezium/Kafka Connect + Kafka | CDC/Kafka | At-least-once event delivery |

Promotion không consume Auth, Order hay Payment event và không phát coupon/wallet, budget,
reservation hay campaign lifecycle event ở v1. Không có consumer nào trong repository đăng
ký các fact đó; thêm sau là thay đổi additive theo
[`contracts/events/promotion/v1/README.md`](../../contracts/events/promotion/v1/README.md).

## 7. Critical invariants

- Một order tối đa một `PLATFORM_VOUCHER`.
- Voucher chỉ áp dụng cho toàn đơn; Promotion không tạo line allocation.
- Budget Redis không âm và `used + reserved` không vượt total hữu hạn.
- Không reserve ở Preview/Discovery.
- Một user tối đa một claim và một wallet row cho cùng promotion.
- Campaign claim window và wallet use window là hai khái niệm khác nhau.
- Limited claim Lua kiểm Redis time, enabled và campaign revision trước `SPOP`; API chỉ trả
  success sau guarded claim/wallet transaction đã commit PostgreSQL. Pending evidence cứu
  crash window và giữ acquisition time/revision.
- Published definition không mutate; `campaigns.revision` không phải definition version.
- Campaign kết thúc không tự xóa/thu hồi voucher đã lưu; eligibility chỉ dùng
  `wallet.expired_at` đã snapshot (với `CAMPAIGN_END`, expiry có thể đúng bằng end time).
- Refund partial/full không đổi wallet `USED` và không giảm used budget.
- `cancel-confirmed` kỹ thuật trả wallet về `SAVED` nếu còn hạn, ngược lại `EXPIRED`.
- Confirm và `cancel-confirmed` commit command receipt + wallet + durable budget trong một
  PostgreSQL transaction; Redis chỉ fence/finalize/repair theo từng flow và receipt
  `redis_finalized_at IS NULL` là durable trigger để chạy lại sau crash.
- Command receipt và outbox có trách nhiệm, retention độc lập; outbox chỉ chứa markdown
  snapshot.
- Markdown outbox dedup chứa causal campaign identity; `INACTIVE` không giả lập rule cũ.
- Product tombstone persist watermark rồi invalidate idempotently; markdown mutation dùng cùng
  product advisory lock nên không thể xuất hiện rule sau tombstone, và không phát event
  vòng ngược về Product đã xóa.
- `BUDGET_EXHAUSTED` là synchronous result/telemetry, không phải integration event.
- Redis schedule/counter không phải source of truth.
- Rule Engine thuần và xác định.

## 8. Document map

| Document | Nội dung |
|---|---|
| [Architecture](./architecture.md) | Thành phần, consistency, concurrency và outbox |
| [Domain](./domain.md) | Thuật ngữ, rule, invariant và state machine |
| [Interfaces](./interfaces.md) | Semantics HTTP/Kafka |
| [HTTP OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) | 24 operations Admin, Customer/Checkout và Order/Temporal |
| [Workflows](./workflows.md) | Happy path, failure, retry và recovery |
| [Data](./data.md) | PostgreSQL, Redis, constraint và lifecycle |
| [Operations](./operations.md) | Worker, alert, failure mode và runbook |
| [Development](./development.md) | Kỳ vọng implementation và test |
| [Decisions](./decisions/README.md) | ADR và lịch sử quyết định |

## 9. Known gaps

- Test suite hiện có: unit (Rule Engine, discount, phase, validity, identity filter), Lua trên
  Redis thật, PostgreSQL+Redis integration (publish/pause, save→reserve→confirm→cancel-confirmed,
  limited claim + Redis loss + sweeper, tombstone, clone), HTTP contract MockMvc và JSON Schema
  contract cho markdown snapshot. Chưa có: load test 10.000 contender, race test đa luồng cho
  confirm/cancel/sweep, E2E qua Debezium/Kafka thật, Temporal fixture.
- Order/Checkout chưa có client gọi Promotion; `/internal/v1/**` xác thực bằng header
  `X-Service-Identity` do mesh/ingress đặt (như Product), không phải Bearer workload JWT. Trong
  docker-compose chưa có mesh nên header này spoof được từ service network; production phải có
  mTLS/mesh trước khi coi là an toàn.
- Product validation đồng bộ dùng `GET /internal/v1/products/{id}/snapshot` với identity
  `promotion-service` (Product allowlist `reindex-clients`); tắt bằng
  `promotion.product.validation-enabled=false` khi không có Product.
- Welcome voucher không thuộc scope v1. Nếu cần, dùng lazy grant khi user gọi API wallet
  lần đầu hoặc admin `DIRECT_GRANT`; không consume Auth Kafka vì Auth delivery là
  best-effort và chưa có reconciliation.
- Promotion giữ `user_id` trong wallet/claim nhưng chưa consume
  `com.ecommerce.auth.user-deleted.v1`; retention/anonymization theo user cần ADR riêng
  trước khi production-ready.
- `promotion_rule_id` trong markdown snapshot là integer theo Product ADR-016, khác
  convention opaque ID của HTTP v1; đổi là breaking và phải đi v2.
- Chính sách partial refund và số refund transaction/order của Order/Payment chưa chốt;
  Promotion luôn giữ voucher `USED` và budget đã consume nên không phụ thuộc quyết định đó.
- Emergency revocation voucher đã lưu chưa được thiết kế; campaign end/pause/cancel không
  được ngầm dùng làm revocation.
- Retention mặc định: outbox 14 ngày (`promotion.outbox.retention`), command receipt 90 ngày
  (`promotion.receipt.retention`, không xóa receipt còn `redis_finalized_at IS NULL`); outbox
  trimmer chưa đọc CDC watermark từ connector nên chỉ trim theo thời gian. SLO/dashboard/alert
  chưa có.
- Gateway đã có middleware `rate-limit-promotion-discovery` (20 request/user/phút theo
  `X-User-Id`) và router cho admin/discovery/wallet trong docker-compose, nhưng rateLimit của
  Traefik CE là in-memory per replica; production nhiều replica cần shared state theo
  `docs/gateway/runtime-contract.md`. Promotion không sở hữu Redis rate-limit key.
- `RuleEngineDemo.java` chỉ minh họa AST; implementation production là
  `domain/rule/RuleEvaluator` + `RuleValidator` trong service.
