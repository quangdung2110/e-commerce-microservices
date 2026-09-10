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

Không có Promotion workflow cho customer refund.

## WF-PROMO-001 — Quản trị và publish campaign

1. Admin tạo campaign `DRAFT` với `[start_time,end_time)`, voucher/markdown children.
2. Application validate AST, validity mode, product IDs, money/basis points và budget.
3. Child không có lịch/status/revision riêng. Mọi mutation tăng `campaigns.revision`.
4. Khi publish, transaction set `publication_status=PUBLISHED`, tăng revision và tạo
   outbox snapshot cần thiết nếu campaign đã ở trong effective window.
5. Sau commit, application `ZADD` START/END vào `promo:campaign:schedule`, invalidate
   promotion snapshots và tăng cache version fence.
6. Nếu ZADD/invalidation thất bại, DB fallback/startup rebuild tự khôi phục; không rollback
   PostgreSQL command đã commit.

Definition đã publish được coi bất biến. Thay đổi cấu hình đáng kể phải tạo revision
mới và không được sửa hồi tố `expired_at` của wallet đã lưu.

Pause/cancel tăng revision và phát markdown `INACTIVE` snapshot nếu cần. Chúng dừng claim
mới/markdown nhưng không tự thu hồi voucher đã lưu.

## WF-PROMO-002 — Campaign START/END chính xác

### Happy path

1. Loop lấy due members bằng `ZRANGEBYSCORE`, không destructive pop.
2. Worker acquire lock ngắn theo campaign.
3. Đọc lại PostgreSQL và so campaign ID, revision, publication status, start/end.
4. Stale member hoặc status không còn phù hợp → không phát business state; cleanup member.
5. Tính phase bằng DB clock và interval `[start,end)`.
6. Với mỗi affected product, acquire PostgreSQL advisory transaction lock theo product,
   tính full winning markdown snapshot, cấp `source_revision` và insert outbox bằng
   deterministic dedup key.
7. Commit DB rồi mới `ZREM` member.

Nếu START được xử lý khi `now >= end_time`, worker không phát `ACTIVE`; nó phát hoặc
xác nhận snapshot `INACTIVE` để hội tụ. Vì API eligibility tự tính phase, scheduler lag
không cho claim sớm hoặc tiếp tục claim sau end.

### Recovery

- DB fallback mỗi 10–30 giây tìm boundary còn thiếu/quá hạn và enqueue/process lại.
- Startup/failover rebuild ZSET từ campaign PUBLISHED chưa qua mọi boundary.
- Crash sau outbox commit trước ZREM → retry trùng dedup key, no-op rồi cleanup.
- Redis mất toàn bộ schedule không làm mất source state trong PostgreSQL.

## WF-PROMO-003 — Claim/save voucher

### Limited flash-sale claim

1. Preconditions: campaign phase ACTIVE, promotion enabled, validity config hợp lệ.
2. Lua trên cùng hash tag `{promotion_id}` kiểm tra `claimed_users`, `SPOP` claim ID,
   ghi claimed user, pending Hash và pending ZSET.
3. API publish internal claim work item rồi trả success.
4. Worker guarded update claim `AVAILABLE→CLAIMED`, insert wallet `SAVED`, tính/snapshot
   `expired_at`, tăng claimed counter trong một PostgreSQL transaction.
5. Nếu cần public claim fact, transaction cũng insert outbox.
6. Chỉ sau DB commit mới HDEL/ZREM pending.

API crash sau SPOP được pending sweeper cứu. Sweeper republish cùng immutable `claim_id`,
không trả suất vào pool và không xóa pending trước DB ack.

### Public save/direct grant

`save-and-apply` hoặc admin grant tạo unique wallet. Public save chỉ hợp lệ khi campaign
ACTIVE. Existing SAVED còn hạn trả idempotent success mà không kéo dài expiry; USED bị
từ chối. Không có default 30 ngày: expiry lấy từ validity policy.

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

1. Order gọi stable confirm key theo order/promotion.
2. Lua move reserved→used, xóa payload/index.
3. PostgreSQL transaction guarded wallet `SAVED→USED`, set `used_order_id`,
   `discount_amount_vnd`, `used_at`, tăng used counters và insert outbox redeemed fact.
4. Duplicate dựa state/unique/dedup key trả idempotent success.

Redis success nhưng DB fail chưa phải hoàn tất; retry/reconciliation phải hội tụ. Missing
reservation trả `RESERVATION_NOT_FOUND` để Order chạy late-payment policy.

### CANCEL trước confirm

Lua release reserved rồi xóa payload/index. Wallet vẫn `SAVED`; PostgreSQL không tạo usage
row hoặc customer-refund record. Missing payload chưa đủ để giả định đã release; worker
đối chiếu state và enqueue repair nếu cần.

## WF-PROMO-006 — `cancel-confirmed` do lỗi kỹ thuật

Chỉ dùng khi Promotion đã confirm nhưng checkout/order saga không thể hoàn tất vì lỗi kỹ
thuật. Đây không phải customer refund.

1. Order gọi `/cancel-confirmed` với stable order/promotion key và confirmed amount.
2. Redis Lua giảm used counter idempotently theo allocation đã confirm.
3. PostgreSQL transaction insert outbox row với unique technical-cancel dedup key.
4. Transaction giảm durable used counter.
5. Nếu `now < wallet.expired_at`, wallet `USED→SAVED` và clear usage fields.
6. Nếu voucher đã hết hạn, wallet `USED→EXPIRED`.
7. Commit rồi phát technical-cancel fact qua CDC nếu có consumer.

Duplicate không mutate lần hai. Lỗi hệ thống vì vậy không làm khách mất voucher còn hạn.

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

Mọi thay đổi effective markdown phát cùng một canonical full snapshot event:
`com.ecommerce.promotion.product-markdown-snapshot.v1`, key `product_id`.

1. Transaction theo product lấy advisory lock.
2. Tính winning markdown từ các rule enabled có campaign ACTIVE.
3. Cấp monotonic `source_revision`.
4. Insert full snapshot `ACTIVE` với winning rule hoặc `INACTIVE` nếu không còn rule.
5. Debezium relay at-least-once.
6. Product apply chỉ khi revision mới hơn, reprice SKU và phát catalog event cho Search.

Không phát delta `MARKDOWN_CREATED/UPDATED/ENDED`; Product không cần safety scheduler.

## WF-PROMO-008 — Rescue, reconciliation và cleanup

- Pending claim: mỗi phút republish item cũ hơn ngưỡng 5 phút; giữ pending tới DB ack.
- Reservation: mỗi phút lấy due item theo per-promotion ZSET; release hoặc repair.
- Campaign schedule: loop 250 ms–1 s; DB fallback 10–30 s; startup rebuild.
- Budget reconciliation: định kỳ dựng expected used từ wallet `USED`, claimed từ claim
  rows và reserved từ payload hợp lệ; lock/re-read rồi repair Redis một chiều.
- Wallet expiry: lazy transition; có thể thêm batch normalization nhưng không ảnh hưởng
  eligibility dựa trực tiếp `expired_at`.
- Outbox retention: chỉ trim theo CDC watermark/replay horizon; không xóa row chưa relay.
- Claim cleanup: chỉ sau campaign end và retention/audit window được duyệt.

Mọi worker bounded batch, retry-safe và có metric oldest-age/backlog/drift.

## Verification bắt buộc

- Boundary tests cho exact start/end, stale revision, delayed START, pause/cancel và clock UTC.
- Crash DB-commit-before-ZREM, missing ZSET, Redis restart và duplicate outbox.
- Load 10.000 contender; duplicate user/work item; pending rescue.
- Reserve payload mismatch, integer counter invariant, confirm/cancel/sweep races.
- `cancel-confirmed` SAVED-vs-EXPIRED và duplicate retry.
- Customer partial/full refund chứng minh không có Promotion mutation/event subscription.
- Product bỏ stale `source_revision` và hội tụ ACTIVE/INACTIVE.
