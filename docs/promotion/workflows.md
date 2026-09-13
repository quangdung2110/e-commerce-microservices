---
doc_id: promotion-workflows
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

# Promotion Service — Workflows

## Workflow index

| ID | Workflow | Trigger |
|---|---|---|
| `WF-PROMO-001` | Quản trị và publish campaign | Admin HTTP |
| `WF-PROMO-002` | Kích hoạt/kết thúc campaign | Redis schedule + DB fallback |
| `WF-PROMO-003` | Claim/save voucher | Customer HTTP + internal worker |
| `WF-PROMO-004` | Discovery và calculate | Checkout HTTP |
| `WF-PROMO-005` | TRY/CONFIRM/CANCEL | Order Temporal activities |
| `WF-PROMO-006` | `cancel-confirmed` kỹ thuật | Order Temporal compensation |
| `WF-PROMO-007` | Markdown snapshot → Product | Outbox + CDC |
| `WF-PROMO-008` | Rescue/reconciliation/cleanup | Scheduled workers |
| `WF-PROMO-009` | Product tombstone invalidation | Product Kafka event |

Không có Promotion workflow cho customer refund.

## WF-PROMO-001 — Quản trị và publish campaign

1. Admin tạo campaign `DRAFT` với `[start_time,end_time)`, voucher/markdown children.
2. Application validate AST, validity mode, product IDs, money/basis points và budget.
   Với create/update markdown, sau synchronous Product validation, PostgreSQL transaction acquire
   product advisory lock và reject nếu terminal tombstone watermark đã tồn tại.
3. Khi campaign còn DRAFT, child không có lịch/status/revision riêng và mọi mutation tăng
   `campaigns.revision`.
4. Khi publish, transaction set `publication_status=PUBLISHED`, tăng revision và tạo
   outbox snapshot cần thiết nếu campaign đã ở trong effective window.
5. Sau publish commit, application `ZADD` START/END, dựng các claim-gate fields trong
   `promo:{promotion_id}:runtime` thành disabled/upcoming hoặc enabled theo DB time/revision,
   invalidate promotion snapshots và tăng cache version fence.
6. Nếu ZADD/runtime/invalidation thất bại, mutation không fail-open; DB fallback/startup
   rebuild tự khôi phục rồi mới ready, không rollback PostgreSQL command đã commit.

Definition đã publish là bất biến. Muốn thay discount, eligibility, validity, product
scope hoặc budget definition phải clone thành campaign DRAFT với campaign/promotion/
markdown ID mới. `campaigns.revision` chỉ là concurrency/cache/schedule fence, không phải
definition version; wallet cũ tiếp tục bind promotion cũ và `expired_at` đã snapshot.

Pause/cancel đóng claim-gate fields trong runtime hash một cách bảo thủ trước DB
transaction, rồi tăng revision và phát markdown `INACTIVE` snapshot nếu cần. Nếu DB
rollback, worker rebuild/reopen fields từ DB; crash chỉ gây false reject tạm thời, không
cấp claim sau khi close đã bắt đầu. Chúng dừng claim mới/markdown nhưng không tự thu hồi
voucher đã lưu.

## WF-PROMO-002 — Campaign START/END chính xác

### Happy path

1. Loop lấy due members bằng `ZRANGEBYSCORE`, không destructive pop.
2. Đọc lại PostgreSQL và so campaign ID, revision, publication status, start/end.
3. Stale member hoặc status không còn phù hợp → không phát business state; cleanup member.
4. Tính phase bằng DB clock và interval `[start,end)`.
5. Với mỗi affected product, acquire PostgreSQL advisory transaction lock theo product,
   tính current-state snapshot (`ACTIVE` winner hoặc `INACTIVE` tombstone), cấp
   `source_revision` và insert outbox bằng deterministic causal key.
6. Commit DB rồi mới `ZREM` member. Không dùng Redis schedule lock: nếu worker khác đã
   cùng xử lý member, outbox causal dedup làm insert no-op và `ZREM` vẫn idempotent.

Nếu START được xử lý khi `now >= end_time`, worker không phát `ACTIVE`; nó phát hoặc
xác nhận snapshot `INACTIVE` để hội tụ. Public save/API eligibility tự tính phase bằng DB
clock; limited claim Lua tự kiểm Redis-time gate nên scheduler lag không mở claim sớm hoặc
kéo dài claim sau end.

### Recovery

- DB fallback mỗi 10–30 giây tìm boundary còn thiếu/quá hạn và enqueue/process lại.
- Startup/failover rebuild ZSET từ campaign PUBLISHED chưa qua mọi boundary.
- Crash sau outbox commit trước ZREM → retry trùng dedup key, no-op rồi cleanup.
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
   `AVAILABLE→CLAIMED`, insert wallet `SAVED`, tính/snapshot `expired_at`, tăng claimed
   counter và nếu cần insert public claim fact vào outbox trong một PostgreSQL transaction.
6. Persistence dùng acquisition time, không dùng processing time để bác claim đã lấy đúng
   hạn. API chỉ trả success và chỉ HDEL/ZREM pending sau DB commit. Nếu chưa xác nhận DB
   trước HTTP deadline, trả retryable `CLAIM_PENDING`/202 thay vì success.

Mỗi retry preflight durable claim/wallet trước Lua; durable hit replay success. API crash
sau SPOP được pending sweeper cứu bằng cùng immutable `claim_id`; không trả suất vào pool
và không xóa pending trước DB commit. Vì success đã durable, Redis loss/rebuild không thể
làm hai caller cùng nhận success cho một claim ID; DB conditional update chọn đúng một.

### Public save/direct grant

`save-and-apply` hoặc admin grant tạo unique wallet. Public save kiểm campaign ACTIVE và
promotion enabled bằng PostgreSQL clock trong cùng transaction với wallet insert. Existing
SAVED còn hạn trả idempotent success mà không kéo dài expiry; USED bị từ chối. Không có
default 30 ngày: expiry lấy từ validity policy.

## WF-PROMO-004 — Discovery và calculate

### Wallet tray

1. Query wallet `(user_id,status)` và lazy-normalize SAVED đã hết hạn thành EXPIRED.
2. Voucher SAVED, `expired_at > now` vẫn được xét dù campaign tự nhiên đã ENDED; với
   validity mode `CAMPAIGN_END`, `expired_at` trùng end nên voucher bị loại theo expiry.
3. Batch load snapshot JSON, parse AST request-scoped, evaluate context.
4. Budget read guard có thể downgrade thành `BUDGET_EXHAUSTED`; timeout fail-open với
   degraded marker vì Reserve mới là quyết định cuối.

### Public discovery

Ngoài wallet, query public promotions enabled thuộc campaign phase ACTIVE, loại promotion
user đã sở hữu, evaluate/sort/limit. Campaign đã end không xuất hiện cho save mới.

### Calculate

Checkout gửi 0/1 platform voucher và subtotal/context toàn đơn. Promotion trả một discount
toàn đơn, không trả allocation theo SKU. Rule evaluation không side effect và cùng
AST/context phải cho cùng kết quả.

## WF-PROMO-005 — Checkout TRY/CONFIRM/CANCEL

### TRY

1. Chỉ sau Submit và khi đã có `order_id`, Order gọi `/reserve`.
2. Promotion revalidate wallet SAVED/expiry/rule và requested amount.
3. Lua kiểm replay payload, available quantity/amount, tăng integer reserved counter,
   ghi reservation payload + per-promotion expiry ZSET.
4. Thất bại giữa apply phải rollback các Redis mutation; trả structured error.

### CONFIRM

1. Order gọi stable `REDEEM_FINAL:{order_id}:{promotion_id}` và expected amount. Canonical
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
   trả stored response; khác hash bị từ chối.
6. Với command mới, transaction lock wallet/budget, dùng authoritative amount từ matching
   reservation `CONFIRMING`, guarded `SAVED→USED`, persist amount vào wallet, set
   `used_order_id`/`used_at`, tăng durable used counters, hoàn thiện receipt và insert
   redeemed outbox fact.
7. PostgreSQL commit là durable completion point. Sau commit Redis finalizer mới atomically
   move reserved→used và xóa payload/index; không ghi applied marker.
8. Duplicate/retry sau commit trả stored response và kick finalizer/repair lại, không mutate
   wallet/budget lần hai.

Crash trước DB commit không đổi durable state; reservation `CONFIRMING` còn grace để retry.
Crash sau commit trước Redis finalize luôn có receipt/outbox để chạy lại. Finalizer thiếu
runtime payload không được increment mù; enqueue reconciliation từ wallet/budget DB.
Reservation mất và không có receipt mới trả `RESERVATION_NOT_FOUND` để Order chạy
late-payment policy.

### CANCEL trước confirm

Lua chỉ release reservation đang `RESERVED`, rồi xóa payload/index. Reservation
`CONFIRMING` không được cancel mù; caller phải đọc durable confirm receipt/outcome. Wallet
vẫn `SAVED` nếu confirm chưa commit; PostgreSQL không tạo usage row hoặc customer-refund
record. Missing payload chưa đủ để giả định đã release; worker đối chiếu receipt/wallet và
enqueue repair nếu cần.

## WF-PROMO-006 — `cancel-confirmed` do lỗi kỹ thuật

Chỉ dùng khi Promotion đã confirm nhưng checkout/order saga không thể hoàn tất vì lỗi kỹ
thuật. Đây không phải customer refund.

1. Order gọi `/cancel-confirmed` với stable
   `CANCEL_FINAL:{order_id}:{promotion_id}` và expected confirmed amount.
2. PostgreSQL transaction acquire `CANCEL_CONFIRMED` command receipt theo canonical request
   hash. Cùng key/hash đã complete trả stored response; khác hash bị từ chối.
3. Với command mới, transaction lock wallet/budget, bắt buộc wallet `USED` bởi đúng order và
   lấy authoritative amount trước khi clear usage fields.
4. Transaction giảm durable used counter bằng guarded update; underflow/inconsistent state
   rollback toàn transaction, không clamp.
5. Nếu DB time `< wallet.expired_at`, wallet `USED→SAVED`; nếu đã hết hạn thì
   `USED→EXPIRED`. Clear `used_order_id`, `discount_amount_vnd`, `used_at`.
6. Hoàn thiện stored response trong receipt và insert technical-cancel outbox fact trong
   cùng transaction; commit là durable completion point.
7. Sau commit trigger Redis projection repair từ DB. Không chạy Redis decrement trước DB
   và không replay delta theo event; duplicate repair được phép.

Crash trước commit rollback receipt/wallet/budget/outbox cùng nhau. Crash sau commit luôn
có receipt để replay response và outbox để kick repair. Redis unavailable không làm durable
compensation thất bại; Redis có thể tạm over-count used (bảo thủ) cho tới khi hội tụ. Lỗi hệ
thống vì vậy không làm khách mất voucher còn hạn.

## Customer refund — explicit non-workflow

Khi Order/Payment hoàn tiền partial, nhiều lần partial hoặc full-order:

- Không gọi endpoint Promotion.
- Promotion không consume `ORDER_REFUNDED`.
- Wallet giữ `USED`.
- `used_quantity` và `used_budget_vnd` không giảm.
- Không tái cấp voucher và không quy đổi voucher thành tiền.
- Không truyền `promotion_refund_allocations[]`.

Số lần partial refund và cách tính amount vẫn thuộc thiết kế Order/Payment chưa được
refactor; Promotion behavior không phụ thuộc quyết định đó.

## WF-PROMO-007 — Markdown snapshot → Product

Mọi thay đổi effective markdown phát cùng một canonical current-state snapshot event:
`com.ecommerce.promotion.product-markdown-snapshot.v1`, key `product_id`.

1. Transaction theo product lấy advisory lock.
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

- Pending claim: mỗi phút persist lại item cũ hơn ngưỡng 5 phút bằng cùng guarded DB path;
  giữ pending tới DB commit.
- Runtime claim fields/pool: rebuild từ PostgreSQL khi startup/Redis loss; stale revision
  không fail-open. Alert Redis–PostgreSQL clock drift vượt ngưỡng.
- Reservation: mỗi phút lấy due item theo per-promotion ZSET; release hoặc repair.
- Confirm finalizer: consume durable receipt/outbox trigger; chỉ finalize matching
  `CONFIRMING`, thiếu runtime evidence thì reconcile, không increment mù.
- Campaign schedule: loop 250 ms–1 s; DB fallback 10–30 s; startup rebuild.
- Budget reconciliation: định kỳ dựng expected used từ wallet `USED`, claimed từ claim
  rows và reserved từ payload hợp lệ; dùng `reconcile_lock`, re-read rồi repair Redis một
  chiều.
- Wallet expiry: lazy transition; có thể thêm batch normalization nhưng không ảnh hưởng
  eligibility dựa trực tiếp `expired_at`.
- Outbox retention: chỉ trim theo CDC watermark/replay horizon; không xóa row chưa relay.
- Command receipt retention: cleanup chỉ sau `retain_until` và maximum Temporal
  retry/late-recovery horizon; độc lập outbox retention.
- Claim cleanup: chỉ sau campaign end và retention/audit window được duyệt.

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
4. Commit rồi invalidate product-scoped Promotion snapshot; Kafka ack sau commit.
5. Không phát markdown `INACTIVE` ngược lại Product đã hard delete. Search nhận Product
   tombstone trực tiếp; scheduler/winner query bỏ mọi invalidated rule.

Nếu Product không tồn tại khi admin tạo/cập nhật DRAFT markdown, synchronous Product
validation từ chối save. Nếu delete xảy ra sau validation, shared advisory lock bảo đảm
hoặc rule commit trước rồi bị consumer invalidate, hoặc watermark commit trước và mutation
bị reject. Product UUID đã tombstone không được reuse.

## Verification bắt buộc

- Boundary tests cho exact start/end, stale revision, delayed START, pause/cancel và clock UTC.
- Crash DB-commit-before-ZREM, missing ZSET, Redis restart và duplicate outbox.
- Load 10.000 contender; duplicate user/work item; pending rescue.
- Claim gate end−1 ms/end/end+1 ms, stale/disabled revision, Redis restart, clock drift và
  worker persist acquisition hợp lệ sau end.
- Reserve payload mismatch, confirm fence/finalize, integer counter invariant và
  confirm/cancel/sweep races.
- Confirm/`cancel-confirmed` cùng key same/different hash, concurrent duplicate, duplicate
  confirm sau Redis cleanup, race preflight-miss/finalizer, crash trước/sau DB commit và
  stored-response replay.
- `cancel-confirmed` SAVED-vs-EXPIRED, Redis unavailable và DB→Redis convergence.
- Customer partial/full refund chứng minh không có Promotion mutation/event subscription.
- Product bỏ stale `source_revision` và hội tụ ACTIVE/INACTIVE.
- Causal dedup với hai campaign cùng revision/product/state và retry cùng cause sau khi
  state đã đổi; ACTIVE/INACTIVE contract shape.
- Product tombstone duplicate/out-of-order, tombstone-before-mutation/mutation-before-tombstone,
  no event loop và dangling-rule cleanup.
- Published definition clone giữ nguyên promotion/wallet cũ; không update in-place.
- Không có `t.promotion.budget.events`; exhausted chỉ xuất hiện trong sync response/metric.
