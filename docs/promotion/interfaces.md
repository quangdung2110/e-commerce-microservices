---
doc_id: promotion-interfaces
service: promotion
scope: current
status: implemented
owners:
  - Promotion Service team
evidence:
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/events/promotion/v1/asyncapi.yaml
  - contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json
  - contracts/events/product/v1/asyncapi.yaml
  - contracts/examples/events/promotion-product-markdown-snapshot.v1.json
  - contracts/examples/events/promotion-product-markdown-inactive-snapshot.v1.json
verified_against_commit: null
---

# Promotion Service — Interfaces

> HTTP contract chuẩn nằm tại
> [`contracts/http/promotion/v1/openapi.yaml`](../../contracts/http/promotion/v1/openapi.yaml);
> Kafka contract chuẩn nằm tại
> [`contracts/events/promotion/v1/asyncapi.yaml`](../../contracts/events/promotion/v1/asyncapi.yaml).
> Cả hai đã `implemented` trong `java/services/promotion-service`: HTTP có MockMvc contract
> test (`HttpContractIntegrationTest`), CloudEvent markdown snapshot có JSON Schema contract
> test (`MarkdownSnapshotContractTest`) và Product consumer đã chấp nhận INACTIVE tombstone.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumer | Status |
|---|---|---|---|---|---|
| Admin campaign/voucher/markdown | Inbound | HTTP | Promotion | Admin/Gateway | `implemented` (provider test; consumer là frontend admin, type sinh từ OpenAPI) |
| Calculate/voucher options/discovery/save/claim | Inbound | HTTP | Promotion | Checkout/Gateway | `implemented` (provider test; Checkout validate fixture theo OpenAPI, chưa có consumer test với Promotion thật) |
| Allocation lifecycle (reserve/confirm/cancel) | Inbound | HTTP | Promotion | Order/Temporal | `implemented` (provider test; Order gọi qua `HttpGateways`, chưa có consumer test với Promotion thật) |
| Technical cancellation (deprecated) | Inbound | HTTP | Promotion | `promotion-legacy-migrator` | provider `implemented` (guard + test); consumer chưa tồn tại nên interaction vẫn `proposed` |
| Markdown current-state snapshot | Outbound | Kafka/CDC | Promotion | Product | `implemented` (JSON Schema contract test + `OutboxCdcIntegrationTest` với Debezium/Kafka thật; chưa chạy cùng Product consumer) |
| Product tombstone | Inbound | Kafka | Product | Promotion | `implemented` (consumer + test service-level; chưa test qua Kafka thật) |

Đây là toàn bộ bề mặt tích hợp v1. Không có inbound `ORDER_REFUNDED`, Auth welcome-voucher
consumer, Promotion refund API, coupon/wallet event, budget event hay reservation event.
Các fact đó không có consumer nào đăng ký trong repository; thêm sau là additive.

## 2. HTTP APIs

User API lấy identity từ header `X-User-Id`/`X-User-Roles` do Gateway inject sau khi verify
token; admin API yêu cầu role `ADMIN`. `/internal/v1/**` chỉ đi trong service mesh: caller được
phép do `AuthorizationPolicy` thực thi theo principal — `checkout-service` cho
`/internal/v1/promotion-discount-calculations`, `order-service` cho
`/internal/v1/promotion-allocation/**` ([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
principal sai bị waypoint trả `403` `text/plain` trước khi tới Promotion. Header
`X-Service-Identity` do caller tự đặt (cùng quy ước với Product); Promotion allowlist header theo
nhóm route (`promotion.internal.*` trong `application.yml`) làm defense-in-depth và attribution,
không phải bằng chứng authorization — mesh không xác minh giá trị header. Trong docker-compose
chưa có mesh nên header này chỉ an toàn ở mức dev. API dùng JSON snake_case,
`application/problem+json` theo RFC 9457, ID opaque string và tiền theo
`{ amount_minor, currency }` (`currency=VND`). BIGINT/`*_vnd` trong data model chỉ là
storage representation, không phải wire shape.

Request không khớp operation nào là lỗi client: route lạ trả `404`, sai HTTP method trả `405` (kèm `Allow`)
và Content-Type operation không chấp nhận (vd `PATCH` không phải `application/merge-patch+json`) trả `400`,
mọi trường hợp là problem+json code `INVALID_REQUEST` (không có `415`; cùng quy ước Checkout). Identity được
kiểm trước routing, nên route `/internal/v1/**` lạ vẫn là `401`/`403` cho caller chưa được allowlist.
Danh mục mã lỗi đầy đủ (code, HTTP, `retryable`) ở [domain.md §10](./domain.md#10-domain-errors).

### 2.1 Admin endpoints

| Method và path | Mục đích | Concurrency/idempotency |
|---|---|---|
| `GET /api/v1/admin/promotion-campaigns` | List campaign theo cursor/status/phase | Safe read |
| `POST /api/v1/admin/promotion-campaigns` | Tạo campaign `DRAFT` | Unique campaign code |
| `GET /api/v1/admin/promotion-campaigns/{campaign_id}` | Đọc campaign và ETag revision | Safe read |
| `PATCH /api/v1/admin/promotion-campaigns/{campaign_id}` | Sửa metadata/lịch khi còn `DRAFT` | Bắt buộc `If-Match` parent revision |
| `POST /api/v1/admin/promotion-campaigns/{campaign_id}/copies` | Clone aggregate thành campaign `DRAFT` mới | Source immutable; `If-Match` source revision |
| `POST /api/v1/admin/promotion-campaigns/{campaign_id}/transitions` | Publish, pause hoặc cancel | `If-Match`; tăng revision đúng một lần |
| `GET/POST /api/v1/admin/promotion-campaigns/{campaign_id}/promotions` | List/tạo voucher definition | Mutation chỉ ở `DRAFT`, tăng parent revision |
| `GET/PATCH /api/v1/admin/promotions/{promotion_id}` | Đọc/sửa voucher definition | PATCH dùng `If-Match` parent campaign |
| `POST /api/v1/admin/promotions/{promotion_id}/grants` | Cấp voucher `DIRECT_GRANT` cho user | Natural key `(user_id,promotion_id)` |
| `GET/POST /api/v1/admin/promotion-campaigns/{campaign_id}/markdown-rules` | List/tạo markdown product-scope | Mutation chỉ ở `DRAFT`, tăng parent revision |
| `GET/PATCH /api/v1/admin/markdown-rules/{markdown_rule_id}` | Đọc/sửa markdown rule | PATCH dùng `If-Match` parent campaign |

### 2.2 Customer qua Gateway và Checkout internal endpoints

| Method và path | Dùng khi nào | Side effect |
|---|---|---|
| `POST /api/v1/me/promotion-voucher-suggestions` | Client tự có checkout context; chỉ xếp hạng voucher `SAVED` trong wallet. Trang checkout không dùng | Safe query ngoài lazy expiry; `Cache-Control: no-store` |
| `POST /api/v1/promotion-discovery` | Client tự có checkout context, tìm cả wallet và public `ACTIVE` chưa lưu. Trang checkout không dùng | Safe query, không auto-save/reserve; `Cache-Control: no-store` |
| `PUT /api/v1/me/promotion-vouchers/{promotion_id}` | User lưu public voucher; body là tùy chọn (không body và `{}` đều hợp lệ), trang checkout gửi body rỗng, evaluate chỉ khi có `context` (`context` tùy chọn, ADR-018) | Tạo mới `201`; replay wallet `SAVED` trả `200`, không kéo dài expiry; không context thì `evaluation: null` |
| `POST /api/v1/me/promotion-voucher-claims` | User claim voucher `LIMITED_CLAIM` | `201` sau DB commit; có thể `202 CLAIM_PENDING` để retry |
| `POST /internal/v1/promotion-discount-calculations` | Checkout server tính lại 0/1 voucher từ trusted context; `checkout_channel` phải là `CHECKOUT` | Pure/replay-safe; không save và không reserve |
| `POST /internal/v1/promotion-voucher-options` | ADR-018: Checkout dựng khay voucher của trang checkout từ trusted snapshot; chỉ `checkout-service`; `checkout_channel` phải là `CHECKOUT` | Safe query như discovery, `Cache-Control: no-store`; trả `evaluation` + `display` (`code`, `name`, `description`, `discount`, `min_order_amount`, `valid_until`) |

Khay voucher của trang checkout đi qua Checkout
`GET /api/v1/checkout/previews/{checkout_token}/voucher-options`
([Checkout ADR-017](../checkout/decisions/ADR-017-voucher-options-read-composition.md)) vì chỉ
Checkout có giá và `category_ids` tin cậy; Gateway áp quota ở route đó.

Hai query customer dùng POST vì checkout context chứa nhiều item/category và không phù hợp
query string; POST ở đây không đồng nghĩa mutation. `payment_method` hiện bị khóa `STRIPE`
theo Checkout Phase 18. Gateway áp profile `promotion-discovery` 20 request/phút/user cho
`POST /api/v1/promotion-discovery`; Promotion không lưu local rate-limit state.

### 2.3 Order/Temporal endpoints

| Method và path | Pha saga | Kết quả chính |
|---|---|---|
| `POST /internal/v1/promotion-allocation/reservations` | TRY sau Submit, trước payment; `checkout_channel` phải là `CHECKOUT`; promotion không tồn tại → `404` | Giữ quota/amount 15 phút; `200` replay hoặc `201` tạo mới |
| `POST /internal/v1/promotion-allocation/confirmations` | CONFIRM sau payment success | Commit wallet `USED`, durable budget và receipt; Redis finalize sau commit |
| `POST /internal/v1/promotion-allocation/cancellations` | CANCEL khi payment chưa confirm/thất bại | Chỉ nhả reservation `RESERVED`; wallet vẫn `SAVED` |
| `POST /internal/v1/promotion-allocation/technical-cancellations` | **Deprecated, legacy migration only** | Dedicated `promotion-legacy-migrator` workload + immutable `X-Legacy-Migration-Proof` (UUID bằng `migration_record_id` của body); `order-service` và mọi caller khác `403`; thiếu/sai proof `403`; body `LegacyTechnicalCancellationRequest` (`migration_record_id`, `pre_financial_watermark_evidence_sha256`, không field lạ) sai schema `400` |

Không có promotion refund endpoint. Customer refund không gọi Promotion. Với active
checkout, `CONFIRM` sau `PAYMENT_SUCCESS` là financial watermark: mọi lỗi phía sau
phải retry/forward-repair hoặc vào financial reconciliation; không được dùng technical
cancellation để trả wallet/budget về chưa redeem. Endpoint technical-cancellation chỉ
giữ cho cleanup/migration legacy có workload capability và evidence digest chứng minh chưa vượt watermark.

Discovery/calculate nhận subtotal toàn đơn, item/category context, payment method và các
field Rule Engine cần. Response trả `eligible`, reason, estimated/actual discount,
`rule_schema_version`, `campaign_revision`, `distribution_mode` và budget-guard metadata.
Discount không được chia thành allocation theo SKU. `distribution_mode` cho client biết
item `PUBLIC` cần `PUT /me/promotion-vouchers/{id}` (`PUBLIC_SAVE`) hay
`POST /me/promotion-voucher-claims` với `campaign_revision` làm `expected_campaign_revision`
(`LIMITED_CLAIM`); public discovery liệt kê cả hai mode, loại promotion user đã sở hữu.
Ranking deterministic: eligible trước, discount giảm dần, `promotion_id` tăng dần;
`/me/promotion-voucher-suggestions` trả cả item không đủ điều kiện kèm `reason`, còn
`/promotion-discovery` chỉ trả item ineligible khi `include_ineligible=true`.

Gateway và Checkout hiện không có authority cho `user.tier`, nên rule chứa
`user.tier` evaluate `false` ở discovery/suggestion/save/calculate/reserve khi
`user_tier` bị omit/null. Ở v1 Checkout/Order luôn omit/null; hỗ trợ tier sau này cần
contract version đồng bộ với một trusted service snapshot. `context.facts` không được ghi đè `user.*`. Checkout gửi
`checkout_channel=CHECKOUT` cố định và lấy `category_ids` từ Product gRPC, không
nhận hai fact này từ browser; Order cũng gửi `CHECKOUT` khi Reserve. Vì vậy calculate, voucher options và
reserve (`TrustedCheckoutContext`) từ chối channel khác `CHECKOUT` bằng `400 INVALID_REQUEST`
(`errors[].field = context.checkout_channel`) trước mọi bước đọc/ghi; context của suggestion, discovery và save
là của client nên channel đó chỉ là một fact do client khai.

Public discovery và voucher options đánh giá tối đa 200 promotion public đang `ACTIVE` (theo `id` tăng, trước khi
loại promotion user đã sở hữu) rồi mới sắp xếp và áp `limit`; vượt ngưỡng đó cần phân trang phía Promotion.

`campaign_revision` là optimistic concurrency/cache/schedule fence, không phải version ID
của published definition. Sau publish, field ảnh hưởng business là immutable; thay đổi
phải clone campaign DRAFT và child IDs mới. Claim request mang expected revision; Lua chỉ
cho phép acquisition khi claim-gate fields hiện hành trong runtime hash cùng revision và
Redis time nằm trong
`[opens_at,closes_at)`. Claim endpoint preflight durable wallet/claim trước Lua và chỉ trả
success sau PostgreSQL persistence commit; quá HTTP deadline trả `202 CLAIM_PENDING`.

### 2.4 Durable final-command idempotency

Confirm dùng `REDEEM_FINAL:{order_id}:{promotion_id}`; deprecated legacy cleanup dùng
`CANCEL_FINAL:{order_id}:{promotion_id}` và chỉ được gọi bởi
`promotion-legacy-migrator` với migration proof bất biến; nó bị cấm cho mọi active
post-capture Order flow. Promotion canonicalize operation, order,
promotion và expected amount thành SHA-256 `request_hash` (legacy cancel thêm `migration_record_id` và
digest bằng chứng đã chuẩn hoá chữ thường, nên replay với proof khác là `IDEMPOTENCY_PAYLOAD_MISMATCH`;
cả hai được lưu trên receipt):

- cùng key + cùng hash → trả stored response từ `promotion_command_receipts`;
- cùng key + khác hash → `IDEMPOTENCY_PAYLOAD_MISMATCH`;
- key khác nhưng cùng `(operation_type,promotion_id,order_id)` → replay cùng business
  outcome hoặc conflict nếu payload khác; không tạo transition thứ hai.

Amount trong request chỉ là expected value. Confirm dùng amount từ matching reservation
`CONFIRMING`; legacy `cancel-confirmed` dùng amount đã persist trên wallet `USED`. Receipt, wallet
và durable budget commit cùng PostgreSQL transaction; final command không ghi outbox.
Receipt với `redis_finalized_at IS NULL` là durable trigger để Redis finalizer/repair chạy
lại sau crash; outbox chỉ dành cho markdown snapshot và có retention độc lập.

Confirm luôn lookup receipt theo key và `(operation_type,promotion_id,order_id)` trước
Redis. Nếu đã có durable outcome thì replay không phụ thuộc reservation còn tồn tại. Khi
preflight miss nhưng Confirm-Prepare báo missing, Promotion phải lookup receipt lần nữa để
đóng race với request khác vừa commit/finalize; chỉ khi vẫn không có receipt mới trả
`RESERVATION_NOT_FOUND`. State `CONFIRMING` cùng payload là replay hợp lệ của Prepare.
`Idempotency-Key` phải khớp đúng `order_id`/`promotion_id` trong body, nếu không trả `400`.
Hai request cùng key đến đồng thời: request thua cuộc kiểm lại receipt sau khi giành khoá wallet và trả đúng
stored response của request thắng (không `FINAL_COMMAND_STATE_INCONSISTENT`; PROMO-BR-027).

## 3. Allocation semantics

- Reserve chỉ hợp lệ với wallet `SAVED`, `expired_at > now` và rule đạt tại Submit. Rule
  không đạt trả `422 CONDITIONS_NOT_MET`; `expected_discount` khác discount Promotion tính
  trả `409 EXPECTED_DISCOUNT_MISMATCH` (Order phải lấy amount từ calculate mới nhất).
  Reservation payload hash gồm order, promotion, user và amount; cùng order khác hash trả
  `409 IDEMPOTENCY_PAYLOAD_MISMATCH`.
- `POST .../cancellations` trả `RELEASED` (đã nhả `RESERVED`), `NO_RESERVATION` (không còn
  payload hoặc đã có durable `CONFIRM` receipt) hoặc `REPAIR_REQUIRED` (payload đang
  `CONFIRMING` chưa có receipt — sweeper nhả sau grace nếu vẫn không có receipt; hoặc index còn
  member nhưng payload mất — Promotion đã xoá member và chạy reconciliation DB→Redis); caller
  không được coi `REPAIR_REQUIRED` là đã release. Counter reserved lệch (`DRIFT`) cũng được
  reconcile và trả `RELEASED`.
- Voucher đã lưu có thể reserve sau natural campaign end nếu `expired_at` còn hạn;
  mode `CAMPAIGN_END` đương nhiên hết hạn đúng boundary. Campaign status không phải
  revocation check; emergency revocation chưa thuộc contract này.
- Confirm-Prepare chỉ fence `RESERVED→CONFIRMING` và gia hạn grace, chưa move counter.
  PostgreSQL commit ghi receipt, `used_order_id`, `discount_amount_vnd`, `used_at` và
  budget; Redis finalizer chạy sau commit rồi set `redis_finalized_at`. Wallet `SAVED` và wallet đã bị
  lazy-normalize thành `EXPIRED` sau Reserve đều chuyển sang `USED` (reservation là authority, PROMO-BR-026);
  wallet ở trạng thái khác là `FINAL_COMMAND_STATE_INCONSISTENT`.
- Cancel pre-payment chỉ nhả Redis reservation đang `RESERVED`; không release mù state
  `CONFIRMING` khi chưa đọc durable confirm outcome.
- `cancel-confirmed` là deprecated legacy migration cleanup; mesh/application chỉ nhận
  `promotion-legacy-migrator` + `X-Legacy-Migration-Proof`, không dùng cho active Order flow,
  late-payment recovery, customer refund, hay bất cứ failure nào sau `PAYMENT_SUCCESS`.
  Order giữ voucher `USED` và retry/repair confirmation; nếu proof reservation/receipt
  không thể khôi phục, Order mở financial reconciliation thay vì đảo allocation.
- Buyer selected-line hay Admin pre-dispatch refund không có call/event tới Promotion.

## 4. Kafka envelope và publication

Business integration event được insert vào `promotion_outbox_events` cùng transaction
với business state. Debezium/Kafka Connect relay at-least-once. Consumer phải idempotent.
Outbox payload là CloudEvents 1.0 structured JSON hoàn chỉnh; `id` là outbox event ID,
`source=/services/promotion`, time UTC và partition key ổn định. Ở v1 outbox chỉ chứa một
message type: markdown snapshot. Contract chuẩn là
[`contracts/events/promotion/v1/asyncapi.yaml`](../../contracts/events/promotion/v1/asyncapi.yaml).

### 4.1 Product markdown snapshot

Canonical event:

```text
type:  com.ecommerce.promotion.product-markdown-snapshot.v1
topic: t.promotion.markdown.events
key:   product_id
```

Payload chung luôn có `product_id UUID`, `source_revision BIGINT` và
`state: ACTIVE | INACTIVE`. Hai state có shape tách biệt:

- `ACTIVE`: bắt buộc `promotion_rule_id`, discount data, `starts_at`, `ends_at`; không có
  `inactive_reason`.
- `INACTIVE`: bắt buộc `inactive_reason=NO_ACTIVE_RULE|EXPIRED|ADMIN_DISABLED`; không mang
  rule ID, discount hoặc interval.

Promotion tính snapshot chiến thắng hiện hành cho product. Event publish-in-window,
START/END, pause hoặc cancel đều dùng cùng event type; DRAFT edit không phát effective
snapshot. Không dùng các event delta
`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`. Product chỉ apply khi
`source_revision` lớn hơn projection hiện tại. Outbox dedup key gồm product, causal
campaign ID/revision và trigger; resulting state không thuộc key để một cause chỉ phát tối
đa một snapshot. `source_revision` chỉ dùng ordering.

Schema do Promotion sở hữu tại
[`contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json`](../../contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json);
Product AsyncAPI tham chiếu message qua `$ref`. `discount` dùng integer VND theo Product
catalog convention và `promotion_rule_id` là integer theo Product ADR-016; hai điểm này
khác convention Money/opaque ID của HTTP v1 và chỉ đổi được ở v2.

### 4.2 Không có coupon fact

Promotion không phát coupon claimed/saved, redeemed hay technical-cancelled event.
Notification và Analytics không đăng ký các fact này. Order Events v1 cũng không mang
voucher snapshot cho Analytics; nếu cần pipeline voucher phải thiết kế consumer contract
riêng. Client nhận kết quả claim/save đồng bộ qua HTTP.
Wallet transition được ghi trong PostgreSQL và có thể thêm event sau khi có consumer thật.
Cũng không có coupon-refunded hoặc budget-exhausted fact. `BUDGET_EXHAUSTED` chỉ xuất hiện
trong discovery/reserve response, metric và log; Redis observation không được giả làm
transactional outbox event.

## 5. Internal claim persistence

Claim Lua commit acquisition trên Redis trước PostgreSQL nên pending Hash/ZSET là recovery
source của crash window, không phải durable success record. API gọi guarded claim update +
unique wallet trong cùng DB transaction và chỉ trả success sau commit; không ghi outbox.
Sweeper chạy lại cùng persistence function bằng `claim_id` bất biến; không cần một Kafka
business event/topic riêng. Chỉ sau DB commit mới xóa pending Redis.

Pending payload bắt buộc có `acquired_at_ms` lấy từ Redis TIME và campaign revision đã
được claim-gate fields kiểm atomically. Worker validate evidence này nhưng không dùng processing
time để từ chối claim đã linearize trước close boundary.

Nếu DB chưa xác nhận trước HTTP deadline, response là retryable `202 CLAIM_PENDING`; retry
preflight DB rồi pending để resume. Không được trình bày Redis acquisition hoặc internal
task như business event exactly-once.

## 6. Events tiêu thụ

| Event | Hành động | Guard |
|---|---|---|
| `com.ecommerce.catalog.product-deleted.v1` | Persist terminal Product watermark và invalidate mọi markdown rule; không bump campaign revision/claim-gate fields hoặc emit ngược | Shared product advisory lock, monotonic `catalog_revision`, conditional invalidation |

Đây là event inbound duy nhất ở v1. Promotion không consume Auth event: welcome voucher
không thuộc scope v1, và nếu cần sẽ dùng lazy grant khi user gọi API wallet lần đầu hoặc
admin `DIRECT_GRANT` thay vì phụ thuộc Auth best-effort delivery. `user-deleted.v1` là
known gap cần ADR retention/anonymization riêng. Promotion không subscribe
`t.order.events`; Order Events v1 không có refund fact và refund payload không cần
`promotion_refund_allocations[]`.

## 7. Compatibility

- Unknown optional JSON field phải được chấp nhận; unsupported major version không xử
  lý như v1.
- Markdown contract v1 đã `implemented` và dùng state-discriminated shape. Nếu v1 đã được
  deploy trước thay đổi này, phải tạo v2/dual-read thay vì đổi breaking in-place.
- Ordering key của markdown luôn là `product_id`; duplicate/replay là bình thường.
- Product tombstone consumer chỉ nhận `product-deleted`, persist watermark dù chưa có rule,
  ack sau DB commit và không dùng `promotion_command_receipts` vốn dành riêng final
  synchronous command.
- Không công bố internal claim-persistence task, confirm finalizer hay projection repair
  thành integration event; chúng chạy từ Redis pending evidence và command receipt.
- Thêm message type mới vào AsyncAPI v1 là additive; đổi payload/key/ordering của markdown
  snapshot là breaking. Contract tests đã có: `HttpContractIntegrationTest` (OpenAPI v1) và
  `MarkdownSnapshotContractTest` (AsyncAPI/JSON Schema v1); còn thiếu consumer test chạy với Promotion thật.
