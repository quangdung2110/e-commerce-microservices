---
doc_id: promotion-overview
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
  - contracts/examples/events/promotion-product-markdown-snapshot.v1.json
verified_against_commit: null
---

# Promotion Service — Tổng quan

Promotion Service sở hữu chiến dịch khuyến mãi, voucher toàn sàn, ngân sách,
quyền sở hữu voucher của người dùng, ledger sử dụng voucher và lịch markdown.
Service là nơi duy nhất đánh giá điều kiện voucher và tính discount line; Checkout
chỉ điều phối và hiển thị kết quả.

## 1. Trạng thái service

| Thuộc tính | Trạng thái |
|---|---|
| Vòng đời service | `planned` — chưa có implementation trong repository |
| Runtime dự kiến | Java 21, Spring Boot 4 |
| Implementation path dự kiến | `java/services/promotion-service` — hiện chưa tồn tại |
| Độ tin cậy tài liệu | `proposed`; chưa verified với source code/migration |
| Contract adoption | `not_started`; các tên API/event hiện chỉ là legacy documented design |

Các ADR trong thư mục `decisions/` ghi quyết định đã chấp nhận ở mức thiết kế;
chúng không chứng minh rằng code production đã tồn tại.

## 2. Mục tiêu

- Cho admin tạo và vận hành campaign, voucher và markdown rule.
- Tính giảm giá nhất quán từ AST điều kiện đa hình mà không query database trong
  từng node rule.
- Bảo vệ quota và ngân sách tiền tệ khi nhiều người dùng submit đồng thời.
- Cấp voucher flash-sale với độ phức tạp O(1) trên Redis và ghi bền vững bất đồng bộ.
- Giữ audit trail cho redeem/refund và tự sửa drift giữa PostgreSQL với Redis.
- Công bố thay đổi markdown để Product tính giá bán, sau đó Search phục vụ read model.

## 3. Boundary

Promotion chịu trách nhiệm:

- `campaigns`, `promotions`, `promotion_budgets`, `coupon_claims`,
  `user_coupon_wallets`, `coupon_usages`, `promotion_event_idempotency` và
  `markdown_rules`.
- Rule Engine, stacking một slot `PLATFORM_VOUCHER`, discount waterfall và reason
  code eligibility.
- Claim/save voucher, reserve/confirm/cancel/cancel-confirmed/refund budget.
- Lịch kết thúc markdown, reservation expiry, pending-claim rescue và reconciliation.

Promotion không chịu trách nhiệm:

- Catalog, category membership, giá gốc hay giá bán công bố; Product sở hữu các dữ
  liệu đó và áp dụng markdown.
- Search/filter/ranking; Search sở hữu read model Elasticsearch.
- Cart snapshot, checkout token, Order/Payment state machine và hoàn tiền cho khách.
- Inventory hoặc shipping discount. Hệ thống không có seller/merchant; mọi promotion
  do admin tạo và quản trị.

Mỗi transaction chỉ bao phủ PostgreSQL của Promotion hoặc một lần thực thi Lua trên
Redis. Không có distributed transaction PostgreSQL–Redis–Kafka.

## 4. Data ownership

| Nhóm dữ liệu | Vai trò của Promotion | Source of truth |
|---|---|---|
| Campaign, voucher, rule AST, markdown schedule | Sở hữu và ghi | PostgreSQL Promotion |
| Tổng ngân sách và cấu hình quota | Sở hữu và ghi | `promotion_budgets` |
| Quyền claim và ví voucher | Sở hữu và ghi | `coupon_claims`, `user_coupon_wallets` |
| Redeem/refund ledger | Sở hữu và ghi | `coupon_usages` + idempotency gate |
| Reservation đang hiệu lực | Trạng thái runtime tạm thời | Redis reservation payload/index |
| Counter budget phục vụ hot path | Projection có thể rebuild | Redis; đối soát một chiều từ DB |
| Cart/user/category context | Chỉ nhận snapshot/identifier | Service nguồn tương ứng |

Service khác không được ghi trực tiếp database hay Redis keyspace của Promotion.

## 5. Capabilities

| ID | Capability | Kết quả nghiệp vụ |
|---|---|---|
| `PROMO-CAP-01` | Quản trị campaign/voucher/markdown | Admin công bố hoặc kết thúc chính sách giảm giá |
| `PROMO-CAP-02` | Đánh giá và tính discount | Trả eligibility, reason, discount line và rule version |
| `PROMO-CAP-03` | Voucher discovery | Liệt kê mã trong ví hoặc mã public theo đúng ngữ cảnh |
| `PROMO-CAP-04` | Claim/save voucher | Gán duy nhất một suất promotion cho người dùng |
| `PROMO-CAP-05` | Budget reservation | TRY/CONFIRM/CANCEL nguyên tử cho một voucher toàn sàn |
| `PROMO-CAP-06` | Refund/cancel-confirmed | Hoàn allocation đã redeem nhưng không tái sử dụng voucher |
| `PROMO-CAP-07` | Markdown propagation | Phát thay đổi rule để Product tính lại giá |
| `PROMO-CAP-08` | Recovery và reconciliation | Cứu claim, nhả reservation, kết thúc rule và sửa counter drift |

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Admin qua Gateway | HTTP | Quản trị campaign, voucher, markdown | Required | Chưa có OpenAPI |
| Inbound | Checkout | HTTP | Discovery và calculate discount | Required | Legacy documented |
| Inbound | Order/Temporal | HTTP | Reserve, confirm, cancel, compensation, refund | Required | Legacy documented |
| Inbound | Order | Kafka | `ORDER_REFUNDED` | Required cho lane async refund | Legacy documented |
| Inbound | Auth | Kafka | `USER_REGISTERED` để cấp welcome voucher | Optional | Compatibility adapter dự kiến |
| Inbound | Product | Kafka | Product chuyển `ACTIVE` để re-apply markdown | Optional trong thiết kế cũ | Legacy documented |
| Outbound | Product | Kafka | Markdown created/updated/ended | Required | Contract đích chưa được đồng bộ ở vòng này |
| Outbound | Notification/Analytics/UI | Kafka | Claim/redeem/cancel/refund/exhausted | Optional theo consumer | Chưa có AsyncAPI Promotion |
| Runtime | PostgreSQL | SQL | Dữ liệu bền vững và audit | Required | Chưa có migration |
| Runtime | Redis Cluster + RedisBloom | Redis/Lua | Snapshot, claim, budget, reservation, schedule, rate limit | Required | Key schema trong `data.md` |
| Runtime | Kafka | Kafka | Event và worker queue | Required | Direct-produce best-effort |

## 7. Critical invariants

- [`PROMO-INV-001`](./domain.md#5-invariants): một đơn tối đa một
  `PLATFORM_VOUCHER`.
- [`PROMO-INV-002`](./domain.md#5-invariants): budget không âm và
  `used + reserved` không vượt tổng.
- [`PROMO-INV-003`](./domain.md#5-invariants): reserve chỉ xảy ra sau Submit, không
  xảy ra ở Preview/Discovery.
- [`PROMO-INV-004`](./domain.md#5-invariants): một user tối đa một claim và một wallet
  row cho cùng promotion.
- [`PROMO-INV-005`](./domain.md#5-invariants): claim window khác use window.
- [`PROMO-INV-006`](./domain.md#5-invariants): refund không đổi wallet `USED` về
  `SAVED`.
- [`PROMO-INV-007`](./domain.md#5-invariants): DB ledger là nguồn chuẩn để sửa Redis;
  không ghi ngược PostgreSQL từ counter Redis.
- [`PROMO-INV-008`](./domain.md#5-invariants): Rule Engine thuần, xác định và không có
  side effect.

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Thành phần, consistency, concurrency và deployment | Khi đổi kiến trúc |
| [Domain](./domain.md) | Thuật ngữ, rule, invariant và state machine | Khi đổi business logic |
| [Interfaces](./interfaces.md) | Semantics HTTP/Kafka hiện được ghi nhận | Khi tích hợp; chưa phải contract chuẩn |
| [Workflows](./workflows.md) | Happy path, failure, retry và recovery | Khi sửa use case |
| [Data](./data.md) | PostgreSQL, Redis, TTL, constraint và retention | Khi sửa dữ liệu/cache |
| [Operations](./operations.md) | Worker, alert, failure mode và runbook | Khi deploy/vận hành |
| [Development](./development.md) | Kỳ vọng implementation và test | Khi bắt đầu code |
| [Decisions](./decisions/README.md) | Lý do của các lựa chọn khó đảo ngược | Khi review trade-off |

## 9. Known gaps

- Chưa có service code, migration, OpenAPI, AsyncAPI hay test của Promotion.
- Tài liệu cũ có một số tên/payload event khác contract đích hiện có của Product;
  việc thống nhất contract liên-service cố ý để dành cho lần refactor sau.
- Tài liệu cũ ghi `coupon_claims.owner_id` là `BIGINT` nhưng wallet/usage dùng UUID;
  canonical user identifier cho claim phải được chốt trong lần chuẩn hóa contract/schema.
- Discovery trả `promotion_budget_version` nhưng logical schema cũ chưa định nghĩa nơi
  lưu `budget_version_updated_at`; cần chốt trước implementation.
- `RuleEngineDemo.java` chỉ minh họa AST, chưa phải implementation production.
- Thiết kế gọi Redis là Cluster nhưng một số Lua script dùng key không cùng hash slot;
  topology/key-tag thực thi phải được chốt trước implementation.
- Direct produce sau DB commit có crash window làm mất event; đây là trade-off được
  chấp nhận ở thiết kế hiện tại.
- Health endpoint, biến cấu hình, SLO ngoài discovery latency, backup/restore và chính
  sách parking-lot/DLQ chưa có đặc tả triển khai.
