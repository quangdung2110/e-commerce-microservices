---
doc_id: promotion-domain
service: promotion
scope: current
status: implemented
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
  - contracts/http/promotion/v1/openapi.yaml
verified_against_commit: null
---

# Promotion Service — Mô hình domain

## 1. Ngôn ngữ chung

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Campaign | Lớp quản trị và lịch chung cho voucher/markdown | Quyền lợi đã lưu trong wallet |
| Publication status | Ý định admin: `DRAFT/PUBLISHED/PAUSED/CANCELLED` | Phase theo thời gian |
| Campaign phase | `DRAFT/UPCOMING/ACTIVE/ENDED/PAUSED/CANCELLED` được suy ra | DB status mutable |
| Promotion | Voucher do admin tạo, áp dụng cho toàn đơn | Markdown giá sản phẩm |
| Markdown rule | Giảm trực tiếp cho một `product_id` khi campaign active | Voucher checkout |
| Claim window | Atomic Redis-time interval từ claim-gate fields trong runtime hash để nhận limited voucher mới | Hạn dùng voucher đã sở hữu |
| Use window | Đến `wallet.expired_at`, được snapshot lúc save | Campaign có còn active hay không |
| Claim | Một suất hữu hạn được gán cho user | Redeem vào order |
| Wallet entry | Quyền sở hữu và trạng thái redemption hiện hành | Reservation runtime |
| Reservation | Giữ quota/amount 15 phút trong Redis sau Submit | Wallet DB state |
| Redeem | Chuyển reserved→used và wallet `SAVED→USED` | Claim/save |
| Legacy technical cleanup | Deprecated `cancel-confirmed` only for `promotion-legacy-migrator` with immutable proof the flow never crossed Payment financial watermark | Customer refund or active post-capture Order failure |
| Customer refund | Buyer selected-line hoặc Admin pre-dispatch refund | Promotion budget rollback |
| Command receipt | Biên nhận durable cho final command, response replay và Redis finalize marker | Outbox event/publication state |

## 2. Domain objects

- `Campaign` là aggregate schedule/lifecycle chung; `revision` là concurrency/cache/schedule
  fence, không phải immutable definition version.
- `Promotion` là voucher definition và có một `PromotionBudget`.
- `CouponClaim` là một suất pre-generated cho distribution `LIMITED_CLAIM`.
- `UserCouponWallet` là current ownership/redemption state, duy nhất theo
  `(user_id,promotion_id)`; không cần `CouponUsage` aggregate.
- `MarkdownRule` là product-scope immutable child; Product tombstone có thể gắn operational
  invalidation riêng mà không sửa definition.
- `CatalogProductTombstone` là durable terminal watermark theo Product/catalog revision;
  nó chặn markdown rule được tạo sau khi delete đã được quan sát.
- `PromotionOutboxEvent` là append-only integration fact/durable dedup record; ở v1 chỉ
  chứa markdown snapshot.
- `PromotionCommandReceipt` là inbound `CONFIRM`/legacy `CANCEL_CONFIRMED` receipt, chứa stable
  key, request hash, stored response và `redis_finalized_at`; không phải usage ledger.
- User, Order và Product chỉ là identifier/snapshot từ bounded context khác.

## 3. Commands và queries

| Type | Capability | Actor |
|---|---|---|
| Command | Tạo/cập nhật/publish/pause/cancel campaign | Admin |
| Command | Tạo/cập nhật voucher hoặc markdown trước publish | Admin |
| Command | Clone published campaign thành DRAFT identity mới | Admin |
| Command | Claim/save voucher | Customer |
| Command | Reserve/confirm/cancel | Order/Temporal |
| Command | Legacy `cancel-confirmed` | `promotion-legacy-migrator` capability only |
| Query | Calculate discount | Checkout |
| Query | Voucher trong ví/public discovery | Customer qua Gateway |
| Worker | Schedule START/END, rescue, reconciliation | Promotion |
| Event | Invalidate markdown khi Product hard delete | Product Kafka tombstone |

Không có command customer-refund trong Promotion.

## 4. Business rules

| ID | Quy tắc |
|---|---|
| `PROMO-BR-001` | Một order áp dụng tối đa một `PLATFORM_VOUCHER`. |
| `PROMO-BR-002` | Voucher mang nghĩa toàn đơn; Promotion không phân bổ discount xuống SKU/order line. |
| `PROMO-BR-003` | `final = max(0, subtotal - platform_discount)`; percentage có cap, fixed có clamp. |
| `PROMO-BR-004` | Preview/Discovery chỉ đọc; reserve chỉ sau Submit và có `order_id`. |
| `PROMO-BR-005` | Reserve all-or-nothing; replay cùng key phải khớp payload. |
| `PROMO-BR-006` | Limited claim Lua phải kiểm claim-gate fields enabled, matching campaign revision và Redis time trong `[start_time,end_time)` trước SPOP; public save kiểm phase trong DB transaction. |
| `PROMO-BR-007` | Voucher đã lưu chỉ dùng `wallet.expired_at`; campaign end không tự thu hồi row/quyền lợi ngoài expiry đã snapshot. Với mode `CAMPAIGN_END`, hai mốc trùng nhau. |
| `PROMO-BR-008` | Validity phải chọn `CAMPAIGN_END`, `DURATION_AFTER_SAVE` hoặc `FIXED_DATE`; không có default ngầm. |
| `PROMO-BR-009` | Definition đã publish là bất biến; thay đổi business config phải clone campaign/promotion/markdown identity mới, không chỉ tăng cùng-row revision. |
| `PROMO-BR-010` | Buyer selected-line hoặc Admin pre-dispatch refund không restore voucher và không hoàn Promotion budget. |
| `PROMO-BR-011` | Legacy `cancel-confirmed` with migration proof trả wallet về `SAVED` nếu còn hạn, `EXPIRED` nếu hết hạn. |
| `PROMO-BR-012` | Markdown chỉ product-scope, chỉ effective khi campaign `ACTIVE`, rule enabled và chưa bị Product tombstone invalidate. |
| `PROMO-BR-013` | Worker không xử lý schedule dựa riêng vào Redis; phải re-read DB/revision/time. |
| `PROMO-BR-014` | Missing AST field trả false; Rule Engine thuần và xác định. |
| `PROMO-BR-015` | `IN` collection là ANY; `NOT_IN` là ALL-not-match; missing vẫn false. |
| `PROMO-BR-016` | Unknown operator/node hoặc logical children rỗng bị từ chối trước persist/publish. |
| `PROMO-BR-017` | Confirm chỉ fence reservation trên Redis trước khi receipt, wallet và durable budget cùng commit PostgreSQL. |
| `PROMO-BR-018` | Deprecated `cancel-confirmed` legacy cleanup commit PostgreSQL trước; Redis chỉ được repair sau commit, không replay decrement delta. Contract/mesh/application deny `order-service` (`403`); only `promotion-legacy-migrator` plus immutable pre-watermark proof may call it: header `X-Legacy-Migration-Proof` phải là UUID bằng `migration_record_id` của body, thiếu hoặc khác là `403` và không đọc/ghi gì. Record ID và digest đi vào request hash và được lưu trên receipt. |
| `PROMO-BR-019` | Command receipt dedup inbound command và là durable trigger cho Redis finalize/repair qua `redis_finalized_at`; outbox dedup outbound markdown event. Hai bảng có retention độc lập. |
| `PROMO-BR-020` | Markdown dedup identity gồm product, campaign, campaign revision và trigger; resulting state không thuộc key; ordering dùng source revision riêng. |
| `PROMO-BR-021` | Snapshot ACTIVE mang full rule; INACTIVE chỉ là tombstone có reason, không mang rule/discount/interval. |
| `PROMO-BR-022` | Product tombstone persist terminal watermark và invalidate markdown idempotently theo catalog revision; mọi markdown mutation serialize cùng product lock và không phát INACTIVE ngược về Product đã xóa. |
| `PROMO-BR-023` | `BUDGET_EXHAUSTED` chỉ là synchronous outcome/telemetry, không phải integration event. |
| `PROMO-BR-024` | Chỉ phát integration event có consumer đã đăng ký trong contract; ở v1 chỉ có markdown snapshot. Wallet transition, reservation và campaign lifecycle không tạo Kafka event. |
| `PROMO-BR-025` | Context của calculate, voucher options và reserve là trusted context do Checkout/Order dựng: `checkout_channel` phải là `CHECKOUT`, khác thì `400 INVALID_REQUEST`. Context của discovery/suggestion/save là của client nên không bị ép. |
| `PROMO-BR-026` | Reservation tạo khi wallet còn hạn là authority của `CONFIRM`: wallet đã bị lazy-normalize thành `EXPIRED` trong lúc Payment chạy vẫn chuyển `EXPIRED → USED`. Chỉ `CONFIRM` có đường này; save, reserve và discovery vẫn coi `EXPIRED` là hết hạn. |
| `PROMO-BR-027` | Hai final command trùng key đến đồng thời cho cùng một kết quả: transition chỉ xảy ra một lần và mọi bên nhận stored response (kiểm lại receipt sau khi giành khoá wallet). |

Natural campaign end khác emergency revocation. Pause/cancel dừng effective markdown và
claim mới nhưng không tự thu hồi voucher đã lưu. Revocation nếu cần phải là nghiệp vụ
riêng, có audit và contract riêng.

## 5. Campaign lifecycle

`publication_status` persist:

- `DRAFT`: chưa public.
- `PUBLISHED`: phase suy ra bằng clock.
- `PAUSED`: admin tạm dừng claim/markdown; schedule cũ bị revision fence vô hiệu.
- `CANCELLED`: không claim/markdown; terminal về quản trị.

Transition hợp lệ: `DRAFT→PUBLISHED`, `PUBLISHED→PAUSED|CANCELLED`, `PAUSED→CANCELLED`.
Không có resume `PAUSED→PUBLISHED`: muốn chạy lại phải clone campaign mới. Publish bị từ chối
(`409 INVALID_STATE_TRANSITION`) khi `end_time <= now`. `DIRECT_GRANT` chỉ khi campaign
`PUBLISHED`, `now < end_time` (UPCOMING hoặc ACTIVE) và promotion enabled; public save chỉ
khi phase ACTIVE.

Với `PUBLISHED`:

```text
now < start_time              => UPCOMING
start_time <= now < end_time  => ACTIVE
now >= end_time               => ENDED
```

Không có transition ghi DB tại START/END. Schedule worker phát current-state markdown snapshot
để downstream hội tụ; eligibility đồng bộ tự tính phase nên worker lag không mở campaign
sớm. Limited claim không dựa vào scheduler: Redis-time gate đóng hard boundary trước
SPOP. `campaigns.revision` tăng cho DRAFT mutation và publish/pause/cancel để fence stale
work, nhưng không tạo definition history. Product tombstone là per-rule operational
overlay nên không bump campaign revision hoặc claim-gate fields trong runtime hash.

## 6. State machines

### Wallet voucher

```mermaid
stateDiagram-v2
  [*] --> SAVED: Claim / save / grant
  SAVED --> EXPIRED: expired_at reached (lazy, on a query of the user)
  SAVED --> USED: Confirm
  EXPIRED --> USED: Confirm of a reservation made before expired_at
  USED --> USED: Buyer selected-line or Admin pre-dispatch refund
  USED --> SAVED: cancel-confirmed and not expired
  USED --> EXPIRED: cancel-confirmed and expired
```

Reservation không phải wallet state:

```text
SAVED --reserve(runtime)--> SAVED --cancel--> SAVED
SAVED --reserve(runtime)--> SAVED --confirm--> USED
```

`EXPIRED` chỉ được ghi khi một truy vấn của chính user chuẩn hoá wallet đã hết hạn (không có batch).
Reserve yêu cầu wallet còn hạn, nên một wallet `EXPIRED` dưới reservation còn sống chỉ có thể do chuẩn hoá
lười xảy ra sau Reserve; `CONFIRM` vẫn redeem nó (PROMO-BR-026, ADR-019). Một `cancel-confirmed` legacy
sau đó trả wallet về `EXPIRED` vì `expired_at` đã qua.

Customer refund không tạo transition. Sau `cancel-confirmed`, usage fields được clear để
voucher có thể dùng lại nếu còn hạn.

### Claim

| From | Trigger | Guard | To |
|---|---|---|---|
| `AVAILABLE` | Redis gate + SPOP, rồi PostgreSQL claim persistence | gate enabled/revision match, Redis time trong `[open,close)`, owner null, user chưa claim | `CLAIMED`; API success chỉ sau DB commit |
| `CLAIMED` | duplicate/replay | cùng claim/user | `CLAIMED` no-op |

### Markdown effective state

Markdown snapshot là `ACTIVE` chỉ khi campaign phase ACTIVE, rule enabled và chưa bị
invalidate; payload mang rule/discount/interval. Khi không còn winner, `INACTIVE` chỉ mang
product, source revision và reason. Effective state không persist trong `markdown_rules`;
Product tombstone invalidation là operational evidence riêng.

Winner khi nhiều campaign ACTIVE cùng product: `priority` giảm dần rồi `id` tăng dần.
`inactive_reason` theo trigger causal: `END` → `EXPIRED`; `PAUSE`/`CANCEL` → `ADMIN_DISABLED`;
`PUBLISH`/`START` mà không có winner → `NO_ACTIVE_RULE` (rule disabled/invalidated), hoặc
`EXPIRED` nếu cause campaign đã qua `end_time` (START xử lý muộn).

## 7. Invariants

| ID | Điều kiện phải luôn đúng | Enforcement |
|---|---|---|
| `PROMO-INV-001` | Tối đa một platform voucher/order. | API + partial unique `used_order_id` |
| `PROMO-INV-002` | Budget integer không âm và không vượt total hữu hạn. | Lua + CHECK + reconciliation |
| `PROMO-INV-003` | Không reservation trước Submit. | Workflow boundary/test |
| `PROMO-INV-004` | Một user có tối đa một claim/wallet row mỗi promotion. | Unique constraints |
| `PROMO-INV-005` | Campaign phase và wallet use window không bị đánh đồng. | Eligibility tests |
| `PROMO-INV-006` | Customer refund không mutate Promotion. | Không API/consumer/refund script |
| `PROMO-INV-007` | Deprecated legacy cleanup không làm mất voucher còn hạn; active post-capture flow cannot invoke it. | Guarded wallet transition + caller policy/contract test |
| `PROMO-INV-008` | Redis schedule/counter không sửa ngược DB. | Worker policy/test |
| `PROMO-INV-009` | Stale campaign revision không phát state cũ. | Schedule member + DB revision check (kiểm lại ở đầu mỗi lô) |
| `PROMO-INV-010` | Markdown event ordering tăng theo product source revision. | Advisory lock + sequence |
| `PROMO-INV-011` | Rule evaluation chỉ phụ thuộc AST + input context. | Unit/property tests |
| `PROMO-INV-012` | Mỗi final command/order/promotion có tối đa một completed receipt và một stored response. | Unique command receipt + request hash |
| `PROMO-INV-013` | Receipt, wallet và durable budget của final command cùng commit hoặc cùng rollback. | PostgreSQL transaction |
| `PROMO-INV-014` | Không decrement/increment Redis mù khi finalizer/repair thiếu runtime evidence. | Fence/finalizer guard + reconciliation |
| `PROMO-INV-019` | Mọi receipt đã commit cuối cùng đều được finalize/repair Redis đúng một lần về mặt hiệu ứng; `redis_finalized_at` chỉ set sau khi Redis hội tụ. | Partial index `redis_finalized_at IS NULL` + idempotent Lua |
| `PROMO-INV-015` | Limited claim success không linearize ngoài hard claim interval/revision. | Claim-gate Lua + Redis TIME |
| `PROMO-INV-016` | Published business definition không mutate in-place. | Admin guards + clone workflow |
| `PROMO-INV-017` | Hai causal markdown transition khác nhau không dùng cùng outbox dedup key. | Full causal unique key |
| `PROMO-INV-018` | Product hard delete không để lại hoặc cho tạo mới dangling markdown rule. | Tombstone watermark + shared product advisory lock + guarded invalidation |
| `PROMO-INV-020` | START/END của campaign hội tụ cho mọi product dù một số lô lỗi: lô đã commit không bị phát lại, member chỉ bị `ZREM` khi không còn lô lỗi. | Mỗi lô một transaction + causal dedup key/`ON CONFLICT` + dedup pre-check + `ZREM` sau lô cuối (ADR-021) |

## 8. Rule AST

```json
{
  "node_type": "LOGICAL",
  "operator": "AND",
  "children": [
    {
      "node_type": "CONDITION",
      "operator": "GREATER_THAN_OR_EQUAL",
      "field": "cart.total_amount",
      "value": 200000
    }
  ]
}
```

Logical operator: `AND`, `OR`. Condition operator: `EQUAL`, `NOT_EQUAL`, `IN`,
`NOT_IN`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN`,
`LESS_THAN_OR_EQUAL`. `rule_schema_version` version hóa format AST; campaign revision chỉ
fence optimistic concurrency/cache/schedule. Một business definition mới dùng identity mới.

## 9. Authorization

- Chỉ admin quản trị campaign/voucher/markdown/budget.
- Customer chỉ đọc/claim/save wallet của `user_id` trong access token.
- Chỉ Checkout workload identity (`X-Service-Identity: checkout-service`) gọi
  `POST /internal/v1/promotion-discount-calculations` và `POST /internal/v1/promotion-voucher-options`
  (`promotion.internal.calculation-clients` / `options-clients`); discovery/suggestion customer lấy
  user identity đã được Gateway verify, còn reservations/confirmations/cancellations chỉ allowlist
  Order/Temporal (`X-Service-Identity: order-service`, `allocation-clients`) và technical-cancellations
  chỉ `promotion-legacy-migrator` (`legacy-migration-clients`; Order bị `403`). Việc chỉ Checkout/Order chạm được các route này do
  service mesh thực thi theo principal ([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
  header do caller tự đặt và không được mesh xác minh, nên `TrustedIdentityFilter` allowlist theo
  nhóm route chỉ là defense-in-depth. Filter so khớp trên path đã decode và bỏ `;param` (đúng path
  Spring MVC route), không phải URI thô, để `/api/v1/admin;x=1/...` hay `/%69nternal/v1/...` không né
  được role ADMIN / allowlist (`TrustedIdentityFilterPathTest`).
- Order/Temporal gọi reserve/confirm/cancel only. `cancel-confirmed` requires the separate legacy-migrator capability and proof
  (`403` cho mọi caller khác và cho proof thiếu/sai; `TrustedIdentityFilterTest`, `HttpContractIntegrationTest`).
- Consumer validate broker ACL, schema, event identity và business guard.

## 10. Domain errors

Danh mục đầy đủ của `common/ErrorCode.java` (34 mã). Cột `retryable` là giá trị trên wire
(`problem.retryable`; `true` kèm `Retry-After: 1`); cột cuối là hướng xử lý của caller. `ErrorCode` là
nguồn chuẩn: không có mã nào ngoài bảng này (mã `PARTIAL_RESERVATION_STATE` từng được mô tả nhưng chưa bao giờ
tồn tại: reservation/counter lệch được sửa nền bằng reconciliation, không trả cho caller).

| Error | HTTP | `retryable` | Điều kiện / hướng xử lý |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | false | Body/header/tham số sai; `If-Match` thiếu hoặc sai dạng; `Idempotency-Key` không khớp `order_id`/`promotion_id`; currency ≠ VND; `payment_method` ≠ STRIPE; `checkout_channel` ≠ `CHECKOUT` ở calculate/options/reserve; route lạ (`404`), sai method (`405`) hoặc Content-Type không hỗ trợ (`400`) |
| `INVALID_CURSOR` | 400 | false | Cursor không giải mã được |
| `UNAUTHENTICATED` | 401 | false | Thiếu trusted identity (user hoặc `X-Service-Identity`) |
| `FORBIDDEN` | 403 | false | Thiếu role ADMIN; service identity ngoài allowlist của nhóm route; technical-cancellations thiếu/sai `X-Legacy-Migration-Proof` |
| `CAMPAIGN_NOT_FOUND`, `PROMOTION_NOT_FOUND`, `MARKDOWN_RULE_NOT_FOUND`, `PRODUCT_NOT_FOUND` | 404 | false | Không có resource (`PRODUCT_NOT_FOUND`: Product trả 404 khi validate markdown rule) |
| `CAMPAIGN_NOT_ACTIVE` | 409 | false | Claim/save/grant khi phase không cho phép; Reserve khi promotion `is_enabled=false`. Không đổi đến khi state đổi |
| `CLAIM_GATE_STALE` | 409 | true | Gate revision khác expected revision; refresh rồi retry |
| `ALREADY_CLAIMED` | 409 | false | User đã claim promotion |
| `OUT_OF_STOCK` | 409 | false | Claim pool rỗng hoặc `total_claim_quantity` đã chạm |
| `ALREADY_USED_VOUCHER` | 409 | false | Wallet đã `USED` |
| `VOUCHER_EXPIRED` | 409 | false | `wallet.expired_at <= now` |
| `VOUCHER_NOT_OWNED` | 409 | false | Reserve cho promotion không có trong wallet |
| `BUDGET_EXHAUSTED` | 409 | false | Reserve không đủ qty/amount, hoặc durable budget không nhận Confirm; chỉ trả sync + metric. Thử lại khi reservation/budget đổi |
| `IDEMPOTENCY_PAYLOAD_MISMATCH` | 409 | false | Cùng stable key/business operation nhưng canonical request hash khác (kể cả legacy cancel với record ID hoặc digest khác); điều tra |
| `EXPECTED_DISCOUNT_MISMATCH` | 409 | false | Reserve: `expected_discount` khác discount Promotion tính; Order tính lại |
| `RESERVATION_NOT_FOUND` | 409 | false | Confirm không còn payload và không có receipt. Retry/recover cùng final command nếu có thể; sau payment success Order vào forward repair/financial reconciliation, không bao giờ cancel-confirmed |
| `FINAL_COMMAND_STATE_INCONSISTENT` | 409 | false | Receipt và wallet/budget không khớp invariant (wallet không `SAVED`/`EXPIRED` khi Confirm, không `USED` bởi order khi legacy cancel, underflow counter); parking-lot/repair |
| `INVALID_STATE_TRANSITION` | 409 | false | Transition campaign ngoài `DRAFT→PUBLISHED`, `PUBLISHED→PAUSED\|CANCELLED`, `PAUSED→CANCELLED`; publish khi `end_time <= now` |
| `CAMPAIGN_CODE_ALREADY_EXISTS`, `PROMOTION_CODE_ALREADY_EXISTS`, `MARKDOWN_RULE_ALREADY_EXISTS` | 409 | false | Code đã tồn tại / campaign đã có rule cho product |
| `CAMPAIGN_NOT_DRAFT` | 409 | false | Mutation definition sau publish; clone campaign |
| `DISTRIBUTION_MODE_MISMATCH` | 409 | false | Save/claim/grant sai distribution mode |
| `PROMOTION_CONSTRAINT_VIOLATION` | 409 | false | Ràng buộc PostgreSQL bị vi phạm bởi race không có mã riêng; tải lại rồi thử lại |
| `CAMPAIGN_REVISION_MISMATCH` | 412 | false | `If-Match` khác revision hiện hành; retry với ETag mới |
| `CONDITIONS_NOT_MET` | 422 | false | AST false hoặc `MIN_ORDER_NOT_MET` ở Reserve |
| `INVALID_RULE` | 422 | false | AST/operator/field/config không hợp lệ; `LIMITED_CLAIM` thiếu `total_claim_quantity` hoặc vượt pool tối đa |
| `PRODUCT_TOMBSTONED` | 422 | false | Markdown mutation cho Product đã có tombstone watermark |
| `INTERNAL_ERROR` | 500 | false | Lỗi không lường trước (log stack trace); không dùng cho lỗi của client |
| `PROMOTION_UNAVAILABLE` | 503 | true | PostgreSQL/Redis tạm thời lỗi, lock timeout, Product validation không gọi được, pause/cancel không đóng được claim gate |
| `RUNTIME_NOT_READY` | 503 | true | Redis runtime của promotion đang rebuild sau Redis loss |

`CLAIM_PENDING` không phải `ErrorCode`: là body của `202` (`code=CLAIM_PENDING`, `retryable=true`) khi Redis
acquisition có evidence nhưng DB chưa xác nhận trước HTTP deadline; retry cùng user/promotion, claim sẽ preflight DB
và pending rồi resume.

## 11. Ví dụ

- Campaign kết thúc lúc 20:00. Markdown chuyển `INACTIVE`; từ 20:00 không claim mới.
  Wallet đã lưu có `expired_at=23:59` vẫn dùng được đến thời điểm đó.
- Limited claim bắt đầu Lua đúng 20:00 bị gate từ chối; claim linearize 19:59:59.999 vẫn
  được worker persist sau 20:00 bằng acquisition evidence.
- Voucher giảm 100.000đ được confirm cho order: wallet `USED`, budget used tăng 100.000đ.
  Buyer refund một selected-line scope (mỗi line full quantity) hay Admin refund toàn
  order trước dispatch: wallet và Promotion budget đều giữ nguyên.
- Confirm thành công sau Payment success mà order còn lỗi Saga giữ durable allocation/wallet
  `USED`; Order retries forward repair or opens financial reconciliation. `cancel-confirmed`
  is deprecated legacy cleanup only and cannot be used to make an active post-capture order
  appear unredeemed.
- START job revision 4 chạy sau pause/cancel đã tăng revision 5: worker no-op.
