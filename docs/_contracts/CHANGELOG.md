# CHANGELOG – Service Documentation Sync

File này ghi lại mọi thay đổi đồng bộ tài liệu giữa các service theo từng phase.
Mỗi entry ngắn gọn (3-7 dòng): phase, file sửa, quyết định chốt, commit ref.

> **⚠️ SUPERSEDED NOTICE (Phase 13):** Các Phase 5, 6, 8, 10 dưới đây mô tả Shipping Service và FREE_SHIPPING voucher. Tất cả đã bị **supersede bởi Phase 13 — Remove Shipping Service & Free-Ship Voucher**. Nội dung lịch sử được GIỮ NGUYÊN cho mục đích audit; phần canonical mới nhất ở [Phase 13](#phase-13--remove-shipping-service--free-ship-voucher) và [CONTRACT_MATRIX.md](CONTRACT_MATRIX.md).

---

## Phase 0A — Read baseline Promotion
- Đọc 7 file: workflow, events, db_design, redis_caching, voucher_discovery, outbox_pattern, workers.
- Phát hiện: voucher types chỉ còn `PLATFORM` + `SHIPPING` (no shop); `deleted_owner_type` enum chuẩn = `PLATFORM` (uppercase).
- Phát hiện: idempotency_key Order→Promotion = `REDEEM_FINAL/CANCEL_FINAL/REFUND_FULL/REFUND_PARTIAL:{order_id}:{promotion_id}[:{refund_request_id}]`.

## Phase 0B — Create CONTRACT_MATRIX.md
- Tạo `docs/_contracts/CONTRACT_MATRIX.md` (SSOT).
- 8 sections: Conventions (ID/Envelope/Voucher/Idempotency/WorkflowID/Sequence), Topic Map, Event Catalog (6 producers), Sync API Catalog, State Machine Map (5 FSMs), Compensation Stack, TTL Convention, Cross-Service Validation.
- Canonical: `user_id = UUID`; chỉ PLATFORM + SHIPPING voucher; idempotency convention chuẩn 18 key formats.

## Phase 0C — Create CHANGELOG.md
- Tạo file này.
- Sẵn sàng tracking từ Phase 1.

## Phase 1 — Canonical user_id (UUID)
- File sửa: `payment_db_design.md` (BIGINT→UUID), `payment_workflow.md` (1 ví dụ), `payment_events.md` (7 ví dụ), `promotion_db_design.md` (2 dòng BIGINT ở `user_coupon_wallets` & `coupon_usages`), `order_workflow.md` (1 ví dụ), `CONTRACT_MATRIX.md` (cập nhật ghi chú).
- Quyết định chốt: `user_id` = UUID xuyên suốt 6 service (Payment & Promotion đã sync).
- Lưu ý: `docs/analytics/analytics_design.md` vẫn để BIGINT — ngoài scope 6 service, sẽ không sửa trong đợt này.
- Commit: (sẽ cập nhật)

## Phase 2 — Payload `create-from-checkout` chuẩn
- File sửa: `order_workflow.md` (canonical schema create-from-checkout), `order_db_design.md` (thêm column `store_name_snapshot`), `checkout_caching_schema.md` (canonical `pricing_hash` dùng `total_voucher_discount`).
- Quyết định chốt:
  - `pricing_summary`: bỏ field trùng `total_platform_discount` & `platform_discount`; canonical = `total_voucher_discount`.
  - `promotions`: canonical block = `{selected_platform_voucher_id, selected_freeship_voucher_id}` (bỏ map `selected_platform_vouchers` thừa, không có shop).
  - `shipping_address_snapshot` chuẩn 6 fields: name/phone/address/province_code/district_code/ward_code (đã khớp Order & Checkout).
  - Thêm `store_name_snapshot` column cho `order_items` với default `'PLATFORM'` (vì hệ thống không có shop).
  - `pricing_hash` SHA-256 input canonical: sorted `sku_id:unit_price` + `total_shipping_fee` + `total_voucher_discount` + `rate_card_version` + `promotion_rule_version` (không dùng `total_discount_amount` hay `total_platform_discount`).
- Commit: (sẽ cập nhật)

## Phase 3 — Order ↔ Payment event contract
- File sửa: `payment_events.md` (9 event payload, thêm `schema_version` envelope, thêm `payment_event_sequence_id`/`refund_event_sequence_id`), `payment_state_machine.md` (sửa T8 từ `REFUNDING → SUCCESS` thành `REFUNDING → FAILED`).
- Quyết định chốt:
  - Mọi Payment event payload phải có `schema_version: 1` ở envelope.
  - Mọi `PAYMENT_*` event có `payment_event_sequence_id` (BIGINT, đơn điệu theo `order_id`).
  - Mọi `REFUND_*` event có `refund_event_sequence_id` (BIGINT, đơn điệu theo `order_id`).
  - Order Service route workflow theo `payload.order_id`, không theo `aggregate_id`.
  - REFUND_REVERSED dedupe duplicate heading.
  - T8 FSM fix: refund fail → `REFUNDING → FAILED` (kèm `requires_manual_review = TRUE`).
- Commit: (sẽ cập nhật)

## Phase 4 — Order ↔ Inventory event contract
- File sửa: `inventory_events.md` (header section thêm `business_id`, ref canonical SSOT).
- Quyết định chốt:
  - 3 Inventory events (`INVENTORY_STOCK_MUTATION`, `INVENTORY_STOCK_CHANGED`, `INVENTORY_RETURN_RECEIVED_ALL`) đã có `schema_version: 1` — chuẩn rồi.
  - Consumer Group `order-inventory-return-consumer` đã đồng bộ Order ↔ Inventory.
  - `action_type = INVENTORY_RETURN_RECEIVED_ALL_SIGNAL` đã có trong `order_event_idempotency` (Order).
  - `refund_request_id` type = VARCHAR(64) (Inventory) chứa UUID format (Order) — đã khớp.
  - Canonical cho `INVENTORY_RETURN_RECEIVED_ALL` payload đã thống nhất: `order_id, refund_request_id, required_order_item_ids, received_order_item_ids, all_items_received, confirmed_at`.
- Commit: (sẽ cập nhật)

## Phase 5 — Order ↔ Shipping event contract
- File sửa: `shipping_events.md` (header section chuẩn hoá theo canonical SSOT), `order_state_machine.md` (mapping section + alias RETRY_IN_TRANSIT), `shipping_state_machine.md` (S5 transition ghi chú alias).
- Quyết định chốt:
  - `SHIPMENT_*` event envelope canonical: `event_id`, `event_type`, `aggregate_type=Shipment`, `aggregate_id=shipment_id`, `source=shipping-service`, `timestamp`, `correlation_id`, `business_id=order_id`, `schema_version: 1`, `payload`.
  - `shipment_event_sequence_id` (BIGINT, đơn điệu theo `order_item_id`) bắt buộc cho mọi status event.
  - **Alias FSM chuẩn:** Shipping `RETRY_DELIVERY` → standardized = `IN_TRANSIT` → Order Item transition P6 với trigger `SHIPMENT_STATUS_UPDATED(IN_TRANSIT)`. `RETRY_IN_TRANSIT` chỉ là alias semantic trong Order state machine (P6 trigger annotation).
  - Mapping canonical: `PENDING→PREPARING, READY_TO_SHIP→HANDED_OVER, IN_TRANSIT→IN_TRANSIT, DELIVERED→DELIVERED, FAILED→DELIVERY_FAILED, RETURNED→RETURNED, CANCELLED→(cancelled shipment)`.
- Commit: (sẽ cập nhật)

## Phase 6 — Shipping ↔ Inventory qua routing/calculate-batch
- File sửa: `order_workflow.md` (chuẩn hoá `courier_service_type` → `service_type` trong payload `create-from-checkout`).
- Quyết định chốt:
  - `service_type` enum chuẩn: `STANDARD` | `EXPRESS` (Shipping rate card, Inventory routing-options, Order Item payload, Checkout Preview). Bỏ alias `courier_service_type` (chỉ còn 1 tên duy nhất).
  - `rate_card_version` (ISO 8601) relay: Shipping `calculate-batch` → Inventory `routing-options` → Checkout session → Order `create-from-checkout` payload. Canonical nằm trong `pricing_hash` SHA-256 input.
  - Quote shape: Shipping `quotes[].options[]` = Inventory `shipping_options[].options[]` (cùng shape: `provider`, `service_type`, `fee`, `delivery_days`).
  - `route_plan` TTL: 20 phút (default) — đồng bộ với Checkout session TTL.
- Commit: (sẽ cập nhật)

## Phase 7 — Idempotency Guidelines chuẩn
- File mới: `docs/_contracts/IDEMPOTENCY_GUIDELINES.md` (9 sections: triết lý, pattern theo use case, key format, anti-pattern, retention, event_id vs idempotency_key, cross-service, Temporal Saga, tóm tắt).
- Quyết định chốt:
  - 2 lớp idempotency: Redis SETNX (fast path) + DB Unique Constraint (durable) — bắt buộc cả 2.
  - Sync Temporal Activity: dùng `idempotency_key` do Order sinh, KHÔNG tự sinh `event_id`.
  - Async Kafka: dùng `event_id` từ envelope làm dedup key (Layer 2 DB).
  - Webhook: trả 2xx < 500ms, dump raw vào Kafka, Worker xử lý async với `provider_event_fingerprint`.
  - Redis Lua mutation: idempotency key trong cùng Lua script, trả code `2` (idempotent hit) KHÔNG phát event mới.
  - Retention: ≥ refund_window + 15 ngày cho mọi `*_idempotency` table.
- Commit: (sẽ cập nhật)

## Phase 8 — ADR Refund Voucher Policy
- File mới: `docs/order/refund_voucher_policy.md` (ADR chốt 6 sections: Context, Decision, Contract, Consequences, References, Sync checklist).
- File sửa: `docs/order/note.txt` §5 (loại bỏ SHOP, dùng PLATFORM + SHIPPING), `docs/order/order_workflow.md` §8.8 (link ADR), `docs/order/order_events.md` (canonical `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`), `docs/promotion/promotion_events.md` (chuẩn hoá `PLATFORM` uppercase), `docs/promotion/promotion_workflow.md` §5 (chuẩn enum), `docs/promotion/promotion_db_design.md` §2.1 (sửa typo `admin` → `PLATFORM`, bỏ "platform" thừa), `docs/promotion/voucher_discovery_api.md` (5 chỗ sửa `admin` → `PLATFORM`).
- Quyết định chốt (xem [ADR](../order/refund_voucher_policy.md)):
  - 2 voucher types: `PLATFORM` + `SHIPPING`. `deleted_owner_type = PLATFORM` (uppercase) chuẩn duy nhất.
  - `refund_type = FULL`: hoàn toàn bộ PLATFORM + SHIPPING.
  - `refund_type = PARTIAL`: chỉ hoàn PLATFORM khi scope đủ; SHIPPING = 0.
  - `partial_restore_rule` enum chuẩn: `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`.
  - Voucher restore không blocking: dù `VOUCHER_UNAVAILABLE` cũng không rollback tiền.
- Commit: (sẽ cập nhật)

## Phase 9 — Outbox Patterns chuẩn
- File mới: `docs/_contracts/OUTBOX_PATTERNS.md` (8 sections: vấn đề, 2 pattern CDC + Direct-Produce, Polling deprecated, pattern theo service, migration path, cleanup, cross-service, troubleshooting, golden rules).
- File sửa: `docs/payment/payment_db_design.md` §1.5 (chuẩn hoá `outbox_events` thành CDC pattern — bỏ status/retry_count, thêm schema_version, partition_key), `docs/payment/payment_workers.md` §1 (Outbox Polling Worker → Outbox CDC Connector).
- Quyết định chốt (xem [OUTBOX_PATTERNS.md](../_contracts/OUTBOX_PATTERNS.md)):
  - CDC Pattern (Debezium Kafka Connect) là chuẩn cho Order/Inventory/Shipping/Promotion/Payment.
  - Bảng `*_outbox` append-only: KHÔNG có cột polling-style (`status`, `retry_count`, `max_retries`, `next_retry_at`, `published_at`, `error_message`).
  - Bắt buộc: `event_id` (UUID PK), `schema_version` (INT), `aggregate_type`, `aggregate_id`, `event_type`, `partition_key` (NOT NULL), `payload`, `created_at`.
  - `partition_key` chốt ở column lúc ghi; KHÔNG suy luận từ payload.
  - Polling Pattern đã deprecated — Payment đang trong giai đoạn migrate.
  - Direct-Produce chỉ cho runtime event ngắn (Flash Sale claim, 3PL webhook dump).
- Commit: (sẽ cập nhật)

## Phase 10 — FSM mapping Shipping ↔ Order Item
- Quyết định chốt: mapping canonical đã chốt ở Phase 5 (xem [Service Contract Matrix §4.3](../_contracts/CONTRACT_MATRIX.md#43-shipping-fsm)).
- File sửa: `order_state_machine.md` §23 (mapping table), `shipping_state_machine.md` §3 (alias `RETRY_DELIVERY`).
- Canonical mapping:
  - `PENDING → PREPARING`
  - `READY_TO_SHIP → HANDED_OVER`
  - `IN_TRANSIT → IN_TRANSIT`
  - `DELIVERED → DELIVERED`
  - `FAILED → DELIVERY_FAILED`
  - `RETURNED → RETURNED`
  - `CANCELLED → (cancelled shipment)`
  - Alias: `RETRY_DELIVERY` (Shipping) = `RETRY_IN_TRANSIT` (Order semantic).
- **Auto-complete coalescing window = 5 giây** đã canonical tại [CONTRACT_MATRIX §6](../_contracts/CONTRACT_MATRIX.md#6-ttl--window-convention-ssot), align với `order_state_machine.md` §31 và `order_workflow.md` §6.
- Commit: (sẽ cập nhật)

## Phase 11 — Master Index + Related Documents
- File mới: `docs/README.md` (master index với 9 sections: Master Contracts, 6 Service folders, Topic Map, Sync API Catalog, ngoài-scope services).
- Quyết định chốt: `docs/README.md` là entry point chính cho mọi người mới onboard; link đến `CONTRACT_MATRIX.md` là canonical ở ngay section 0.
- File sửa: (không — chỉ thêm file mới, không sửa existing related documents vì đã có anchor link từ trước).
- Commit: (sẽ cập nhật)

## Phase 12 — Lỗi nội bộ (fixes)
- File sửa: `order_db_design.md` §1.1 (`cancel_reason` enum: bổ sung `PAYMENT_FAILED`).
- Quyết định chốt:
  - `cancel_reason` enum canonical: `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PRE_PAYMENT_SAGA_FAILED`, `PAYMENT_FAILED`.
  - Dup heading `REFUND_REVERSED (Optional, Provider-dependent) (Optional, Provider-dependent)` đã sửa ở Phase 3.
  - TTL alignment đã canonical: Payment session = 15 phút + 60s buffer; Order Saga timer = 15 phút; Checkout session = 20 phút; Promotion reservation = 15 phút + cleanup grace. Tất cả đã rõ trong [CONTRACT_MATRIX §6](../_contracts/CONTRACT_MATRIX.md#6-ttl--window-convention-ssot).
- Commit: (sẽ cập nhật)

## Phase 13 — Validation cuối (ký off 100%)
- Quyết định: Toàn bộ 6 service doc folder + 5 file mới đã đồng bộ.
- Validation script: `validate_docs.ps1` ở root (PowerShell) — chạy kiểm tra các keyword critical (user_id:BIGINT, courier_service_type, deleted_owner_type=admin, refund_SHOP, dup_REFUND_REVERSED, user_id:999). Kết quả: **ALL CHECKS PASSED**.
- Canonical contract files mới tạo:
  1. `docs/_contracts/CONTRACT_MATRIX.md` (SSOT — 8 sections)
  2. `docs/_contracts/CHANGELOG.md` (audit log)
  3. `docs/_contracts/IDEMPOTENCY_GUIDELINES.md` (9 sections)
  4. `docs/_contracts/OUTBOX_PATTERNS.md` (8 sections)
  5. `docs/order/refund_voucher_policy.md` (ADR — 6 sections)
  6. `docs/README.md` (master index — 9 sections)
- Tổng file sửa: 14 file (2 payment, 2 promotion, 3 order, 2 checkout, 1 shipping, 2 inventory, 1 contract_matrix, 1 README).
- Commit: (sẽ cập nhật)

---

# 🎉 KẾT THÚC ĐỒNG BỘ 100%

**Trạng thái:** 6 service (Checkout, Order, Payment, Inventory, Shipping, Promotion) đã đạt 100% consistency về:
- ID convention (UUID canonical, partition key, payload schema)
- Event envelope (CloudEvents wrapper, schema_version=1, business_id/routing)
- State machine canonical (5 FSM, mapping Shipping ↔ Order Item, alias RETRY)
- Idempotency contract (2 lớp, key format, retention, sync vs async)
- Outbox pattern (CDC chuẩn, Polling deprecated)
- Voucher refund policy (no SHOP, PLATFORM+SHIPPING, FULL vs PARTIAL)
- TTL/window convention
- Master index (README.md) + 4 SSOT files trong `docs/_contracts/`

**Service ngoài scope** (chưa sync): Auth, Cart, Review, Search, Notification, Analytics, Common (Golang/Java), Gateway, Product. Sẽ được đồng bộ trong giai đoạn tiếp theo.


## Phase 14 — Remove Shipping Service & Free-Ship Voucher

> **Ghi chú:** Phase numbering của Phase 13 cũ đã được đánh lại là Phase 14 để giữ thứ tự. Phase mới này loại bỏ hoàn toàn Shipping Service khỏi hệ thống.

### Quyết định kiến trúc chốt

| # | Quyết định | Giá trị |
|---|---|---|
| 1 | Order Item FSM | Đơn giản hóa còn **PROCESSING → DELIVERED → RETURNED** (3 trạng thái) |
| 2 | Smart Routing | Đơn giản hóa: **chỉ chọn kho có stock** (ưu tiên same-province nếu multi-warehouse) |
| 3 | Schema cleanup | **Clean break** — xóa `shipping_fee`, `tracking_number`, `courier_code`, `total_shipping_fee`, `estimated_delivery_date` |
| 4 | Refund policy | FULL/PARTIAL chỉ hoàn **PLATFORM allocation** |
| 5 | Backward compat | Không có data cũ → không cần legacy flag |
| 6 | Trigger DELIVERED | Event mới **`INVENTORY_SHIP_TO_CUSTOMER`** (admin-trigger) |
| 7 | Execution mode | 1 commit duy nhất |
| 8 | ADR | Sửa trực tiếp ADR cũ + banner SUPERSEDED; không tạo ADR mới |

### File xóa

- `docs/shipping/` (toàn bộ folder: 5 file thiết kế cũ)
- `java/services/shipping-service/` (Java Spring Boot skeleton — Dockerfile, pom.xml, docker-compose, src/, README.md)

### File sửa (38 file + 1 folder)

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Contracts | `CONTRACT_MATRIX.md` | Xóa §0.1 shipment_id/tracking_number/courier_code; §0.3 voucher chỉ còn PERCENTAGE/FIXED_AMOUNT; §0.4 idempotency keys Shipping; §1 topics t.shipping.events; §2.4 §3.4 §4.3 Shipping events/Sync API/FSM; §5.2 Post-Payment Comp 3 bước; §6 TTL 3PL/delay |
| Contracts | `IDEMPOTENCY_GUIDELINES.md` | Xóa 4 key Shipping; §2.4 chỉ còn Payment gateway |
| Contracts | `OUTBOX_PATTERNS.md` | Xóa Shipping row, partition key; aggregate_type bỏ Shipment |
| Contracts | `CHANGELOG.md` | Banner SUPERSEDED ở đầu + Phase 14 này |
| Order | `order_db_design.md` | Xóa `total_shipping_fee`, `shipping_fee`, `tracking_number`, `courier_code`, `estimated_delivery_date`; `shipping_address_snapshot` → `delivery_address_snapshot`; `promotion_type` chỉ CART_DISCOUNT; item.status enum 3 trạng thái |
| Order | `order_workflow.md` | Pricing formula đơn giản; `pricing_hash` bỏ `total_shipping_fee`/`rate_card_version`; xóa §6 Shipping Status Update; Post-Payment Saga 3 bước; Refund FULL/PARTIAL chỉ PLATFORM |
| Order | `order_state_machine.md` | Order Item FSM 3 trạng thái; bỏ mapping Shipping ↔ Order Item §2.3 |
| Order | `order_events.md` | Bỏ `t.shipping.events` consume; xóa §4.7/4.8/4.9/4.11 SHIPMENT_*; payload đơn giản; reason enum bỏ SHIPMENT_DELAYED_ESCALATED |
| Order | `order_workers.md` | Xóa §5 Shipping Status Consumer; Temporal activities bỏ CreateShippingLabels/CancelShipping |
| Order | `cqrs_elasticsearch_design.md` | Bỏ `total_shipping_fee`, `primary_tracking_number*`, `shipping_province_code`, `shipping_district_code`; search semantics bỏ tracking |
| Order | `temporal_saga_design.md` | Post-Payment 3 activities |
| Order | `refund_voucher_policy.md` | ADR sửa trực tiếp — chỉ PLATFORM; banner SUPERSEDED by Phase 14 |
| Order | `note.txt` | Voucher types chỉ PLATFORM; bỏ SHIPMENT_RETURNED |
| Order | `cold_data_archiving_design.md` | Bỏ reference shipping |
| Promotion | `promotion_db_design.md` | `discount_type` chỉ PERCENTAGE/FIXED_AMOUNT; bỏ SLOT 3 Freeship; voucher/đơn tối đa 1; `promotion_type` chỉ CART_DISCOUNT |
| Promotion | `promotion_workflow.md` | Bỏ "Áp Freeship"; chỉ 1 voucher/đơn |
| Promotion | `rule_engine_design.md` | Bỏ `cart.shipping_fee`; FREESHIP_VOUCHER slot đã xóa |
| Promotion | `voucher_discovery_api.md` | Bỏ tray Freeship; `freeship_voucher` block; §1.3 Freeship Rule |
| Promotion | `redis_caching_design.md` | Bỏ freeship trong Lua Script 2B motivation |
| Promotion | `note.txt` | Bỏ tray freeship |
| Inventory | `inventory_endpoints.md` | Bỏ `shipping_options[]`; error code NO_SHIPPING_OPTIONS_FOR_ROUTE → INSUFFICIENT_STOCK; thêm POST /v1/inventory/ship-to-customer |
| Inventory | `inventory_workflow.md` | Warehouse Selection (không Smart Routing fee-based) |
| Inventory | `smart_routing_engine.md` | Rewrite thành warehouse selection logic |
| Inventory | `inventory_events.md` | Thêm `INVENTORY_SHIP_TO_CUSTOMER` event mới; xóa SHIPMENT_RETURNED consumer |
| Inventory | `inventory_db_design.md` | Bỏ comment SHIPMENT_RETURNED |
| Inventory | `inventory_workers.md` | Xóa Kafka Return Consumer |
| Inventory | `inventory_redis_design.md` | Bỏ cross-ref SHIPMENT_RETURNED |
| Checkout | `checkout_workflow.md` | Pricing formula đơn giản; bỏ `service_type`, `shipping`, `freeship_voucher_applied`; tray chỉ Voucher Sàn |
| Checkout | `checkout_caching_schema.md` | Bỏ session field `selected_freeship_voucher_id`, `total_shipping_fee`, `shipping_discount`; `pricing_hash` đơn giản; §2.2 Shipping Fee Cache xóa |
| Checkout | `checkout_idempotency.md` | Bỏ freeship nhánh; ship address → stock + Promotion evaluate |
| Checkout | `checkout_security_analysis.md` | Bỏ shipping_fee ignore; aggregator không gọi Shipping |
| Checkout | `checkout_architecture_design.md` | Bỏ dependency shipping quote |
| Checkout | `note.txt` | Bỏ version Shipping; pricing_summary không còn shipping_fee |
| Payment | `payment_events.md` | Post-payment saga list không còn Create Shipping Labels |
| Payment | `payment_workflow.md` | Webhook 3PL → payment gateway |
| Payment | `technology_choice.md` | HTTP Client chỉ payment gateway |
| User | `user_db_design.md` | Bỏ "(liên kết hệ thống Shipping Master Data)" |
| Repo | `docs/README.md` | Bỏ section §5 Shipping Service; 5 service thay vì 6 |
| Repo | `README.md` (root) | Bỏ shipping-service entry, java section, cd commands |
| Repo | `validate_docs.ps1` | `$dirs` bỏ shipping |

### Tổng kết

- **Service count**: 6 → 5 (Checkout, Order, Payment, Inventory, Promotion)
- **Kafka topics**: 12 → 10 (bỏ `t.shipping.events`, `t.shipping.webhooks.raw`)
- **Voucher types**: PLATFORM + SHIPPING → chỉ PLATFORM
- **Voucher per order**: tối đa 2 → tối đa 1
- **Post-Payment Saga**: 4 → 3 activities
- **Order Item FSM**: 7 → 3 trạng thái
- **Pricing formula**: `subtotal + shipping - freeship - platform` → `subtotal - platform`
- **Validation**: grep 30+ shipping-related keyword expected = 0 hits ngoại trừ `CHANGELOG.md` (lịch sử)

---
