---
doc_id: promotion-workflows
service: promotion
scope: current
status: implemented
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/events/promotion/v1/asyncapi.yaml
verified_against_commit: null
---

# Promotion Service — Workflows

## Workflow index

| ID | Workflow | Trigger |
|---|---|---|
| `WF-PROMO-001` | Quản trị và publish campaign | Admin HTTP |
| `WF-PROMO-002` | Kích hoạt/kết thúc campaign | Redis schedule + DB fallback |
| `WF-PROMO-003` | Claim/save voucher | Customer HTTP + internal worker |
| `WF-PROMO-004` | Discovery và calculate | Checkout HTTP |
| `WF-PROMO-005` | TRY/CONFIRM/CANCEL | Order Temporal activities |
| `WF-PROMO-006` | `cancel-confirmed` legacy cleanup (deprecated) | Migration/ops only; not active Order compensation |
| `WF-PROMO-007` | Markdown snapshot → Product | Outbox + CDC |
| `WF-PROMO-008` | Rescue/finalizer/reconciliation/cleanup | Scheduled workers |
| `WF-PROMO-009` | Product tombstone invalidation | Product Kafka event |

Không có Promotion workflow cho customer refund, welcome voucher qua Auth event hay phát
coupon fact; WF-PROMO-007 là workflow duy nhất ghi outbox.

## WF-PROMO-001 — Quản trị và publish campaign

1. Admin gọi `POST /api/v1/admin/promotion-campaigns` để tạo campaign `DRAFT`, sau đó
   tạo voucher/markdown children qua các collection dưới campaign.
2. Application validate AST, validity mode, product IDs, money/basis points và budget.
   Với create/update markdown, sau synchronous Product validation, PostgreSQL transaction acquire
   product advisory lock và reject nếu terminal tombstone watermark đã tồn tại.
3. Khi campaign còn DRAFT, child không có lịch/status/revision riêng và mọi mutation tăng
   `campaigns.revision`.
4. Khi publish, transaction từ chối nếu `end_time <= now`, pre-generate đúng
   `total_claim_quantity` row `coupon_claims` cho mỗi promotion `LIMITED_CLAIM`, set
   `publication_status=PUBLISHED`, tăng revision và tạo outbox snapshot (trigger `PUBLISH`)
   nếu campaign đã ở trong effective window.
5. Sau publish commit, application `ZADD` START/END, dựng các claim-gate fields trong
   `promo:{promotion_id}:runtime` thành disabled/upcoming hoặc enabled theo DB time/revision,
   invalidate promotion snapshots và tăng cache version fence.
6. Nếu ZADD/runtime/invalidation thất bại, mutation không fail-open và không rollback PostgreSQL command đã
   commit. Schedule được DB fallback scan/startup rebuild khôi phục; claim-gate/pool được dựng lại khi startup
   hoặc theo yêu cầu ở request claim đầu tiên gặp `GATE_MISSING` (rebuild từ PostgreSQL rồi retry một lần).

Definition đã publish là bất biến. Muốn thay discount, eligibility, validity, product
scope hoặc budget definition phải clone thành campaign DRAFT với campaign/promotion/
markdown ID mới (clone bỏ qua markdown rule đã `invalidated` hoặc của Product đã có tombstone watermark để
không tạo dangling rule; budget được sao chép là cấu hình, counter bắt đầu từ 0, không sao chép claim hay wallet). `campaigns.revision` chỉ là concurrency/cache/schedule fence, không phải
definition version; wallet cũ tiếp tục bind promotion cũ và `expired_at` đã snapshot.

Pause/cancel đóng claim-gate fields trong runtime hash một cách bảo thủ trước DB
transaction, rồi tăng revision và phát markdown `INACTIVE` snapshot nếu cần. Nếu **không đóng được** gate của bất
kỳ promotion `LIMITED_CLAIM` nào (Redis lỗi), lệnh thất bại `503 PROMOTION_UNAVAILABLE` (retryable) và rollback:
campaign giữ nguyên `PUBLISHED`/revision, không outbox, để admin không tưởng claim đã dừng trong khi Redis vẫn cấp
suất; các gate đã đóng trước đó được mở lại như dưới. Nếu DB rollback sau khi gate đã đóng, hook `afterRollback`
của transaction rebuild/reopen fields từ DB ngay (`RuntimeRebuildService.reopenAfterRollback`, transaction
riêng); nếu bước mở lại cũng lỗi (Redis) thì chỉ log và gate giữ đóng đến lần rebuild kế tiếp (startup hoặc
transition kế). Crash giữa chừng chỉ gây false reject tạm thời, không cấp claim sau khi close đã bắt đầu. Chúng
dừng claim mới/markdown nhưng không tự thu hồi voucher đã lưu.

## WF-PROMO-002 — Campaign START/END chính xác

### Happy path

1. Loop lấy due members bằng `ZRANGEBYSCORE`, không destructive pop.
2. Đọc lại PostgreSQL và so campaign ID, revision, publication status, start/end.
3. Stale member hoặc status không còn phù hợp → không phát business state; cleanup member.
4. Tính phase bằng DB clock và interval `[start,end)`.
5. Duyệt rule của campaign bằng keyset `id > afterId` theo `promotion.schedule.emit-batch-size` (mặc định
   200) và xử lý **mỗi lô trong một PostgreSQL transaction riêng** (ADR-021). Trong transaction của lô:
   a. đọc lại campaign; `revision`/`publication_status` lệch member → dừng, coi member lỗi thời (bước 3);
   b. loại các product đã có causal dedup key bằng **một** query `dedup_key IN (...)` (bộ lọc không khoá,
      không lock/tính winner/tiêu `source_revision` cho phần đã phát);
   c. với mỗi product còn lại: acquire PostgreSQL advisory transaction lock theo product, tính
      current-state snapshot (`ACTIVE` winner hoặc `INACTIVE` tombstone), cấp `source_revision` và insert
      outbox bằng deterministic causal key (`ON CONFLICT DO NOTHING` vẫn là hàng rào chính xác).
6. Một lô lỗi chỉ rollback chính nó; worker log/đếm rồi tiếp tục các lô sau. Khi **không còn lô lỗi** (mọi
   lô đã commit, hoặc member lỗi thời) mới `ZREM` member; còn lô lỗi thì giữ member để vòng sau chạy lại cả
   campaign (lô đã commit bị bước 5b bỏ qua). Không dùng Redis schedule lock: nếu worker khác đã cùng
   xử lý member, outbox causal dedup làm insert no-op và `ZREM` vẫn idempotent.

START/END vì vậy không nguyên tử theo campaign: trong khoảng ngắn một số product đã có snapshot mới còn số
khác chưa. Downstream chỉ dựa vào `source_revision` theo từng product nên không phát sinh sai trạng thái.
Publish/pause/cancel (WF-PROMO-001) là ngoại lệ: snapshot nằm trong transaction của lệnh admin, không chia lô.

Nếu START được xử lý khi `now >= end_time`, worker không phát `ACTIVE`; nó phát hoặc
xác nhận snapshot `INACTIVE` để hội tụ. Public save/API eligibility tự tính phase bằng DB
clock; limited claim Lua tự kiểm Redis-time gate nên scheduler lag không mở claim sớm hoặc
kéo dài claim sau end.

### Recovery

- DB fallback mỗi 10–30 giây tìm boundary còn thiếu/quá hạn và enqueue/process lại.
- Startup/failover rebuild ZSET từ campaign PUBLISHED chưa qua mọi boundary.
- Crash sau outbox commit trước ZREM → retry: product đã phát bị dedup pre-check bỏ qua, no-op rồi cleanup.
- Crash/lỗi giữa các lô → lô đã commit giữ nguyên, member còn trong ZSET; vòng sau (hoặc fallback) chạy lại
  và chỉ phát phần còn thiếu. Lô lỗi vĩnh viễn do dữ liệu hỏng không chặn các lô khác; xử lý theo RB-05.
- Redis mất toàn bộ schedule không làm mất source state trong PostgreSQL.

## WF-PROMO-003 — Claim/save voucher

### Limited flash-sale claim

1. Publish/rebuild ghi claim-gate fields vào `promo:{promotion_id}:runtime`, cùng hash tag
   với pool: enabled, campaign revision, `opens_at_ms` và `closes_at_ms` lấy từ PostgreSQL.
2. Request mang expected revision. Lua gọi Redis `TIME`, từ chối gate disabled/stale hoặc
   `now_ms < opens_at_ms || now_ms >= closes_at_ms` trước khi chạm pool.
3. Trong cùng Lua, kiểm `claimed_users`, `SPOP` claim ID, rồi ghi claimed user và pending
   payload gồm `user_id`, `claim_id`, `acquired_at_ms`, campaign revision; ZADD rescue index.
4. Lua success là acquisition linearization point. API dùng pending evidence để chạy ngay
   PostgreSQL persistence; không trả success chỉ dựa trên Redis.
5. API hoặc pending worker kiểm identity và revision-at-acquisition nhất quán, nhưng không
   yêu cầu revision đó bằng current campaign revision. Sau đó guarded update claim
   `AVAILABLE→CLAIMED`, insert wallet `SAVED`, tính/snapshot `expired_at` và tăng claimed
   counter trong một PostgreSQL transaction; không ghi outbox.
6. Persistence dùng acquisition time, không dùng processing time để bác claim đã lấy đúng
   hạn. API chỉ trả success và chỉ HDEL/ZREM pending sau DB commit. Nếu chưa xác nhận DB
   trước HTTP deadline, trả retryable `CLAIM_PENDING`/202 thay vì success.

Mỗi retry preflight durable claim/wallet trước Lua; durable hit replay success. API crash
sau SPOP được pending sweeper cứu bằng cùng immutable `claim_id`; không trả suất vào pool
và không xóa pending trước DB commit. Vì success đã durable, Redis loss/rebuild không thể
làm hai caller cùng nhận success cho một claim ID; DB conditional update chọn đúng một.

Hai request đồng thời của **cùng user** cùng promotion dùng chung một `claim_id` (Lua trả
`ALREADY_CLAIMED` kèm pending). Request thua phải trả đúng wallet của request thắng, giống
PROMO-BR-027 của Confirm, không phải lỗi: (a) nếu guarded update trả 0 dòng vì claim đã `CLAIMED`
bởi chính user, đọc lại wallet sau khi request thắng commit rồi trả replay `created=false`; (b) nếu
Lua trả `ALREADY_CLAIMED` không kèm pending (request thắng đã commit và HDEL pending), đọc lại wallet
trước khi từ chối `ALREADY_CLAIMED`. Chỉ khi claim thuộc user nhưng wallet vẫn không có (xóa thủ công)
mới là `FINAL_COMMAND_STATE_INCONSISTENT`.

### Public save/direct grant

`PUT /api/v1/me/promotion-vouchers/{promotion_id}` hoặc
`POST /api/v1/admin/promotions/{promotion_id}/grants` tạo unique wallet. Public save kiểm campaign ACTIVE và
promotion enabled bằng PostgreSQL clock trong cùng transaction với wallet insert; direct grant
kiểm campaign `PUBLISHED` và `now < end_time`. Nếu `total_claim_quantity` hữu hạn, cả hai
tăng `claimed_quantity` bằng guarded update và trả `OUT_OF_STOCK` khi đã chạm total. Existing
SAVED còn hạn trả idempotent success mà không kéo dài expiry; USED bị từ chối. Không có
default 30 ngày: expiry lấy từ validity policy. Public save chỉ evaluate khi request có
`context`; trang checkout gửi body rỗng và nhận `evaluation: null`, discount chính thức đến từ
Checkout Preview (`context` tùy chọn, ADR-018).

## WF-PROMO-004 — Discovery và calculate

### Wallet tray

1. `POST /api/v1/me/promotion-voucher-suggestions` query wallet `(user_id,status)` và
   lazy-normalize SAVED đã hết hạn thành EXPIRED.
2. Voucher SAVED, `expired_at > now` vẫn được xét dù campaign tự nhiên đã ENDED; với
   validity mode `CAMPAIGN_END`, `expired_at` trùng end nên voucher bị loại theo expiry.
3. Duyệt wallet `SAVED` bằng keyset theo wallet ID; projection chỉ lấy ID, promotion ID và expiry.
   Batch load snapshot JSON, parse AST request-scoped, evaluate context; heap giữ top `limit`.
4. Budget read guard có thể downgrade thành `BUDGET_EXHAUSTED`; timeout fail-open với
   degraded marker vì Reserve mới là quyết định cuối.

### Public discovery

`POST /api/v1/promotion-discovery` ngoài wallet còn query public promotions enabled thuộc campaign phase ACTIVE.
SQL `NOT EXISTS` loại promotion user đã sở hữu ở mọi status trước khi lấy batch ID theo
`id > afterId ORDER BY id ASC`. Duyệt đến hết candidate, kể cả khi đã tìm đủ `limit` item eligible.
Campaign đã end không xuất hiện cho save mới. Mỗi request dùng cùng một `now` cho mọi batch.

Snapshot: đọc version một lần mỗi batch, pipeline GET theo ID; cache miss/JSON lỗi/version lệch dùng một
projection query join promotion–campaign, rồi pipeline ghi cache với TTL. Rule evaluation đi trước budget;
chỉ voucher vượt điều kiện cần pipeline HMGET runtime. Runtime thiếu → `NOT_CHECKED`, Redis lỗi →
`DEGRADED` fail-open; budget exhausted → ineligible với discount 0. Không thực hiện round trip riêng cho từng voucher.

Heap tối đa `limit` item dùng chung cho wallet/public: lọc `include_ineligible` trước heap, eligible trước,
discount giảm dần, promotion ID tăng dần. Sort heap khi trả response. Kích thước batch cấu hình bằng
`promotion.discovery.batch-size` (mặc định 200, phải >= 1); không có trần candidate hoặc cursor API.
Chi tiết: [ADR-020](./decisions/ADR-020-complete-discovery-with-batched-top-k.md).

### Checkout voucher options (ADR-018)

Checkout gọi `POST /internal/v1/promotion-voucher-options` với `user_id` và
`TrustedCheckoutContext` dựng từ báo giá. Promotion chạy cùng bước wallet tray và public
discovery ở trên (theo `include_public`/`include_ineligible`), giữ ranking, rồi gắn thông tin
hiển thị từ definition snapshot (`code`, `name`, `description`, `discount`,
`min_order_amount`, `valid_until`). `valid_until` là `expired_at` của wallet với voucher `WALLET`
và `end_time` của campaign với voucher `PUBLIC`. Mặc định `include_public` và
`include_ineligible` là `true`, `limit` là 50. Không save, claim hay reserve; không local rate
limit. Context phải là `TrustedCheckoutContext` (`checkout_channel=CHECKOUT`, khác thì `400`); response
`Cache-Control: no-store`. Phần public dùng cùng keyset scan và heap top `limit` toàn cục ở trên;
không giới hạn 200 candidate.

### Calculate

Checkout gọi `POST /internal/v1/promotion-discount-calculations`, gửi 0/1 platform voucher
và subtotal/context toàn đơn. Promotion trả một discount
toàn đơn, không trả allocation theo SKU. Rule evaluation không side effect và cùng
AST/context phải cho cùng kết quả.

## WF-PROMO-005 — Checkout TRY/CONFIRM/CANCEL

### TRY

1. Chỉ sau Submit và khi đã có `order_id`, Order gọi
   `POST /internal/v1/promotion-allocation/reservations`. Context phải là `TrustedCheckoutContext`
   (`checkout_channel=CHECKOUT`, khác thì `400 INVALID_REQUEST` trước mọi bước đọc/ghi).
2. Promotion revalidate wallet SAVED/expiry/rule và requested amount.
3. Lua kiểm replay payload, available quantity/amount, tăng integer reserved counter,
   ghi reservation payload + per-promotion expiry ZSET.
4. Thất bại giữa apply phải rollback các Redis mutation; trả structured error.

### CONFIRM

1. Order gọi `POST /internal/v1/promotion-allocation/confirmations` với
   `Idempotency-Key: REDEEM_FINAL:{order_id}:{promotion_id}` và expected amount. Canonical
   request hash bao gồm operation/order/promotion/expected amount.
2. Promotion preflight PostgreSQL receipt theo key và business identity. Existing cùng
   hash trả stored response rồi kick finalizer/repair; khác hash trả
   `IDEMPOTENCY_PAYLOAD_MISMATCH`. Durable replay không cần reservation Redis còn tồn tại.
3. Nếu chưa có receipt, Redis Confirm-Prepare Lua verify reservation/payload. Matching
   `CONFIRMING` là replay; state `RESERVED` đổi thành `CONFIRMING` và gia hạn grace nhưng
   chưa move reserved/used counter.
4. Nếu Prepare báo missing, recheck PostgreSQL receipt để đóng race với request khác vừa
   commit/finalize. Chỉ khi recheck vẫn miss mới trả `RESERVATION_NOT_FOUND` cho
   late-payment policy.
5. PostgreSQL transaction acquire `CONFIRM` command receipt. Concurrent existing cùng hash
   trả stored response; khác hash bị từ chối. Receipt được kiểm trước và kiểm lại sau khi giành khoá
   wallet: bản trùng đến đồng thời chờ khoá rồi trả stored response của bản thắng (PROMO-BR-027), không phải
   `FINAL_COMMAND_STATE_INCONSISTENT`.
6. Với command mới, transaction lock wallet/budget, dùng authoritative amount từ matching
   reservation `CONFIRMING`, guarded `SAVED→USED` (cả `EXPIRED→USED` nếu truy vấn của user đã chuẩn hoá
   wallet sau Reserve: reservation tạo khi còn hạn là authority, PROMO-BR-026), persist amount vào wallet, set
   `used_order_id`/`used_at`, tăng durable used counters và hoàn thiện receipt với
   `redis_finalized_at = NULL`. Không insert outbox: không có consumer cho redeemed fact.
7. PostgreSQL commit là durable completion point. Sau commit Redis finalizer mới atomically
   move reserved→used và xóa payload/index; không ghi applied marker trong Redis. Khi Lua
   trả matching-finalized hoặc already-finalized, application `UPDATE ... SET
   redis_finalized_at = NOW() WHERE redis_finalized_at IS NULL`.
8. Duplicate/retry sau commit trả stored response và kick finalizer/repair lại, không mutate
   wallet/budget lần hai.

Crash trước DB commit không đổi durable state; reservation `CONFIRMING` còn grace để retry.
Crash sau commit trước Redis finalize luôn có receipt `redis_finalized_at IS NULL` để
finalizer worker chạy lại. Finalizer thiếu runtime payload không được increment mù; enqueue
reconciliation từ wallet/budget DB rồi mới set `redis_finalized_at` khi projection đã hội
 tụ. Reservation mất và không có receipt mới trả `RESERVATION_NOT_FOUND`. Nếu Payment đã
 success, Order không được compensate bằng cancel confirmed allocation: retry cùng final
 command khi có thể, nếu không thì mở financial reconciliation/forward repair. Chỉ verified
 late-payment recovery mới có thể tạo reservation mới trước một CONFIRM mới.

### CANCEL trước confirm

Order gọi `POST /internal/v1/promotion-allocation/cancellations`. Lua chỉ release
reservation đang `RESERVED`, rồi xóa payload/index. Reservation
`CONFIRMING` không được cancel mù; caller phải đọc durable confirm receipt/outcome. Wallet
vẫn `SAVED` nếu confirm chưa commit; PostgreSQL không tạo usage row hoặc customer-refund
record. Missing payload chưa đủ để giả định đã release; worker đối chiếu receipt/wallet và
enqueue repair nếu cần.

## WF-PROMO-006 — `cancel-confirmed` legacy cleanup (deprecated)

Flow này chỉ giữ để cleanup/migration record legacy với bằng chứng audit bất biến rằng nó
chưa vượt Payment financial watermark. Nó không phải customer refund và không được active
Order checkout, post-payment hay late-payment workflow gọi. Only the dedicated
`promotion-legacy-migrator` mTLS principal can invoke the endpoint; `order-service` is
denied by mesh and application policy (`403 FORBIDDEN` từ allowlist `legacy-migration-clients`).

1. `promotion-legacy-migrator` gọi `POST /internal/v1/promotion-allocation/technical-cancellations`
   với `Idempotency-Key: CANCEL_FINAL:{order_id}:{promotion_id}`, header
   `X-Legacy-Migration-Proof=<migration-record-uuid>` và body `LegacyTechnicalCancellationRequest`
   (`migration_record_id` bằng header, `pre_financial_watermark_evidence_sha256` 64 hex; không field lạ,
   sai schema `400`). Missing/mismatched proof or an `order-service` principal is denied (`403`, trước mọi
   đọc/ghi). Promotion không tra cứu record trong registry nào: proof được lưu bất biến để audit.
2. PostgreSQL transaction acquire `CANCEL_CONFIRMED` command receipt theo canonical request
   hash (gồm record ID và digest). Cùng key/hash đã complete trả stored response (kể cả bản trùng đến
   đồng thời); khác hash — kể cả khác record hoặc digest — bị từ chối (`IDEMPOTENCY_PAYLOAD_MISMATCH`).
3. Với command mới, transaction lock wallet/budget, bắt buộc wallet `USED` bởi đúng order và
   lấy authoritative amount trước khi clear usage fields.
4. Transaction giảm durable used counter bằng guarded update; underflow/inconsistent state
   rollback toàn transaction, không clamp.
5. Nếu DB time `< wallet.expired_at`, wallet `USED→SAVED`; nếu đã hết hạn thì
   `USED→EXPIRED`. Clear `used_order_id`, `discount_amount_vnd`, `used_at`.
6. Hoàn thiện stored response trong receipt với `redis_finalized_at = NULL` trong cùng
   transaction; commit là durable completion point. Không insert outbox: không có consumer
   cho technical-cancel fact.
7. Sau commit trigger Redis projection repair từ DB rồi set `redis_finalized_at`. Không
   chạy Redis decrement trước DB và không replay delta theo event; duplicate repair được
   phép.

Crash trước commit rollback receipt/wallet/budget cùng nhau. Crash sau commit luôn có
receipt để replay response và `redis_finalized_at IS NULL` để finalizer worker kick repair.
Redis unavailable không làm durable compensation thất bại; Redis có thể tạm over-count used
 (bảo thủ) cho tới khi hội tụ. Lỗi hệ thống vì vậy không làm khách mất voucher còn hạn.
 Active flow sau `PAYMENT_SUCCESS` giữ wallet `USED` và forward-repair hoặc chuyển case về
 Order financial reconciliation; nó không tạo `CANCEL_FINAL`.

## Customer refund — explicit non-workflow

Khi Order/Payment hoàn tiền một selected-line scope của Buyer (mỗi line là full
quantity) hoặc toàn order theo nhánh Admin trước dispatch, luôn chỉ có một lifetime
refund case:

- Không gọi endpoint Promotion.
- Promotion không consume `t.order.events`; Order Events v1 không phát refund fact.
- Wallet giữ `USED`.
- `used_quantity` và `used_budget_vnd` không giảm.
- Không tái cấp voucher và không quy đổi voucher thành tiền.
- Không truyền `promotion_refund_allocations[]`.

Order locks gross selected-line amount capped at `final_amount_minor`; Promotion behavior
does not depend on that calculation and remains no-op.

## WF-PROMO-007 — Markdown snapshot → Product

Mọi thay đổi effective markdown phát cùng một canonical current-state snapshot event:
`com.ecommerce.promotion.product-markdown-snapshot.v1`, key `product_id`.

1. Transaction lấy advisory lock theo product (scheduler START/END: nhiều product trong cùng transaction
   của một lô — ADR-021; admin publish/pause/cancel: trong transaction của lệnh admin).
2. Tính winning markdown từ các rule enabled, chưa invalidated và có campaign ACTIVE.
3. Cấp monotonic `source_revision`.
4. Tạo causal dedup key gồm product, campaign, campaign revision và trigger. Resulting
   state không nằm trong key; retry cùng cause luôn dùng đúng một key và campaign khác
   không collision.
5. Insert `ACTIVE` với full winning rule/discount/interval. Nếu không còn winner, insert
   `INACTIVE` tombstone chỉ có product, source revision và reason
   `NO_ACTIVE_RULE|EXPIRED|ADMIN_DISABLED`.
6. Debezium relay at-least-once.
7. Product apply chỉ khi revision mới hơn, reprice SKU và phát catalog event cho Search.

Không phát delta `MARKDOWN_CREATED/UPDATED/ENDED`; Product không cần safety scheduler.

## WF-PROMO-008 — Rescue, reconciliation và cleanup

- Pending claim: mỗi phút duyệt promotion non-DRAFT, persist lại item cũ hơn ngưỡng 5 phút
  bằng cùng guarded DB path (`ClaimPersistenceService`); giữ pending tới DB commit. Pending
  mà claim đã thuộc user khác (`FINAL_COMMAND_STATE_INCONSISTENT`) được log error và dọn để
  không retry vô hạn.
- Runtime claim fields/pool: rebuild từ PostgreSQL khi startup/Redis loss; stale revision
  không fail-open. Clock drift Redis–PostgreSQL chưa có alert tự động; kiểm thủ công theo RB-03A.
- Reservation: mỗi phút duyệt promotion ID từ PostgreSQL (campaign non-DRAFT, không key scan
  Redis), lấy due item theo per-promotion ZSET; `RESERVED` → release; `CONFIRMING` có receipt
  → finalize, hết grace không receipt → force release (Order đi late-payment policy); payload
  mất → xóa index và reconcile.
- Final-command finalizer: inline sau commit và worker quét receipt
  `redis_finalized_at IS NULL` quá ngưỡng. `CONFIRM` chỉ finalize matching `CONFIRMING`;
  legacy `CANCEL_CONFIRMED` chạy DB→Redis projection repair. Thiếu runtime evidence thì reconcile,
  không increment/decrement mù; chỉ set `redis_finalized_at` sau khi Redis hội tụ.
- Campaign schedule: loop 250 ms–1 s; DB fallback 10–30 s; startup rebuild.
- Budget reconciliation: định kỳ dựng expected used từ wallet `USED`, claimed từ claim
  rows và reserved từ payload hợp lệ; dùng `reconcile_lock`, re-read rồi repair Redis một
  chiều.
- Wallet expiry: lazy transition; có thể thêm batch normalization nhưng không ảnh hưởng
  eligibility dựa trực tiếp `expired_at`.
- Outbox retention: chỉ trim theo CDC watermark/replay horizon; không xóa row chưa relay.
- Command receipt retention: cleanup chỉ sau `retain_until` và maximum Temporal
  retry/late-recovery horizon; độc lập outbox retention.
- Claim cleanup: chỉ sau campaign end và retention/audit window được duyệt. Chưa triển khai: Redis
  `claim_pool`/`claimed_users`/`runtime` hiện không bị dọn (known gap, `data.md` §8).

Mọi worker bounded batch, retry-safe và có metric oldest-age/backlog/drift.

## WF-PROMO-009 — Product tombstone invalidation

Promotion consume `com.ecommerce.catalog.product-deleted.v1`, key `product_id`:

1. Validate CloudEvent/schema/source và lấy `product_id`, `catalog_revision`.
2. PostgreSQL transaction acquire cùng product advisory lock với admin markdown mutation,
   upsert `catalog_product_tombstones` chỉ tiến tới catalog revision lớn hơn, rồi lock
   affected markdown rules theo ID tăng dần. Watermark vẫn được persist khi chưa có rule.
3. Với rule có `source_product_revision IS NULL OR source_product_revision < watermark`, set
   `invalidated_at`, `invalidation_reason=PRODUCT_DELETED` và watermark revision. Duplicate/
   stale event không lùi watermark nhưng vẫn có thể repair rule bất thường; không bump
   campaign revision hoặc claim-gate fields vì voucher definition không đổi.
4. Commit; Kafka ack sau commit. Không invalidate cache: markdown rule không nằm trong Redis và snapshot
   voucher theo promotion không đổi (ADR-019).
5. Không phát markdown `INACTIVE` ngược lại Product đã hard delete. Search nhận Product
   tombstone trực tiếp; scheduler/winner query bỏ mọi invalidated rule.

Nếu Product không tồn tại khi admin tạo/cập nhật DRAFT markdown, synchronous Product
validation từ chối save. Nếu delete xảy ra sau validation, shared advisory lock bảo đảm
hoặc rule commit trước rồi bị consumer invalidate, hoặc watermark commit trước và mutation
bị reject. Product UUID đã tombstone không được reuse.

## Verification bắt buộc

- Boundary tests cho exact start/end, stale revision, delayed START, pause/cancel và clock UTC.
- Crash DB-commit-before-ZREM, missing ZSET, Redis restart và duplicate outbox.
- START/END theo lô: số transaction bằng số lô; lô lỗi cô lập, giữ member, retry chỉ phát phần thiếu và không
  tiêu sequence; campaign đổi revision giữa lô; hai worker cùng một member (ADR-021).
- Load 10.000 contender; duplicate user/work item; pending rescue.
- Claim gate end−1 ms/end/end+1 ms, stale/disabled revision, Redis restart, clock drift và
  worker persist acquisition hợp lệ sau end.
- Reserve payload mismatch, confirm fence/finalize, integer counter invariant và
  confirm/cancel/sweep races.
- Confirm/`cancel-confirmed` cùng key same/different hash, concurrent duplicate, duplicate
  confirm sau Redis cleanup, race preflight-miss/finalizer, crash trước/sau DB commit,
  crash giữa Redis finalize và set `redis_finalized_at` (worker chạy lại phải no-op) và
  stored-response replay.
- `cancel-confirmed` SAVED-vs-EXPIRED, Redis unavailable và DB→Redis convergence.
- Buyer selected-line hoặc Admin pre-dispatch refund chứng minh không có Promotion mutation/event subscription.
- Product bỏ stale `source_revision` và hội tụ ACTIVE/INACTIVE.
- Causal dedup với hai campaign cùng revision/product/state và retry cùng cause sau khi
  state đã đổi; ACTIVE/INACTIVE contract shape.
- Product tombstone duplicate/out-of-order, tombstone-before-mutation/mutation-before-tombstone,
  no event loop và dangling-rule cleanup.
- Published definition clone giữ nguyên promotion/wallet cũ; không update in-place.
- Không có `t.promotion.budget.events` hay coupon topic; outbox chỉ chứa markdown
  snapshot; exhausted chỉ xuất hiện trong sync response/metric.
