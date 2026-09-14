---
doc_id: promotion-adr-016
service: promotion
status: accepted
date: 2026-09-13
decision_owners:
  - Promotion Service team
  - Product Service team
supersedes: null
amends:
  - ADR-013
  - ADR-014
evidence:
  - contracts/events/promotion/v1/asyncapi.yaml
  - contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json
  - docs/promotion/interfaces.md
  - docs/promotion/data.md
---

# ADR-016 — Phạm vi integration event v1 và finalizer trigger bằng command receipt

## Status

`Accepted`; implemented trong `java/services/promotion-service`. Không supersede ADR nào. Amend
[ADR-013](./ADR-013-command-receipts-and-durable-finalization.md) ở điểm "outbox event là
durable trigger cho finalizer/repair" và amend
[ADR-014](./ADR-014-claim-and-markdown-consistency.md) ở điểm coupon fact/Auth consumer
tùy chọn. Transactional outbox + CDC, causal markdown dedup, claim boundary và
PostgreSQL-first finalization vẫn giữ nguyên.

## Context

Tài liệu Promotion liệt kê ba coupon fact (claimed/saved, redeemed, technical-cancelled)
cho Notification/Analytics và một Auth "user-registered" consumer cho welcome voucher.
Rà soát consumer-side cho thấy không có service nào đăng ký các fact này: Notification chỉ
tham chiếu topic marketing legacy `t.promotion.events`, Analytics lấy dữ liệu voucher từ
order snapshot trên `t.order.events`, Order/Checkout dùng HTTP đồng bộ. Auth không có event
"user-registered"; `user-verified-success.v1` chỉ dành cho User Service và Auth delivery là
best-effort không outbox. Promotion cũng chưa có AsyncAPI riêng: markdown snapshot do
Promotion phát nhưng contract sống trong Product AsyncAPI.

ADR-013 dùng outbox row của Confirm/`cancel-confirmed` làm durable trigger để Redis
finalizer/repair chạy lại sau crash. Nếu bỏ coupon event thì không còn outbox row để làm
trigger, trong khi crash window giữa PostgreSQL commit và Redis finalize vẫn tồn tại.

## Decision

### Phạm vi event v1

- Promotion sở hữu `contracts/events/promotion/v1/`. Message phát duy nhất là
  `com.ecommerce.promotion.product-markdown-snapshot.v1` trên `t.promotion.markdown.events`;
  schema chuyển từ `contracts/events/product/v1/schemas/` sang thư mục Promotion, file cũ
  giữ làm `$ref` shim, Product AsyncAPI tham chiếu message qua `$ref`.
- Message nhận duy nhất là `com.ecommerce.catalog.product-deleted.v1`.
- Không có coupon saved/redeemed/technical-cancelled, budget, reservation, campaign
  lifecycle hay refund event. Không consume Auth, Order hay Payment event. Claim
  persistence, confirm và `cancel-confirmed` không insert outbox.
- Welcome voucher không thuộc scope v1; nếu cần, dùng lazy grant khi user gọi API wallet
  lần đầu hoặc admin `DIRECT_GRANT`. `user-deleted.v1` là known gap cần ADR retention/
  anonymization riêng.
- Thêm message type mới khi có consumer thật là thay đổi additive; đổi payload/key/ordering
  của markdown snapshot là breaking và đi v2.

### Finalizer trigger bằng command receipt

- `promotion_command_receipts` thêm `redis_finalized_at TIMESTAMPTZ NULL` và partial index
  `(completed_at) WHERE redis_finalized_at IS NULL`. Cột luôn `NULL` khi transaction final
  command commit.
- Sau commit, application chạy inline Redis finalize (`CONFIRM`: matching `CONFIRMING`
  reserved→used) hoặc projection repair (`CANCEL_CONFIRMED`: DB→Redis) rồi set
  `redis_finalized_at` bằng `UPDATE ... WHERE redis_finalized_at IS NULL`.
- Finalizer worker quét receipt còn `NULL` quá ngưỡng và chạy lại cùng bước; Lua trả
  already-finalized hoặc thiếu runtime evidence thì reconcile từ DB, không increment/
  decrement mù, và chỉ set marker sau khi Redis hội tụ.
- Outbox chỉ dành cho integration event có consumer; không dùng làm cơ chế điều phối nội
  bộ. Receipt trimmer không xóa receipt còn `redis_finalized_at IS NULL`.

## Alternatives considered

- Giữ coupon event và tự consume qua Kafka làm trigger: thêm topic/schema/consumer group
  chỉ để Promotion tự nghe mình, phụ thuộc CDC lag cho hội tụ Redis, trộn trách nhiệm outbox.
- Không thêm cột; worker quét reservation `CONFIRMING` trong Redis rồi tra receipt: ít
  schema nhưng phụ thuộc Redis còn payload, trái nguyên tắc Redis không phải truth và không
  bao phủ `CANCEL_CONFIRMED`.
- Bảng finalize-task riêng: tách được retention nhưng thêm bảng/transaction cho một bit
  trạng thái; cột trên receipt đủ và đã cùng transaction.
- Phát coupon event "để sẵn": tạo contract không ai kiểm thử, dễ rot; consumer tương lai có
  thể cần shape khác.

## Consequences

Bề mặt tích hợp v1 giảm còn một topic phát và một message nhận; không cần Auth fixture,
coupon topic/ACL hay connector route thêm. Redis finalize/repair hội tụ không phụ thuộc
Debezium. Đổi lại receipt có thêm một cột và worker quét riêng; Notification/Analytics muốn
coupon fact sau này phải đăng ký consumer và thêm message additive. Product ADR-016 shape
(`promotion_rule_id` integer, discount integer VND) giữ nguyên nên contract khác convention
Money/opaque ID của HTTP v1 cho tới v2.

## Migration impact

Tạo `contracts/events/promotion/v1/` (AsyncAPI, README, common + snapshot schema), shim ở
Product, `$ref` trong Product AsyncAPI, cập nhật example `dataschema`, catalog
`interactions.yaml`/`services.yaml`, `ADOPTION.md`, `CHANGELOG.md`. Baseline migration thêm
`redis_finalized_at` + partial index. Tài liệu Promotion (README, architecture, domain,
interfaces, workflows, data, operations, development), `docs/README.md` và
`docs/notification` bỏ tham chiếu coupon/Auth event. Chưa có runtime nên không cần
rollback dữ liệu.

## Verification

- `pwsh ./contracts/tools/validate-contracts.ps1` pass: example markdown trỏ `dataschema`
  mới, shim `$ref` resolve, không còn `markdown_discount` mồ côi.
- Contract test Product deserialize fixture từ vị trí mới; Promotion consumer test cho
  `product-deleted.v1` duplicate/out-of-order.
- Test crash sau commit trước finalize, crash sau finalize trước set marker, duplicate worker
  cùng receipt, `CANCEL_CONFIRMED` với Redis unavailable rồi hội tụ; chứng minh
  `redis_finalized_at` chỉ set sau hội tụ và trimmer bỏ qua receipt còn `NULL`.
- Negative test: confirm/`cancel-confirmed`/claim persistence không tạo outbox row; không
  có topic coupon/budget trong config.
