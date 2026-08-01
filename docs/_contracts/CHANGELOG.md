# CHANGELOG – Service Documentation Sync

File này ghi lại mọi thay đổi đồng bộ tài liệu giữa các service theo từng phase.
Mỗi entry ngắn gọn (3-7 dòng): phase, file sửa, quyết định chốt, commit ref.

## Phase 23 — Remove Voucher Restore on Refund

**Status:** ACCEPTED (2026-06-27)
**Scope:** Promotion Service — không còn restore `USED → SAVED` khi xử lý `ORDER_REFUNDED`.

**Quyết định chốt:**
- Promotion **không** restore voucher khi refund — voucher luôn giữ `USED`.
- Chỉ hoàn `promotion_budgets.used_*` và cập nhật `coupon_usages` → `REFUNDED`.
- `restore_outcome` mặc định: `RETAINED_USED`; trường hợp row ví không tồn tại: `VOUCHER_UNAVAILABLE`.
- Các giá trị `RESTORED`, `EXPIRED_AT_RESTORE_TIME`, `SKIPPED_SCOPE_RULE` không còn được dùng.

**Files sửa:**
| Service | File | Thay đổi |
|---------|------|----------|
| Promotion | `docs/promotion/promotion_workflow.md` | Luồng 5: xóa restore wallet, giữ `USED` |
| Promotion | `docs/promotion/promotion_events.md` | §2.7 + §3.1: cập nhật restore outcome & wallet validity |
| Promotion | `docs/promotion/voucher_discovery_api.md` | §4.2: cập nhật state diagram |
| Order | `docs/order/refund_voucher_policy.md` | §1.2 + §2.2: policy mới — không restore |
| Order | `docs/order/order_events.md` | §3.6: cập nhật wallet validity contract |
| Order | `docs/order/order_workflow.md` | §8.7: cập nhật summary |
| Order | `docs/order/temporal_saga_design.md` | §2.5 step 11: cập nhật |
| Contracts | `docs/_contracts/CONTRACT_MATRIX.md` | `voucher_restore_mode` note |
| README | `docs/README.md` | Cập nhật mô tả |

---

## Phase 22b — Search Service — Remove DLQ

**Status:** ACCEPTED (2026-06-22)
**Scope:** Search Service đơn giản hoá error handling — bỏ hoàn toàn DLQ topic + DLQ processor. Áp dụng cho Search Service (Read Model thuần túy, single-admin, ít downstream consumers).

**Quyết định chốt:**

- **Bỏ topic `t.search.dlq` + consumer group `search-dlq-processor`** — Search Service không còn DLQ infrastructure.
- **Mọi lỗi không tự hồi phục xử lý bằng `log error + metric + skip + ACK`** (Phase 22 chung với Order Service về triết lý đơn giản hoá).
- **Giữ nguyên retry cho transient errors** (ES timeout/429/503/network) — nack event → Kafka tự retry theo policy `delivery.timeout.ms` (mặc định 5 phút).

**Lý do:**

- **Single-admin system:** Hệ thống chỉ có 1 admin duy nhất (toàn quyền, không bao giờ bị ban). Search Service không subscribe `t.admin.events`, không lưu field admin nào trên document → không có scenario admin tạo event poison.
- **Schema validation ở producer:** Payload đã được CDC + Debezium đảm bảo ở producer (PostgreSQL WAL stream). Search Service consumer chỉ cần `@JsonIgnoreProperties(ignoreUnknown = true)`.
- **Manual recovery đủ dùng:** `document_missing_exception` (partial update miss) rất hiếm; nếu xảy ra → log + metric là đủ để vận hành xử lý thủ công hoặc chạy Full Reindex Job.
- **Mapping conflict / schema poison:** Log + metric, không tự retry vô hạn (tránh poison pill loop).
- **Số lượng miss đủ nhỏ:** Trade-off chấp nhận mất automatic recovery cho partial update miss, đánh đổi bằng đơn giản hoá infrastructure (1 consumer group, 0 DLQ topic, 0 DLQ processor).

**Các loại lỗi & cách xử lý:**

| Loại lỗi | Xử lý |
|---|---|
| Transient (ES timeout / 429 / 503 / network) | Nack + Kafka retry theo `delivery.timeout.ms`. Không commit offset. |
| `document_missing_exception` cho partial update | Log + metric `search_document_missing_total{topic,event_type,doc_id}` + skip + ACK. Không tự fetch full product. |
| Mapping conflict / schema poison | Log + metric `search_payload_invalid_total{topic,event_type}` + skip + ACK. |
| Category lookup miss (PRODUCT_CREATED / PRODUCT_UPDATED / SKU_CREATED) | Skip enrichment (không ghi `category_name`/`category_path`) + log + metric `search_category_enrichment_miss_total{topic,event_type,category_id}` + ACK. `CATEGORY_UPDATED`/`CATEGORY_CREATED` sau đó populate lại. |
| Cluster ES down | Log liên tục + metric tăng theo thời gian. Dashboard alert; vận hành restart consumer / fix cluster. Không tự retry vô hạn. |

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Contracts | `CHANGELOG.md` | Thêm Phase 22b này |
| Search | `search_architecture_recommendations.md` | §7: cross-ref Phase 22b (group consumer chính, không DLQ); §8: chi tiết Phase 22b decision; §Tổng Kết: "Không có DLQ (Phase 22b)" |
| Search | `search_events.md` | §1: bỏ row "search-dlq-processor" trong topic map; §4.3: thay đổi "Xử Lý Lỗi" table thành "Phase 22b — Không có DLQ" |
| Search | `search_workers.md` | Tổng Hợp Workers: bỏ row "search-dlq-processor"; §1 Product Indexing Worker: ghi chú "Không có DLQ" trong phần error handling |
| Search | `search_workflow.md` | Error codes table: bỏ `ELASTICSEARCH_DLQ_*` (không tồn tại) |
| Search | `search_elasticsearch_schema.md` | Banner top: "Phase 22b: Bỏ hoàn toàn DLQ" |

**Tổng kết Phase 22b:**

- **Topics dropped:** −1 (`t.search.dlq`)
- **Consumer groups dropped:** −1 (`search-dlq-processor`)
- **Infrastructure đơn giản hoá:** Search Service giờ chỉ có 1 consumer group chính + 1 group tạm (reindex) + 0 DLQ.
- **Trade-off chấp nhận:** Mất automatic recovery cho partial update miss. Số lượng miss đủ nhỏ → manual recovery hoặc Full Reindex Job.
- **Files affected:** 5 file Search + 1 CHANGELOG.

---

## Phase 22 — Simplify Idempotency Schema (Order)

**Status:** ACCEPTED (2026-06-22)
**Scope:** Bỏ toàn bộ bảng idempotency của Order Service (`order_request_idempotency`, `order_event_idempotency`) để giảm migration/maintenance overhead. Chuyển guard sang cột inline trên `orders` + Redis SETNX.

**Quyết định chốt:**

- **DROP TABLE `order_request_idempotency`** — Toàn bộ idempotency sync API + one-shot guard chuyển sang cơ chế khác (xem dưới). Bảng này trước đây gộp 2 mục đích (replay response + first-write-wins guard cho Buyer return), nay tách rõ để có thể bỏ bảng.
- **DROP TABLE `order_event_idempotency`** — Toàn bộ durable dedup cho Kafka consumer + Temporal signal chuyển sang Redis SETNX. CloudEvents `event_id` envelope + FSM row-level lock đã đủ làm guard chính ở tầng app.
- **Thêm cột `orders.buyer_return_locked_at TIMESTAMPTZ NULL`** — Thay thế one-shot guard Buyer return: `UPDATE orders SET buyer_return_locked_at=NOW() WHERE id=:id AND buyer_return_locked_at IS NULL RETURNING id` (CAS row-level lock). Nếu `RETURNING` không trả row → 409 `REFUND_CUSTOMER_REQUEST_ALREADY_USED`. Guard vẫn giữ nguyên semantics: không bị reset khi refund fail/reversed, chỉ áp cho trigger `API_RETURN`, không áp cho admin lane.
- **API idempotency mới** (CREATE_ORDER, CANCEL_ORDER, REQUEST_RETURN, REQUEST_ADMIN_REFUND):
  - `CREATE_ORDER`: Temporal WorkflowID `order-checkout-{checkout_token}` đã dedup (canonical contract từ trước). Redis SETNX `order:req:create:{checkout_token}` TTL 7 ngày làm fast-path.
  - `CANCEL_ORDER`, `REQUEST_RETURN`, `REQUEST_ADMIN_REFUND`: WorkflowID `order-refund-{order_id}` + Redis SETNX TTL 7 ngày. Mất lớp replay response cross-instance (chấp nhận được cho project cá nhân).
- **Kafka consumer dedup mới** (Payment Status, Inventory Ship, Inventory Return):
  - Payment Status: Redis SETNX `payment:signal:{order_id}:{txn_id}:{event_type}` TTL 30 ngày.
  - Inventory Ship: Redis SETNX `inventory:ship:applied:{order_id}:{warehouse_id}:{shipped_at}` TTL 30 ngày.
  - Inventory Return: Redis SETNX `inventory:return:confirm:{order_id}:{refund_request_id}` TTL 30 ngày.
  - Idempotency ở tầng DB vẫn đảm bảo nhờ FSM CAS (`UPDATE ... WHERE status='OLD_STATUS'` + row-level lock) + Auto-Complete CAS cho master order.
- **Outbox duplicate risk mitigation**: Trong trường hợp Redis mất key khi consumer xử lý, cùng một event có thể insert outbox 2 lần. Notification consumer đã có **contract bắt buộc** dedup theo `(order_item_id, last_event_sequence_id)` cho `ORDER_ITEM_STATUS_CHANGED` và theo `(order_id, last_event_sequence_id)` cho `ORDER_COMPLETED` (xem `order_events.md` §2.1 + §3.5 + §3.7). Không cần thêm unique constraint trên `outbox_events`.

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Contracts | `CHANGELOG.md` | Thêm Phase 22 này |
| Contracts | `CONTRACT_MATRIX.md` | §3 idempotency table: 2 row Order đổi sang Redis SETNX; §4 routing notes (line 123, 252): đổi reference; §retention row "Order idempotency 45d" xóa |
| Contracts | `IDEMPOTENCY_GUIDELINES.md` | §3.1 row Order cancel: DB row → Redis SETNX 7d; §3.3 row Order event idempotency: DB → Redis SETNX 30d; §5 retention: xóa 2 row Order |
| Order | `order_db_design.md` | §1.1 `orders`: thêm `buyer_return_locked_at TIMESTAMPTZ NULL`; §2.0: cập nhật note guard; **XÓA §2.2** (toàn bộ `order_request_idempotency` + §3.2.1 cũ + backfill note); **XÓA §3.3** (toàn bộ `order_event_idempotency`); renumber `§3.3.2 → §3.1` (`order_refund_cases`, thêm §3 header mới); thêm §3.1 backfill SQL cho `buyer_return_locked_at` |
| Order | `order_workflow.md` | §3 bước 2: bỏ "durable path DB", chỉ giữ Redis SETNX 7d; §3.1 bước 3 + 5: rewrite one-shot guard dùng cột `buyer_return_locked_at`; §4.1 bước 2: bỏ DB idempotency (WorkflowID đã dedup); §6 bước 3: bỏ reference `order_event_idempotency`, đổi thành CAS row-level lock |
| Order | `order_workers.md` | §3 (Payment Status Consumer) bước 4 + Idempotency: bỏ DB durable guard, chỉ Redis SETNX 30d; §4 (Inventory Ship Consumer) Idempotency: tương tự; §5.1 (Inventory Return Consumer) bước 2: tương tự |
| Order | `order_events.md` | §2.1 Guard keys table: cập nhật note consumer guard (Redis SETNX + FSM lock); §3.6 line 290: đổi one-shot guard reference; §4 routing rule line 64 + 418: đổi reference; §5 line 640: rewrite idempotency contract |
| Order | `order_state_machine.md` | §3.1 (One-shot Buyer API): đổi guard reference từ `order_request_idempotency` → cột `orders.buyer_return_locked_at` |
| Order | `temporal_saga_design.md` | §2.2 line 54 + §4 line 150: đổi one-shot guard reference |

**Tổng kết:**

- **Tables dropped:** −2 (`order_request_idempotency`, `order_event_idempotency`).
- **Indexes dropped:** −3 (`uq_order_request_idempotency_resource_action`, `uq_order_event_idempotency_scope`, `uq_order_event_idempotency_aggregate_scope`).
- **Columns added:** +1 (`orders.buyer_return_locked_at`).
- **Cleanup CronJob:** 1 (`order_event_idempotency` 45d) → 0 (Redis tự expire).
- **Tables tổng (Order Service):** 8 → 7 (sau Phase 21).
- **Idempotency tầng lưu trữ:** 2 PostgreSQL tables → 1 cột inline + 1 Redis namespace (multi-key SETNX).
- **Files affected:** 9 file (7 Order + 2 Contracts).
- **Trade-off chấp nhận**: (a) Mất khả năng replay response API khi Redis evict sau 7 ngày — chấp nhận vì Temporal WorkflowID đã dedup phần lớn case. (b) Duplicate outbox event có thể xảy ra nếu Redis cluster mất dữ liệu — mitigated bởi Notification consumer dedup contract đã có sẵn. (c) One-shot guard Buyer return giờ dựa vào column trên `orders` thay vì row trong bảng riêng — vẫn đảm bảo invariant nghiệp vụ vì guard sống cùng vòng đời order.

---

## Phase 21 — Order Service Schema Simplification

**Status:** ACCEPTED (2026-06-22)
**Scope:** Tinh giản schema Order Service sau khi xác nhận các bảng/column/event không còn cần thiết cho Search Service.

**Quyết định chốt:**

- **DROP TABLE `order_product_metrics`** — Bảng aggregate này chỉ phục vụ worker `order-metrics-projector` phát `ORDER_PRODUCT_METRICS_UPDATED` lên `t.search.metrics.events` cho Search Service đồng bộ `product_sold_count`. Search Service giờ **không cần** metric này — sort `best_selling` đã bỏ khỏi API public.
- **DROP COLUMN `order_items.store_name_snapshot`** — Hệ thống không có SHOP/SELLER, giá trị luôn = `'PLATFORM'` (default). `primary_store_name` derive constant ở runtime trong Search/Filter SQL.
- **DROP COLUMNS `order_promotions.promotion_type` + `order_promotions.admin_funding`** — Sau Phase 14 chỉ còn PLATFORM allocation; `promotion_type` chỉ có giá trị `CART_DISCOUNT` duy nhất; `admin_funding` luôn = `discount_amount` ở PLATFORM-only system.
- **DROP event `ORDER_PRODUCT_METRICS_UPDATED` + topic `t.search.metrics.events`** — Search Service không còn consumer.
- **Thu gọn `promotion_refund_allocations[]` payload** từ 3 field xuống **2 field** (`promotion_id`, `refunded_discount_amount`) — bỏ `admin_funding_refund` vì luôn = `refunded_discount_amount`.
- **Bỏ field `product_sold_count` khỏi ES mapping + Search API response** + bỏ `product_sold_count_30d` khỏi `search_suggestions` index.
- **Bỏ sort `best_selling`** khỏi Search Service public API.
- **Gộp Search Service consumer groups: 3 → 2** (bỏ `search-metrics-indexer` cho sales metrics; giữ `search-product-indexer` + `search-dlq-processor`).

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Contracts | `CHANGELOG.md` | Thêm Phase 21 này |
| Contracts | `CONTRACT_MATRIX.md` | §0.3: xóa mapping table `order_promotions.promotion_type`; §0.6: xóa row "Order Metrics (CQRS)"; §1: xóa row `t.search.metrics.events`; §2.1: xóa block `ORDER_PRODUCT_METRICS_UPDATED`; §2.1 ORDER_REFUNDED: bỏ `admin_funding_refund` khỏi allocations |
| Order | `order_db_design.md` | Banner Phase 21; §1.2: DROP COLUMN `store_name_snapshot`; §1.4: DROP 2 columns order_promotions; §3.4: DROP TABLE `order_product_metrics`; §4.1: cập nhật note |
| Order | `order_workers.md` | Topic map: −1 row; §1: cập nhật outbox router (chỉ `Order`/`OrderItem`); §2: cập nhật note Search/Filter; §3: xóa Order Metrics Projector; renumber §4-6 |
| Order | `order_events.md` | Banner Phase 21; §1.1: −1 row topic; §2.1: −1 row Guard Keys (Order Metrics); §2 aggregate_type: bỏ ProductSalesMetrics; §3.6 ORDER_REFUNDED: bỏ `admin_funding_refund`; §3.8: xóa ORDER_PRODUCT_METRICS_UPDATED; renumber §3.9 → §3.8 |
| Order | `order_state_machine.md` | §6 Guard Keys: bỏ row Order Metrics; mapping table §6: thay `MIN(store_name_snapshot)` → hardcoded `'PLATFORM'` |
| Order | `order_workflow.md` | §2.3: note `primary_store_name` hardcoded Phase 21; §8: cập nhật `ORDER_REFUNDED` allocations (2 field thay vì 3) |
| Order | `order_search_design.md` | Banner Phase 21; §3.1 + §4.1 SQL: thay `MIN(store_name_snapshot)` bằng literal `'PLATFORM'` |
| Order | `temporal_saga_design.md` | Banner Phase 20: cập nhật allocations (2 field); §3 step 1: bỏ `store_name_snapshot` khỏi snapshot list |
| Order | `refund_voucher_policy.md` | Banner Phase 21; tham chiếu CHANGELOG Phase 21 |
| Search | `search_workflow.md` | §2 sort mapping: bỏ `best_selling`; bỏ secondary sort `product_sold_count`; §2 query DSL: bỏ `product_sold_count` sort; §2 response: bỏ field `product_sold_count` |
| Search | `search_elasticsearch_schema.md` | §1: bỏ "Order Metrics Projector" khỏi nguồn sync; §2.2 mapping: xóa `product_sold_count`; §2.4 sample: xóa field; §4.2 mapping: xóa `product_sold_count_30d`; §4.4 sample: xóa field |
| Search | `search_events.md` | §1 topic map: −1 row; §3.11: xóa ORDER_PRODUCT_METRICS_UPDATED processing; renumber §3.12/3.13 → §3.11/3.12; §4.2 guard table: −1 row |
| Search | `search_workers.md` | §2: đổi tên Metrics Indexing Worker → Review Metrics Indexing Worker (chỉ còn review); xóa reference `t.search.metrics.events`; §3 Catch-up: bỏ partition; §4 Suggestions Rebuilder: xóa `product_sold_count_30d` khỏi weight formula |
| Search | `search_architecture_recommendations.md` | §10: bỏ `product_sold_count` khỏi function score; §12: bỏ row `product_sold_count` |
| Promotion | `promotion_db_design.md` | §2.2: xóa mapping table `order_promotions.promotion_type`; thay bằng note Phase 21 |

**Tổng kết:**

- **Tables:** 9 → 8 (Order Service).
- **Columns:** −3 (1 ở `order_items` + 2 ở `order_promotions`).
- **Workers (Order):** 6 → 5 (bỏ `order-metrics-projector`).
- **Kafka topics (Order produce):** 2 → 1 (bỏ `t.search.metrics.events`).
- **Outbox `aggregate_type`:** 3 → 2 (bỏ `ProductSalesMetrics`).
- **Events:** 8 → 7 (bỏ `ORDER_PRODUCT_METRICS_UPDATED`).
- **Search Service consumer groups:** 3 → 2 (gộp).
- **Search ES fields:** −2 (`product_sold_count`, `product_sold_count_30d`).
- **Search API sort:** −1 (`best_selling`).
- **`promotion_refund_allocations[]` fields:** 3 → 2 (bỏ `admin_funding_refund`).
- **Files affected:** 14 file (7 Order + 5 Search + 1 Promotion + 1 Contracts CHANGELOG + 1 Contracts MATRIX).
- **Trade-off chấp nhận:** Search Service không còn khả năng sort theo `product_sold_count` (best_selling). Order Service đơn giản hóa payload allocations → Promotion tự hiểu `refunded_discount_amount` đã là PLATFORM allocation trong PLATFORM-only system.

---

## Phase 20 — Event Payload Simplification (Order ↔ Payment ↔ Inventory ↔ Promotion ↔ Cart)

**Status:** ACCEPTED (2026-06-22)
**Scope:** Tinh giản payload các event Order produce + các event Order consume từ Payment/Inventory, đồng bộ cross-service để đảm bảo producer/consumer chỉ trao đổi field tối thiểu cần thiết.

**Quyết định chốt:**

- **Nguyên tắc tinh giản (minimum viable payload):** Mỗi event chỉ chứa field thực sự cần để consumer (a) xác định aggregate, (b) quyết định hành động tiếp theo, (c) audit khi bắt buộc. Bỏ mọi field có thể derive từ DB/API khác hoặc field snapshot không còn cần thiết.
- **Tách field theo consumer:** Các field chỉ dùng bởi 1 consumer (VD `delivery_address_snapshot` chỉ cho Notification, `courier_note` chỉ cho audit) bị bỏ khỏi payload chung; consumer cần thì fetch qua API.
- **Bỏ field do Phase 18 đã obsolete:** `payment_method`, `provider` (luôn STRIPE), `currency` (luôn VND), `payment_method_type` — đã bỏ từ Phase 18 nhưng vẫn còn sót lại ở một số example.
- **`ORDER_REFUNDED` thu gọn `promotion_refund_allocations[]`:** bỏ `scope_admin_id` (luôn PLATFORM, Phase 14), `partial_restore_rule` (Promotion tự xử lý qua `idempotency_key`), `voucher_restore_mode` (Promotion tự quyết theo wallet validity), `platform_funding_refund` (luôn = `admin_funding_refund`).
- **Counter sequence_id:** giữ nguyên cấu trúc Phase 19 (consumed payment counter không dùng làm guard; chỉ telemetry). **KHÔNG thay đổi** envelope `schema_version` / `business_id` trong phase này (sẽ thảo luận trong giai đoạn structural refactor sau).

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Contracts | `CHANGELOG.md` | Thêm Phase 20 này |
| Contracts | `CONTRACT_MATRIX.md` | §2.1 Order event catalog: update field notes; §2.2 Payment: bỏ field không cần cho Order; §2.3 Inventory: thu gọn `INVENTORY_SHIP_TO_CUSTOMER`/`INVENTORY_RETURN_RECEIVED_ALL`; §7 cross-service validation: bổ sung note "minimum viable payload" |
| Order | `order_events.md` | §3.1-3.9 produced event payloads: thu gọn (bỏ snapshot items[] không cần, promotions_applied[], delivery_address_snapshot, returned_items[] chi tiết, variant_name, single_refund_transaction, customer_return_guard/policy, redundant timestamps); §4.1-4.9 consumed event payloads: thu gọn (bỏ user_id, currency, provider*, payment_event_sequence_id, error_message, order_item_ids, confirmed_at, courier_note) |
| Order | `order_workers.md` | §4 Payment Status Consumer: update logic xử lý field mới; §5 Inventory Ship Consumer: bỏ reference `courier_note`; §5.1 Inventory Return Consumer: bỏ reference `order_item_ids[]`/`confirmed_at` |
| Order | `order_workflow.md` | §4.5, §5, §6, §8: cập nhật reference tới field mới của `ORDER_PAID`/`ORDER_CANCELLED`/`ORDER_STATUS_CHANGED`/`ORDER_REFUNDED`/`ORDER_REFUND_FAILED`/`INVENTORY_*` |
| Order | `order_state_machine.md` | §3 reason enum giữ nguyên; thêm note Phase 20 về payload simplification |
| Order | `temporal_saga_design.md` | §2.4/§3: cập nhật note về payload của event được tạo ra từ workflow |
| Order | `refund_voucher_policy.md` | §2.1, §2.2: cập nhật `promotion_refund_allocations[]` contract bỏ `scope_admin_id`/`partial_restore_rule`/`voucher_restore_mode`/`platform_funding_refund` |
| Order | `note.txt` | Bổ sung banner Phase 20 |
| Payment | `payment_events.md` | §3.1-3.9: thu gọn payload bỏ field Order không cần (`user_id`, `currency`, `payment_event_sequence_id`, `refund_event_sequence_id`, `provider`, `provider_transaction_id`, `provider_charge_id`, `payment_method_type`, `error_message`, `reason_message`) |
| Inventory | `inventory_events.md` | §4.3: thu gọn `INVENTORY_RETURN_RECEIVED_ALL` (bỏ `order_item_ids[]`, `confirmed_at`); §4.4: thu gọn `INVENTORY_SHIP_TO_CUSTOMER` (bỏ `courier_note`) |
| Promotion | `promotion_events.md` | §3.1 inbound `ORDER_REFUNDED`: cập nhật payload example khớp với Order Service; thêm note về field bị bỏ (Promotion tự derive từ `idempotency_key`/wallet validity) |
| Cart | `cart_events.md` | §3.1 inbound `ORDER_PAID`: cập nhật payload example khớp; bỏ `cart_id` (derive từ `user_id`); ghi chú "Cart chỉ dùng `user_id` + `sku_ids`" |
| Cart | `cart_workers.md` | §6 Order Event Consumer: bỏ step validate `cart_id` từ payload (derive từ `user_id`); cập nhật idempotency key theo `order_id` (đã có sẵn) |

**Tổng kết:**

- **Producer (Order)**: 8 event type, payload giảm trung bình **~50%** field (nặng nhất: `ORDER_REFUNDED` từ 25+ field xuống 8; `ORDER_CREATED` từ 20+ xuống 5; `ORDER_STATUS_CHANGED` từ 12 xuống 5).
- **Consumer (Order)**: 9 event type, payload giảm trung bình **~40%** field (nặng nhất: `PAYMENT_SUCCESS` từ 9 xuống 4; `INVENTORY_RETURN_RECEIVED_ALL` từ 4 xuống 2).
- **Cross-service downstream**: 2 file (Promotion inbound `ORDER_REFUNDED`, Cart inbound `ORDER_PAID`) cập nhật để khớp.
- **Lợi ích**: giảm bandwidth Kafka, giảm coupling, đơn giản hóa tài liệu; Notification/Analytics tự fetch chi tiết qua API riêng khi cần.
- **Trade-off chấp nhận**: Notification Service cần gọi `GET /v1/orders/{id}` để lấy delivery_address/items[] cho email body (tăng latency nhẹ nhưng giảm payload trung bình đáng kể). Một số audit timestamp phải lấy từ workflow history thay vì từ event payload.
- **KHÔNG thuộc phase này** (để thảo luận sau): merge `ORDER_REFUNDED`+`ORDER_REFUND_FAILED` thành 1, tách `ORDER_STATUS_CHANGED` thành event riêng theo transition, bỏ envelope field `business_id`/`schema_version` — đây là structural refactor cần cross-team thống nhất.

---

## Phase 19 — Order Service Simplification (FSM lock + sequence_id consolidation)

**Status:** ACCEPTED (2026-06-22)
**Scope:** Order Service docs đồng bộ sau khi bỏ `version` (optimistic locking) và gộp counter sequence_id trong Order's own produced events.

**Quyết định chốt:**

- **Bỏ field `version`/`order_version`/`item_version`**: Temporal workflow đã serialize mọi FSM transition trong workflow context; Kafka consumer + `order_event_idempotency` đã chặn duplicate processing ở entry point; row-level lock (`SELECT ... FOR UPDATE`) đủ chặn concurrent transition giữa các transaction backend. Không cần optimistic locking / CAS `version` nữa.
- **Bỏ `payload_hash` khỏi `order_event_idempotency`**: CloudEvents envelope với `event_id` unique đã đủ chống trùng at-least-once; lane "contract-violation" trước đây chỉ phát hiện nhưng chưa có luồng xử lý rõ ràng.
- **Gộp counter cho Order's own produced events**:
  - `orders.last_event_sequence_id` (per `order_id`) thay thế 2 counter cũ mà Order tự phát trong `ORDER_*` events. Tăng mỗi lần insert `outbox_events` cho 1 order, ghi vào `payload.last_event_sequence_id`.
  - Counter `event_sequence_id` trong `order_product_metrics` (per `product_id`) **giữ nguyên** vì aggregate scope khác hẳn.
- **Counter trong event Order CONSUME từ Payment Service KHÔNG đổi**:
  - `payment_event_sequence_id` (cho `PAYMENT_*`) và `refund_event_sequence_id` (cho `REFUND_*`) do Payment Service tự quản, vẫn còn trong payload.
  - Order **không** dùng 2 field này làm guard; guard bằng `event_id` + unique index `(order_id, action_type, action_scope_key)` trong `order_event_idempotency`.

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Order | `order_db_design.md` | §2: chốt row-level lock thay `version`; §3.0: clarify scope gộp counter (chỉ Order's own produced events); §3.3: ghi chú bỏ `payload_hash` |
| Order | `order_state_machine.md` | §3 / §4: đánh lại numbering (§22 → §3, §24 → §4, §31 → §5, §32 → §6); §6: bỏ "Optimistic Locking" trong mapping guard keys |
| Order | `order_workflow.md` | §3 step 4: `SELECT status, version` → `SELECT status`; §4.3.1: bỏ "optimistic locking"; §6 step 3: bỏ "increment version"; rút gọn §2.1/§2.2 → link `order_search_design.md` |
| Order | `temporal_saga_design.md` | §2.4: `UpdateOrderStatusActivity` chú thích "bỏ `version`/optimistic locking" |
| Order | `order_workers.md` | §5: SQL bỏ `version = version + 1`; §6 activity list: chú thích; §4 idempotency: bỏ "contract-violation lane" → chỉ dựa `event_id` |
| Order | `order_events.md` | §2.1: chuyển "Phase X" → Phase 19; §4.1/§4.7: examples bỏ `last_event_sequence_id` thừa; §4 routing note: clarify guard dùng `event_id` (không dùng `payment_event_sequence_id`) |

---

## Phase 18 — Single Payment Provider (Stripe)

**Status:** ACCEPTED (2026-06-22)
**Scope:** Toàn bộ docs cập nhật để hệ thống chỉ dùng 1 payment provider duy nhất (Stripe).

**Quyết định chốt:**

- Hệ thống **chỉ dùng Stripe** (PaymentIntent + Webhook) cho cả payment và refund. Bỏ hoàn toàn VNPay, MoMo, ZaloPay.
- **Bỏ Provider Adapter Pattern**: Payment Service gọi trực tiếp `com.stripe:stripe-java` SDK. Không còn `PaymentProviderAdapter` interface, không còn `PaymentProviderFactory`, không còn `VNPayAdapter`/`MoMoAdapter`/`ZaloPayAdapter`. Trade-off chấp nhận: vendor lock-in với Stripe; compensate bằng cách encapsulate Stripe SDK trong package `com.payment.stripe`.
- **DROP COLUMN `orders.payment_method`** (VARCHAR(50) trước đó dùng cho denormalized read-model). Không còn field này trong `orders` table, JSON payload của Order events, hay API response.
- **`payment_transactions.provider`**: giữ column nhưng default = `'STRIPE'`, `CHECK (provider = 'STRIPE')`.
- **`webhook_logs`**: thêm `stripe_event_id` (VARCHAR(120)) để dedup theo Stripe `evt_xxx`; thêm `stripe_signature` (TEXT) để audit signature nhận được. `provider` column default = `'STRIPE'`.
- **Webhook URL**: `/v1/payments/webhook/{provider}` → `/v1/payments/webhook/stripe` (cố định, không còn path variable).
- **Webhook signature verification**: `Stripe-Signature` header (`t=timestamp,v1=signature` HMAC-SHA256 với `whsec_...`); reject nếu `|now - t| > 300s` (chống replay).
- **Response Initiate Payment**: thay `payment_url` (VNPay style) bằng `client_secret` (Stripe Elements/Checkout SDK) + `publishable_key`.
- **Refund flow**: gọi `Refund.create()` của Stripe với `Idempotency-Key` header theo key Order sinh.
- **Currency**: giữ `VND` (không đổi). Stripe hỗ trợ VND ở VN region.
- **Promotion rule engine**: `cart.payment_method` field vẫn tồn tại trong rule AST (để tương thích) nhưng giá trị luôn = `"STRIPE"`. Rule mới không nên filter theo giá trị cố định này.

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Contracts | `CHANGELOG.md` | Thêm Phase 18 này |
| Contracts | `CONTRACT_MATRIX.md` | §0.4 webhook key format update (provider=STRIPE); §2.2 thêm note routing; §3.2 webhook URL cố định `/v1/payments/webhook/stripe` |
| Payment | `payment_db_design.md` | §1.1 provider default='STRIPE' + CHECK; §1.2 provider_refund_id comment; §1.3 webhook_logs + stripe_event_id + stripe_signature; §3 Stripe Webhook Signature section; §3.5 mục mới "Stripe SDK direct call" |
| Payment | `payment_workflow.md` | Section 1: Mermaid, code, response (client_secret + publishable_key); Section 2: Stripe-Signature; Section 4: Refund.create() với Idempotency-Key; Important note đổi sang Stripe; TỔNG KẾT 5 nguyên tắc |
| Payment | `payment_state_machine.md` | Ghi chú Stripe là provider duy nhất; mapping Stripe event types → FSM |
| Payment | `payment_events.md` | Tất cả JSON examples: provider="STRIPE", provider_transaction_id=pi_xxx, provider_refund_id=re_xxx, bỏ payment_method field; PAYMENT_SUCCESS thêm payment_method_type, provider_charge_id; REFUND_INITIATED thêm stripe_reason |
| Payment | `payment_workers.md` | §2 Stripe-Signature verification step; §5 cronjob note về Stripe refund timing |
| Payment | `refund_boundary_decision.md` | Ghi chú Phase 18 single-provider, không thay đổi decision chính |
| Payment | `technology_choice.md` | Section 1 bảng update; §2.1 viết lại hoàn toàn (Single-Provider Simplicity); §2.3 Stripe Webhook Security; §3 cập nhật so sánh Go |
| Order | `note.txt` | Bổ sung Phase 18 banner |
| Order | `order_db_design.md` | §1.1 DROP payment_method column; bỏ reference VNPay/MOMO/ZALOPAY |
| Order | `order_events.md` | §3.1, §3.2, §4.1 examples: bỏ payment_method; provider="STRIPE" |
| Order | `order_workflow.md` | §4.1.1 payload: bỏ payment_method; cập nhật validation; ghi chú |
| Order | `order_state_machine.md` | Banner Phase 18 |
| Order | `temporal_saga_design.md` | Ghi chú cuối file về Stripe PaymentIntent |
| Order | `order_workers.md` | Không đổi (consumer trừu tượng) |
| Order | `order_search_design.md` | Không đổi |
| Order | `refund_voucher_policy.md` | Không đổi (không liên quan payment provider) |
| Checkout | `checkout_workflow.md` | §1.6 response: available_payment_methods=["STRIPE"] hoặc bỏ; §2.1 submit payload: bỏ payment_method; bỏ validation enum VNPAY/MOMO/ZALOPAY |
| Checkout | `checkout_caching_schema.md` | §5.2: payment_method = "STRIPE" hoặc bỏ |
| Checkout | `checkout_idempotency.md` | §1.1 mermaid: bỏ payment_method; §3.1: cập nhật bảng |
| Checkout | `checkout_architecture_design.md` | Không đổi |
| Checkout | `checkout_security_analysis.md` | Tìm reference payment_method nếu có |
| Gateway | `design.md` | Bổ sung section 2.2 Webhook Passthrough (Stripe) |
| Gateway | `implementation_plan.md` | Tìm reference payment provider nếu có |
| Gateway | `technology_choice.md` | Tìm reference payment provider nếu có |
| Promotion | `rule_engine_design.md` | Ghi chú Phase 18 về cart.payment_method |
| Promotion | `voucher_discovery_api.md` | 2 chỗ: payment_method = "STRIPE" |
| Promotion | `promotion_db_design.md` | cart.payment_method enum: cập nhật value="STRIPE" |
| Promotion | `promotion_workflow.md` | Reference payment_method: ghi chú chỉ = "STRIPE" |
| Notification | `events.md` | JSON example: payment_method = "STRIPE" |

**Tổng kết:**

- **Pattern bỏ**: Provider Adapter + Factory Pattern.
- **Column dropped**: `orders.payment_method`.
- **Column added/updated**: `payment_transactions.provider` (default + CHECK), `webhook_logs.stripe_event_id`, `webhook_logs.stripe_signature`.
- **Webhook URL đổi**: `/v1/payments/webhook/{provider}` → `/v1/payments/webhook/stripe`.
- **Response shape đổi**: `payment_url` (VNPay) → `client_secret` + `publishable_key` (Stripe).
- **Event payload**: thêm `payment_method_type`, `provider_charge_id`, `stripe_reason`; bỏ `payment_method` (Order side).
- **Files affected**: ~28 docs across 7 services.
- **Trade-off chấp nhận**: vendor lock-in với Stripe; đổi provider trong tương lai phải refactor Stripe-specific code (đã encapsulate trong `com.payment.stripe` package để giảm rủi ro).

---

## Phase 16 — Remove Cold Data Archiving from Order Service

**Status:** ACCEPTED (2026-XX-XX)
**Scope:** Order Service archival path + docs đồng bộ.

**Quyết định chốt:**

- Bỏ toàn bộ cơ chế cold data archiving: orders lịch sử được giữ nguyên trong PostgreSQL OLTP, không archive sang S3/Parquet.
- Bỏ control tables: `archived_orders_lookup`, `archive_batches`, `archive_batch_items`.
- Bỏ Kafka topic: `t.order.archive.retry`.
- Bỏ outbox event: `ORDER_ARCHIVE_BATCH_FAILED`; aggregate type `ArchiveBatch` không còn hợp lệ.
- Bỏ idempotency action: `ARCHIVE_BATCH_REPLAY`.
- Bỏ workers: `Cold Data Archiver (Batch CronJob)` và `Archive Replay Consumer` — chỉ còn 3 cronjob (Outbox Trimmer, CDC DLQ Monitor, Idempotency Cleanup).
- Bỏ hot→cold routing trên `GET /v1/orders/{orderId}` — chỉ còn hot path (PostgreSQL), miss trả `404`.
- Bỏ field `cold_data_unavailable` khỏi response `GET /v1/orders/search`.
- Giữ default buyer window 90 ngày cho Search API (đổi rationale sang performance bound; không còn liên quan cold cutoff).

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Order | `cold_data_archiving_design.md` | **Xoá** |
| Order | `order_db_design.md` | §3.7 xoá (3 control tables); §3.1 + §3.3 bỏ reference `ArchiveBatch`/`archive_batch_id`/`ARCHIVE_BATCH_REPLAY`; §4.2 xoá; header link bỏ |
| Order | `order_events.md` | §1.1 + §1.2 bỏ row `t.order.archive.retry`; §2 bỏ `ArchiveBatch` aggregate; §3.10 + §4.11 xoá; header link bỏ |
| Order | `order_workers.md` | Bỏ 2 row `t.order.archive.retry`; §1 router bỏ `ArchiveBatch`; §7 + §8 xoá; đánh lại §9→§7, §10→§8, §11→§9; header link bỏ |
| Order | `order_workflow.md` | §1 step 4 hot-miss xoá (cold fallback); §1 step 6 sửa cụm "hot và cold"; §2.3 bỏ cold_data_unavailable note; §2.5 response shape bỏ field; header link bỏ |
| Order | `order_search_design.md` | §6 xoá (Cold-Data Path); §7 response bỏ field; §8 so sánh bỏ row "Cold search"; §10 checklist bỏ bullet; header link bỏ |
| Order | `temporal_saga_design.md` | Header link bỏ |
| Order | `note.txt` | Dòng 1 về cold metadata lookup xoá |
| Contracts | `CONTRACT_MATRIX.md` | §1 topic map bỏ `t.order.archive.retry`; §2.1 bỏ block `ORDER_ARCHIVE_BATCH_FAILED`; §2.6 bỏ row consumer |

**Tổng kết:**

- **Kafka topics**: 3 → 2 (bỏ `t.order.archive.retry`).
- **PostgreSQL tables**: -3 (bỏ `archived_orders_lookup`, `archive_batches`, `archive_batch_items`).
- **Workers**: -2 (bỏ Cold Archiver + Archive Replay Consumer).
- **API contract**: bỏ field `cold_data_unavailable`; đơn giản hoá `GET /v1/orders/{orderId}` (chỉ hot path).
- **Trade-off chấp nhận**: bảng `orders`/`order_items`/`order_item_lines`/`order_promotions` sẽ grow tuyến tính theo doanh số; cần bổ sung partitioning/retention sau nếu DB phình quá lớn (phase sau nếu cần).

---

## Phase 15 — Remove Elasticsearch from Order Service (Search/Filter trực tiếp PostgreSQL)

**Status:** ACCEPTED (2026-XX-XX)
**Scope:** Order Service search/filter path + docs đồng bộ.

**Quyết định chốt:**

- Bỏ toàn bộ read-model Elasticsearch: `orders_read_vN`, `order_search_lines_vN`, toàn bộ alias `orders_read` / `orders_write` / `order_search_lines` / `order_search_lines_write`.
- Bỏ projector workers: `order-cqrs-summary-projector`, `order-cqrs-line-projector`, `order-cqrs-retry-replayer`.
- Bỏ Kafka topics: `t.order.cqrs.retry`, `t.order.cqrs.dlq`.
- Bỏ control tables: `cqrs_projection_checkpoints`, `cqrs_reindex_runs`.
- Bỏ reindex runbook (6-phase + shadow catch-up).
- Search/Filter API (`GET /v1/orders/search`) query **trực tiếp PostgreSQL** qua:
  - B-tree composite indexes mới: `idx_orders_buyer_created_id`, `idx_orders_created_id`, `idx_orders_status_payment_created`, `idx_orders_final_amount_created`.
  - GIN trigram index mới: `idx_oil_snapshot_name_search` (text search theo `snapshot_product_name`).
  - View mới: `v_orders_buyer_tab` (derive `buyer_tab` tại SQL).
  - Extensions: `pg_trgm`, `unaccent`.
- Bỏ text search theo `store_name` (vì `store_name_snapshot` luôn là `'PLATFORM'`).
- Bỏ fuzzy / autocomplete / highlight / relevance scoring — chỉ substring accent-insensitive.
- Bỏ response field `match_reason` và `matched_context[]` (Phase 14 còn trả).
- Cold-data path: trả `cold_data_unavailable: true` cho query vượt 90 ngày, không scan S3 theo `q`.

**File đồng bộ:**

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Order | `cqrs_elasticsearch_design.md` | **Xoá** |
| Order | `order_search_design.md` | **Tạo mới** — thay thế toàn bộ design read-model |
| Order | `order_db_design.md` | §3.5/§3.6 xoá (control tables); §4.1 viết lại (PostgreSQL direct + indexes mới); related-docs đổi sang `order_search_design.md` |
| Order | `order_workflow.md` | §2 viết lại (SQL CTE Class A + Class B); bỏ "Tại sao Elasticsearch?"; response contract chốt (bỏ match_reason, matched_context, matched_lines_count); bỏ `[Elasticsearch]` khỏi tương tác stack |
| Order | `order_workers.md` | §2 (CQRS Projection Workers) xoá; topic map bỏ `t.order.cqrs.retry` / `t.order.cqrs.dlq`; §7 thêm note về cold_data_unavailable |
| Order | `order_events.md` | §1.1 bỏ topic CQRS retry/DLQ; §2.1 đổi tên "Guard Keys cho FSM & Optimistic Locking" (bỏ CQRS); §2.2 "Search projection contract" xoá; header từng event bỏ "CQRS Projection Worker" |
| Order | `order_state_machine.md` | §32 đổi tiêu đề + viết lại mapping FSM sang API Response (không còn read-model) |
| Order | `cold_data_archiving_design.md` | §4 viết lại cho search API PostgreSQL direct (bỏ `orders_read`, fallback `cold_data_unavailable: true`) |
| Order | `temporal_saga_design.md` | Link đổi `cqrs_elasticsearch_design.md` → `order_search_design.md`; line 103 đổi "CQRS search" → "Search/Filter API response" |
| Contracts | `CONTRACT_MATRIX.md` | §1 Kafka topic map bỏ `t.order.cqrs.retry`/`t.order.cqrs.dlq`; §2.1 Consumers từng event bỏ CQRS; §2.6 Cross-Service Consumption bỏ 2 row CQRS retry/dlq; §0.6 "Order Metrics (CQRS)" → "Order Metrics" |

**Tổng kết:**

- **Kafka topics**: 10 → 8 (bỏ `t.order.cqrs.retry`, `t.order.cqrs.dlq`).
- **Internal workers**: bỏ 3 projector (giữ `order-metrics-projector` cho product metrics).
- **PostgreSQL objects mới**: 5 indexes (4 B-tree + 1 GIN trigram) + 1 view + 2 extensions (pg_trgm, unaccent).
- **API contract**: bỏ `match_reason`, `matched_context[]`, `matched_lines_count`; thêm `cold_data_unavailable`.
- **Trade-off FTS chấp nhận**: bỏ tokenization / fuzzy / autocomplete / highlight / scoring; chỉ giữ substring accent-insensitive.

---

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

## Phase 15 — Drop `estimated_delivery_days` + Rename `warehouse_options` → `warehouse_assignments`

> **Ghi chú:** Phase 15 sửa doc-bug consistency giữa Inventory & Checkout sau khi Phase 14 loại bỏ Shipping Service. Field `estimated_delivery_days` không còn consumer thực tế.

### Quyết định kiến trúc chốt

| # | Quyết định | Giá trị |
|---|---|---|
| 1 | Drop field | Bỏ `estimated_delivery_days` khỏi `warehouse_assignments[]` của Inventory `routing-options` response và Checkout Preview response |
| 2 | Rename field | `warehouse_options` → `warehouse_assignments` cho đồng bộ Inventory ↔ Checkout (canonical tên đã có sẵn ở Checkout response payload) |
| 3 | Lý do drop | Phase 14 đã loại Shipping Service; delivery estimate không còn consumer downstream |
| 4 | Lý do rename | Sửa inconsistency: Inventory dùng `warehouse_options` (gợi nhầm pricing options), Checkout đã canonical là `warehouse_assignments` |
| 5 | Tác động Submit Saga | KHÔNG — `route_plan_id`, `items[].warehouse_id` vẫn nguyên |
| 6 | Tác động Cache | Cache entries cũ `route_plan:{id}` TTL 20 phút tự expire; không cần migration script |
| 7 | Backward compat | Breaking change; không có client thật parse field `estimated_delivery_days` (Phase 14 vừa chốt, chưa release) |
| 8 | Drop class | Bỏ `EstimatedDeliveryDaysResolver` khỏi Inventory routing package |
| 9 | Drop record field | Bỏ `int estimatedDeliveryDays` khỏi `RoutePlanProto` |

### File sửa (8 file)

| Nhóm | File | Thay đổi chính |
|---|---|---|
| Inventory | `inventory_endpoints.md` | L91-98 JSON rename + drop; L109-112 bảng mô tả rename + drop row |
| Inventory | `smart_routing_engine.md` | L39-46 Output Contract rename + drop |
| Inventory | `inventory_workflow.md` | L259 luồng 4 response shape rename + drop |
| Inventory | `inventory/plan/04-domain-model.md` | §4.5 `RoutePlan.WarehouseOption` → `WarehouseAssignment`; drop `estimatedDeliveryDays` field |
| Inventory | `inventory/plan/10-routing-engine.md` | Bỏ `EstimatedDeliveryDaysResolver` (class + package layout); bỏ field khỏi `RoutePlanProto`; cập nhật `RoutePlanBuilder` |
| Inventory | `inventory/plan/13-http-api-controllers.md` | §4.2 `WarehouseOptionDto` → `WarehouseAssignmentDto`; drop `estimatedDeliveryDays` |
| Checkout | `checkout_workflow.md` | L168-172 Preview response drop field `estimated_delivery_days` |
| Checkout | `checkout_idempotency.md` | L32 sequence diagram rename `warehouse_options` → `warehouse_assignments` |
| Checkout | `checkout_caching_schema.md` | L71 rename + đổi tên API giả định `/checkout/warehouse-options` → `/checkout/warehouse-assignments` |

### Tổng kết Phase 15

- **Field dropped**: `estimated_delivery_days` (1 field) + `EstimatedDeliveryDaysResolver` (1 class)
- **Field renamed**: `warehouse_options` → `warehouse_assignments` (3 file Inventory + 2 file Checkout)
- **Java record changed**: `WarehouseOption` → `WarehouseAssignment` trong `RoutePlan`; `WarehouseOptionDto` → `WarehouseAssignmentDto` trong `RoutingOptionsResponse`
- **Validation**: grep `estimated_delivery_days`, `EstimatedDeliveryDays`, `warehouse_options` expected = 0 hits ngoại trừ `CHANGELOG.md` (lịch sử)

---
