---
doc_id: promotion-overview
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
  - contracts/events/product/v1/schemas/product-markdown-snapshot.schema.json
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
| Vòng đời service | `planned` — chưa có implementation trong repository |
| Runtime dự kiến | Java 21, Spring Boot 4 |
| Implementation path dự kiến | `java/services/promotion-service` — hiện chưa tồn tại |
| Độ tin cậy tài liệu | `proposed`; chưa verified với source code/migration |
| Contract adoption | Markdown snapshot v1 đã có contract đích; HTTP Promotion chưa có OpenAPI |

Các ADR trong `decisions/` là quyết định thiết kế, không phải bằng chứng code production.

## 2. Mục tiêu

- Cho admin tạo và vận hành campaign, voucher và markdown product-scope.
- Tách đúng vòng đời campaign, claim voucher và hạn sử dụng voucher đã lưu.
- Đánh giá rule AST xác định, không query database/network trong từng node.
- Bảo vệ quota/ngân sách khi nhiều người dùng claim hoặc submit đồng thời.
- Kích hoạt/kết thúc markdown không sớm, tự phục hồi khi Redis/worker lỗi và phát event
  bền vững qua transactional outbox + CDC.
- Giữ PostgreSQL là source of truth và tự sửa drift của Redis hot state.

## 3. Boundary

Promotion chịu trách nhiệm:

- `campaigns`, `promotions`, `promotion_budgets`, `coupon_claims`,
  `user_coupon_wallets`, `markdown_rules` và `promotion_outbox_events`.
- Một slot `PLATFORM_VOUCHER` cho toàn đơn, Rule Engine, eligibility và discount.
- Claim/save, reserve, confirm, cancel và `cancel-confirmed` do lỗi kỹ thuật.
- Campaign schedule, pending-claim rescue, reservation expiry và reconciliation.
- Full product markdown snapshot cho Product.

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
| Integration event | `promotion_outbox_events` trong cùng transaction với business state |
| Reservation đang hiệu lực | Redis runtime; không persist `RESERVED` |
| Claim pool/pending claim | Redis runtime có đường cứu về PostgreSQL |
| Counter budget hot path | Redis projection có thể rebuild/repair từ PostgreSQL + reservation hợp lệ |

Không có `coupon_usages` hoặc `promotion_event_idempotency`. Correctness dùng wallet,
guarded transition, unique constraint, deterministic key và outbox dedup key.

## 5. Capabilities

| ID | Capability | Kết quả nghiệp vụ |
|---|---|---|
| `PROMO-CAP-01` | Quản trị campaign | Publish/pause/cancel và lịch dùng chung |
| `PROMO-CAP-02` | Quản trị voucher/markdown | Cấu hình bất biến sau publish hoặc tạo revision mới |
| `PROMO-CAP-03` | Tính discount/discovery | Eligibility, reason và một voucher toàn đơn |
| `PROMO-CAP-04` | Claim/save voucher | Gán duy nhất một claim cho user, snapshot `expired_at` |
| `PROMO-CAP-05` | Budget reservation | TRY/CONFIRM/CANCEL nguyên tử bằng Redis Lua |
| `PROMO-CAP-06` | Technical compensation | `cancel-confirmed` trả wallet về `SAVED` nếu còn hạn |
| `PROMO-CAP-07` | Markdown propagation | Full snapshot `ACTIVE/INACTIVE` có `source_revision` |
| `PROMO-CAP-08` | Recovery/reconciliation | Cứu claim, nhả reservation, rebuild schedule và sửa drift |

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose |
|---|---|---|---|
| Inbound | Admin qua Gateway | HTTP | Quản trị campaign/voucher/markdown |
| Inbound | Checkout | HTTP | Discovery, save và calculate discount |
| Inbound | Order/Temporal | HTTP | Reserve, confirm, cancel, `cancel-confirmed` |
| Inbound | Auth | Kafka | Welcome voucher tùy chọn |
| Outbound | Product | Kafka/CDC | `product-markdown-snapshot.v1` |
| Outbound | Notification/Analytics | Kafka/CDC | Claim/redeem/cancel facts nếu có consumer |
| Runtime | PostgreSQL | SQL/WAL | Dữ liệu bền vững và transactional outbox |
| Runtime | Redis Cluster + RedisBloom | Redis/Lua | Cache, claim, budget, reservation, schedule |
| Runtime | Debezium/Kafka Connect + Kafka | CDC/Kafka | At-least-once event delivery |

## 7. Critical invariants

- Một order tối đa một `PLATFORM_VOUCHER`.
- Voucher chỉ áp dụng cho toàn đơn; Promotion không tạo line allocation.
- Budget Redis không âm và `used + reserved` không vượt total hữu hạn.
- Không reserve ở Preview/Discovery.
- Một user tối đa một claim và một wallet row cho cùng promotion.
- Campaign claim window và wallet use window là hai khái niệm khác nhau.
- Campaign kết thúc không tự xóa/thu hồi voucher đã lưu; eligibility chỉ dùng
  `wallet.expired_at` đã snapshot (với `CAMPAIGN_END`, expiry có thể đúng bằng end time).
- Refund partial/full không đổi wallet `USED` và không giảm used budget.
- `cancel-confirmed` kỹ thuật trả wallet về `SAVED` nếu còn hạn, ngược lại `EXPIRED`.
- Redis schedule/counter không phải source of truth.
- Rule Engine thuần và xác định.

## 8. Document map

| Document | Nội dung |
|---|---|
| [Architecture](./architecture.md) | Thành phần, consistency, concurrency và outbox |
| [Domain](./domain.md) | Thuật ngữ, rule, invariant và state machine |
| [Interfaces](./interfaces.md) | Semantics HTTP/Kafka |
| [Workflows](./workflows.md) | Happy path, failure, retry và recovery |
| [Data](./data.md) | PostgreSQL, Redis, constraint và lifecycle |
| [Operations](./operations.md) | Worker, alert, failure mode và runbook |
| [Development](./development.md) | Kỳ vọng implementation và test |
| [Decisions](./decisions/README.md) | ADR và lịch sử quyết định |

## 9. Known gaps

- Chưa có service code, migration, Promotion OpenAPI/AsyncAPI hoặc test.
- Chính sách partial refund và số refund transaction/order của Order/Payment chưa chốt;
  Promotion luôn giữ voucher `USED` và budget đã consume nên không phụ thuộc quyết định đó.
- Emergency revocation voucher đã lưu chưa được thiết kế; campaign end/pause/cancel không
  được ngầm dùng làm revocation.
- Health/config/SLO, outbox retention watermark và parking-lot chi tiết cần được chốt khi
  triển khai.
- `RuleEngineDemo.java` chỉ minh họa AST, không phải implementation production.
