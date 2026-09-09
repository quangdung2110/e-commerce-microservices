---
doc_id: promotion-workflows
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
verified_against_commit: null
---

# Promotion Service — Workflows

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-PROMO-001` | Tạo/cập nhật promotion hoặc markdown | HTTP command | Admin | proposed |
| `WF-PROMO-002` | Claim voucher flash-sale | HTTP + Kafka | Customer | proposed |
| `WF-PROMO-003` | Tính discount | HTTP query | Checkout | proposed |
| `WF-PROMO-004` | Checkout TRY/CONFIRM/CANCEL | Temporal activities | Order | proposed |
| `WF-PROMO-005` | Refund/cancel-confirmed | HTTP/Kafka | Order | proposed |
| `WF-PROMO-006` | Voucher trong ví | HTTP query | Customer/Checkout | proposed |
| `WF-PROMO-007` | Khám phá và lưu public voucher | HTTP query/command | Customer/Checkout | proposed |
| `WF-PROMO-008` | Expiry, rescue và reconciliation | Schedule | Promotion workers | proposed |

## WF-PROMO-001 — Tạo/cập nhật promotion hoặc markdown

### Mục tiêu và trigger

Admin tạo/sửa `promotions` hoặc `markdown_rules`; promotion mới phải sẵn sàng cho Rule
Engine mà không gây thundering herd, markdown phải tới Product theo lịch.

### Happy path và state changes

1. HTTP adapter xác thực admin, validate discount, time window, AST; với markdown validate
   logical `ref_id` qua Product.
2. Application ghi PostgreSQL trong một transaction.
3. Sau commit, xóa `promo:{promotion_id}:snapshot`, tăng `promo:rules:version` và đồng bộ
   markdown `ZSET promo:markdown:expiry:index` (`ZREM` nếu INACTIVE/ENDED).
4. Với markdown, direct-produce `MARKDOWN_CREATED` hoặc `MARKDOWN_UPDATED`.
5. Product chỉ apply khi đạt `start_time` theo thiết kế cũ; Product cập nhật giá và phát
   catalog price event cho Search. Search không tự tính markdown.
6. Request sau pipeline version + snapshot; miss đọc DB, cache JSON rồi parse/evaluate.

### Failure, retry và concurrency

DB rollback thì không publish. Crash sau DB commit trước invalidation/event có thể để
snapshot stale đến TTL một giờ hoặc mất event; scheduler/rebuild chỉ chữa một phần, do
direct-produce best-effort. Admin update đồng thời phải dùng DB concurrency guard chưa
được chốt. Markdown expiry DB guard ngăn event kết thúc lặp.

### Verification bắt buộc

Test AST invalid, cache version mismatch, crash window, early start/end schedule, duplicate
expiry, Redis unavailable và event publish failure. Metrics: admin latency/error,
snapshot invalidation failure, direct-produce failure, schedule drift.

## WF-PROMO-002 — Claim voucher flash-sale

### Mục tiêu và preconditions

Cấp tối đa N suất cho lượng truy cập lớn mà không tranh lock PostgreSQL. Admin đã tạo N
row `coupon_claims AVAILABLE`, preload IDs vào `promo:{promotion}:claim_pool`; promotion
đang trong claim window và user chưa claim.

### Happy path

1. Customer bấm “Lưu mã”. API chạy Lua Script 1.
2. Script atomically kiểm tra flag user, `SPOP` một `claim_id`, đặt flag TTL 30 ngày,
   ghi pending payload vào Hash và timestamp vào ZSET.
3. API direct-produce `COUPON_CLAIMED` với event ID mới,
   `business_id=payload.claim_id=claim_id`, `production_source=API_DIRECT`, rồi trả success.
4. `promo-claim-consumer` dedup event/claim, trong một DB transaction update claim bằng
   guard AVAILABLE và upsert wallet `(user,promotion)` với `saved_at=acquired_at`,
   `expired_at=acquired_at+30 days`.
5. Chỉ sau DB commit consumer `HDEL` pending data và `ZREM` pending index.

### Failure, retry, idempotency và concurrency

- Flag đã có → `ALREADY_CLAIMED`; pool rỗng → `OUT_OF_STOCK`.
- Nếu API chết sau SPOP trước Kafka, pending sweeper mỗi phút lấy item cũ hơn 5 phút,
  republish với event ID mới và `production_source=SWEEPER_RESCUE`. Không trả vé vào pool
  và không xóa pending trước DB ack.
- Consumer dedup delivery theo event ID, business theo immutable claim ID; DB partial
  unique và guarded update là chốt cuối.
- Nếu wallet đã tồn tại, upsert phải tuân state policy, không tạo duplicate.

### Verification bắt buộc

Load test 10.000 contender/1.000 suất, duplicate user/event, crash ở mọi ranh giới,
republish nhiều lần, worker DB failure và cleanup retry. Metrics: claim success/out-of-
stock/already-claimed, pending age/count, rescue publish và claim DB conflict.

## WF-PROMO-003 — Tính discount

### Mục tiêu, trigger và input

Checkout gọi `/calculate-discount` với cart/user context và 0/1 promotion đã chọn. Input
quantity lấy từ Cart snapshot; query không mutate budget.

### Happy path

1. Pipeline `MGET` version fence và N snapshot Redis trong một round-trip; miss/version
   mismatch đọc metadata + JSONB AST từ PostgreSQL rồi `SETEX 3600`.
2. Parse AST thành `RuleNode` trong request scope; depth-first evaluate bằng decimal-safe
   comparison. Missing field=false; collection `IN`=ANY, `NOT_IN`=ALL-not-match.
3. Kiểm tra `min_purchase_amount`, claim/use expiry theo source và các condition.
4. Tính PERCENTAGE có cap hoặc FIXED có clamp; áp một PLATFORM voucher lên subtotal.
5. Trả result từng mã, tổng discount, final amount, reason và rule version.

### Failure, retry và determinism

Unknown node/operator hoặc malformed AST là lỗi cấu hình, không retry request mù. Redis
snapshot lỗi có thể fallback PostgreSQL; DB cũng lỗi thì fail request. Cùng AST/context
phải cho cùng kết quả, không side effect. Checkout không được tự có engine thứ hai.

### Verification bắt buộc

Unit/property test mọi operator, nested AND/OR, missing/empty collection, decimal/cap,
expired/min-purchase/category/payment reason, cache hit/miss/version drift và duplicate
query. Theo dõi evaluate latency, AST parse error, snapshot hit/miss.

## WF-PROMO-004 — Checkout TRY/CONFIRM/CANCEL

### Mục tiêu và preconditions

Budget chỉ bị khóa sau khi user Submit và Order đã có `order_id`. Preview token/context
phải hiện hành; mã đã chọn có trong ví và use-window hợp lệ. Reservation business timeout
là 15 phút, key giữ thêm cleanup grace.

### TRY — reserve all-or-nothing

1. Temporal `ReservePromotionActivity` gọi `/reserve` với 0/1 selected platform voucher,
   discount amount theo promotion và identity/idempotency context.
2. Script 2B kiểm layout/input, kiểm reservation hiện hữu có cùng payload, rồi pass 1
   validate available quantity/amount.
3. Pass 2 tăng `reserved_qty/amount`, ghi reservation payload và ZSET expiry. Nếu một
   trong HINCRBY/HINCRBYFLOAT/SETEX/ZADD lỗi, script đảo các bước đã áp dụng.
4. Success trả TTL 15 phút. Failure trả structured result với promotion/slot/reason và
   `reserve_mode=ALL_OR_NOTHING`.

Nếu fail budget/eligibility, Checkout loại mã fail, gọi lại `/calculate-discount` với
đúng current cart và mọi survivor. Nó tạo `dropped_promotions[]`, map budget detail về
`BUDGET_EXHAUSTED`, trả 409 + proposal. Nếu không còn mã, survivors là `[]` và total không
discount. User chấp nhận phải gọi Preview lấy token mới trước Submit lại.

### CONFIRM — payment success

1. Temporal gọi `/confirm` đồng bộ cho promotion với
   `REDEEM_FINAL:{order_id}:{promotion_id}`.
2. Backend gia hạn reservation thêm 60 giây chống race sweeper, rồi Script 3 acquire
   lock, đọc payload, atomically move reserved→used và xóa payload/index.
3. PostgreSQL transaction insert idempotency FINAL, tăng used budget, set wallet USED +
   `used_at`, insert `coupon_usages REDEEMED` với amount thực tế và optional reserved_at.
4. Idempotency conflict đọc state đã chốt và trả success; không đảo Redis commit.

Nếu reservation không còn, trả `RESERVATION_NOT_FOUND`; Order phải xử lý late payment
success bằng refund tức thì thay vì cố redeem lại. Redis success nhưng DB failure phải
vào repair/replay/compensation, không báo nghiệp vụ hoàn tất.

### CANCEL — pre-payment rollback

1. Temporal timeout/user cancel gọi `/cancel` với
   `CANCEL_FINAL:{order_id}:{promotion_id}`.
2. Script 4 lock, đọc payload, giảm reserved rồi DEL/ZREM atomically.
3. DB ghi `CANCEL_COUPON/FINAL`. Wallet vẫn SAVED; không có coupon usage vì Reserve chưa
   ghi ledger.
4. Missing payload chỉ được coi no-op nếu DB đã final. Nếu chưa final, trả repair-required.

### Race, retry và recovery

Confirm/cancel/sweeper không global lock. Lock Redis chỉ ngăn request concurrent ngắn;
DB action scope và Lua idempotency phân xử. Retry dùng cùng key/payload. Sweeper quá hạn
check DB REDEEM FINAL trước release; thiếu payload enqueue reconciliation.

### Verification bắt buộc

Test anti-abuse Preview không reserve, payload mismatch, bốn apply failure point, duplicate
confirm/cancel, confirm-vs-sweep, cancel-vs-confirm, late payment, DB crash after Redis,
409 proposal không/survivor và checkout token replay.

## WF-PROMO-005 — Refund và post-payment compensation

### Mục tiêu và trigger

Hoàn budget allocation đã redeem mà không rollback refund tiền của khách. Trigger chính
là Kafka `ORDER_REFUNDED`; `/refund` là lane sync tùy chọn. `cancel-confirmed` dùng khi
post-payment saga fail sau confirm, tách semantics với cancel pre-payment.

### Refund happy path

1. Consumer nhận event có order/user, refund transaction/type và
   `promotion_refund_allocations[]`; không suy luận từ returned items.
2. Gate duplicate/payload hash trước mutation. Mỗi PLATFORM allocation partial chỉ restore
   khi `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED`; các case khác amount bằng 0.
3. Pipelining Script 5 theo promotion (happy path một voucher). Script lock, clamp không
   vượt used và trả applied qty/amount.
4. DB transaction insert idempotency `REFUND_COUPON` theo refund scope, giảm used budget,
   tăng usage refunded amount/count, set PARTIALLY_REFUNDED hoặc REFUNDED.
5. Wallet giữ `USED`. Có thể phát `COUPON_REFUNDED` cho Analytics.

Nếu wallet không tồn tại, audit `VOUCHER_UNAVAILABLE`, không rollback Payment refund.
Duplicate là audit-only/no mutation; cùng key khác payload vào parking-lot + alert.

### Cancel-confirmed

Order gọi `/cancel-confirmed` với CANCEL FINAL. Promotion reverse budget allocation đã
redeem, ghi cancel idempotency/ledger và dùng policy validity-aware: voucher hết hạn
không bị ép SAVED. Replay trả success, không mutate lần hai.

### Verification bắt buộc

Test full/partial/multi-event refund, clamp, duplicate, payload mismatch, unavailable
wallet, expired wallet, cancel-confirmed khác cancel và DB failure sau Redis mutation.

## WF-PROMO-006 — Voucher trong ví

### Mục tiêu và happy path

`GET /voucher-suggestions` chỉ phục vụ mã đã lưu. Query wallet + promotions bằng index
`(user_id,status)`, lọc PLATFORM, SAVED, active và `expired_at > NOW()`. Row SAVED đã
hết hạn được guarded update sang EXPIRED rồi loại khỏi response.

Service batch load snapshots, evaluate AST, tính discount; với eligible IDs pipeline
budget hash. Exhausted bị downgrade `BUDGET_EXHAUSTED`; Redis guard lỗi thì fail-open và
`budget_guard_status=DEGRADED`. Sort eligible trước, discount giảm dần; trả best/others,
rule/budget versions và guard metadata.

Discovery không ghi wallet mới, không reserve và không fanout invalidate mọi user khi
budget cạn. Race Preview→Submit được chốt bằng Reserve. Target p99 cache-hit dưới 15 ms;
response tối đa 20 eligible + 10 ineligible.

### Failure và tests

DB unavailable fail request; snapshot Redis có DB fallback; budget Redis fail-open. Test
lazy expiry concurrent, large wallet/pagination, budget stale/degraded, source WALLET,
sorting/limit và use-window sau claim-window.

## WF-PROMO-007 — Khám phá và lưu public voucher

### Happy path

1. `/platform-voucher-discovery` lấy wallet PLATFORM hợp lệ và public promotion
   `PLATFORM_VOUCHER ACTIVE` trong claim window, loại IDs đã saved.
2. Evaluate min purchase + AST, budget guard và estimated discount như WF-006.
3. Trả `eligible[]` theo discount giảm dần, `ineligible[]` theo amount-needed tăng dần;
   source là WALLET hoặc PLATFORM_PUBLIC.
4. User chọn public gọi `/save-and-apply`. Service validate type/status/claim window rồi
   upsert wallet SAVED với use-window 30 ngày.
5. Existing SAVED còn hạn trả idempotent success, không kéo dài expiry; EXPIRED có thể
   được reacquire và reset thời gian; USED mặc định trả 409.
6. Sau success, Checkout gọi Preview với promotion ID. Budget chỉ reserve khi Submit.

### Failure và tests

Claim window vừa đóng → EXPIRED/ineligible hoặc save bị từ chối. Concurrent save dựa
unique wallet. Test saved/public de-dup, source, EXPIRED reacquire, USED reject, budget
race và sub-cart/category/payment contexts.

## WF-PROMO-008 — Expiry, rescue và reconciliation

### Scheduled paths

- Pending claim: mỗi phút republish item cũ hơn 5 phút, không xóa trước DB ack.
- Reservation: mỗi phút batch 1.000 item đến hạn, gate REDEEM FINAL, release hoặc repair.
- Markdown: loop 250 ms–1 s batch 200; lock, guarded DB transition, publish sau commit,
  ZREM sau; cron fallback 10–30 s quét DB `FOR UPDATE SKIP LOCKED`; startup rebuild ZSET.
- Budget reconciliation: mỗi 15 phút theo recently changed promotions, full-scan off-peak
  hằng ngày; dựng expected từ DB ledger + valid reservations, lock/re-read/repair Redis.
- Claim cleanup: ban đêm xóa claim thuộc campaign ended và quá 30 ngày.
- Idempotency cleanup: hằng ngày, retention tối thiểu refund window + 15 ngày, mặc định 45.
- Snapshot cold-miss monitor: mỗi 5 phút chỉ metric/alert, không auto-warm.
- Wallet expiry: không job; lazy inline.

### Recovery và verification

Mỗi job batch hữu hạn, retry transient và idempotent. Reconciliation audit before/after,
run ID/reason và không bao giờ ghi DB từ Redis. Test restart/failover, lock contention,
crash DB-commit-before-ZREM/publish, missing index/payload, repeated drift và safe rerun.
