# Promotion Service - Redis Caching & Lua Scripts Design

**Liên kết nhanh (Related Documents):**
*   [Database Schema](promotion_db_design.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Rule Engine Design](rule_engine_design.md)
*   [Background Workers & Jobs](promotion_workers.md)

---

Tài liệu này mô tả chi tiết kiến trúc bộ nhớ đệm đa tầng (L1/L2), cùng với các Script Lua đảm bảo tính nguyên tử (Atomicity) tuyệt đối cho hệ thống Promotion Service, giúp xử lý hàng trăm nghìn giao dịch mỗi giây (TPS) ở cấu hình cao mà không gặp rủi ro Deadlock hay Race Condition.

---

## 1. Kiến Trúc Caching Đa Tầng (Multi-Level Caching)

### 1.1. L1 Cache (Caffeine In-Memory)
- **Mục đích:** Cache metadata tĩnh siêu tốc độ (< 1ms) cho Rule Engine (JSONB AST) và cấu hình mã giảm giá cơ bản.
- **Tại sao cần L1?** Khâu "Tính giá" (`/calculate-discount`) và "Đánh giá sự phù hợp" (Voucher Discovery) đòi hỏi quét hàng loạt rule trên RAM để trả về kết quả ngay lập tức mà không phải chịu I/O mạng của Redis.
- **Cấu hình:**
  - Kích thước: Tối đa 50,000 entries.
  - TTL: 10 phút. Kích hoạt tính năng `refreshAfterWrite(1m)` để liên tục âm thầm đồng bộ ngầm (Async Loading) mà không block read.
- **Sự kiện Invalidation:** Khi Kafka/Redis Pub-Sub phát đi tín hiệu sự kiện `PROMO_EVICT {id}`, Caffeine tự xóa cache cục bộ và nạp lại.

### 1.2. L2 Cache (Redis Cluster)
- **Mục đích:** Xử lý các thay đổi Concurrency siêu nhanh (Số lượng vé, Giữ chỗ ngân sách, Rate Limits, Tranh mã Flash Sale).
- Đây là cốt lõi của bảo vệ Giao dịch Cao tải, áp dụng chặt chẽ nguyên lý Gatekeeper, hoàn toàn **Không dùng cơ chế khóa DB (DB Lock)** để quản lý tồn kho ngân sách trong luồng đặt hàng.

### 1.3. Wallet Discovery Cache + Budget Guard (Option A)
- **Wallet cache theo user:** `wallet:{user_id}:active_vouchers` lưu metadata tĩnh để tăng tốc tray voucher.
- **Budget Guard read-time:** Sau khi Rule Engine đánh dấu eligible, service đọc batch `promo:{id}:budget` bằng Redis pipelining để downgrade mã đã cạn ngân sách/lượt thành `ineligible_reason=BUDGET_EXHAUSTED`.
- **Fail-open khi guard lỗi:** Nếu Redis timeout/lỗi ngắn hạn, Discovery vẫn trả response (degraded) để giữ availability; correctness cuối cùng được chốt tại Reserve khi Submit.
- **Không fanout invalidation toàn cục:** Khi có `BUDGET_EXHAUSTED`, hệ thống **không** quét và `DEL` toàn bộ wallet keys theo `promotion_id` (tránh write amplification + reverse-index phức tạp). Invalidation vẫn user-scoped theo các luồng claim/paid/cancel/refund/expire.

---

## 2. Thiết Kế Các Lua Scripts Phân Tán (Nguyên Tử Hoá)

Việc chạy Lua Script trong Redis đảm bảo mọi lệnh kết hợp `GET`, `CHECK`, `SET` đều đóng gói nguyên khối (Atomic), giúp chặn đứng Race Condition.

> [!IMPORTANT]
> **Quy tắc idempotency bắt buộc:** Redis script chỉ đảm nhận atomic counter mutation và lock ngắn hạn chống đua. Quyết định idempotent cuối cùng phải nằm ở PostgreSQL (`promotion_event_idempotency`) để không tạo trạng thái "đã xử lý" giả khi DB chưa commit.

### Script 1: Thuật toán Giật Mã Flash Sale O(1) (SPOP Claiming)
**Ngữ cảnh:** 10,000 người dùng bấm "Lưu mã" Flash Sale cùng 1 lúc (Giới hạn 1,000 mã).
**Cấu trúc dữ liệu liên quan:**
- `Set: promo:{id}:claim_pool` (Chứa sẵn 1.000 vé `claim_id` từ CSDL).
- `Hash/Bit: promo:{id}:claimed:user:{user_id}` (Cờ đánh dấu user đã giật thành công).
- `Hash: promo:{id}:pending_claims:data` (Value JSON: `user_id`, `timestamp`).
- `ZSET: promo:{id}:pending_claims:index` (Score=`timestamp`, Member=`claim_id`) để quét timeout O(logN).

```lua
-- KEYS[1] = claim_pool, KEYS[2] = user_claimed_flag, KEYS[3] = pending_claims_data, KEYS[4] = pending_claims_index
-- ARGV[1] = user_id, ARGV[2] = current_timestamp

-- 1. Chống Spam: User đã giật được mã chưa?
if redis.call('GET', KEYS[2]) then
    return -1 -- "ALREADY_CLAIMED"
end

-- 2. Tranh vé: Lôi ngẫu nhiên 1 vé bằng SPOP
local claim_id = redis.call('SPOP', KEYS[1])
if not claim_id then
    return -2 -- "OUT_OF_STOCK"
end

-- 3. Khóa cờ User
redis.call('SETEX', KEYS[2], 86400 * 30, "1") -- Giữ 30 ngày

-- 4. Đẩy dự phòng vào pending_claims rớt mạng
local payload = '{"user_id":' .. ARGV[1] .. ',"timestamp":' .. ARGV[2] .. '}'
redis.call('HSET', KEYS[3], claim_id, payload)
redis.call('ZADD', KEYS[4], tonumber(ARGV[2]), claim_id)

return claim_id
```

### Script 2: Thuật toán Giữ chỗ Ngân sách (Budget Reservation)
**Ngữ cảnh:** Khi User bấm Submit Order, tạo cam kết giảm giá (Reserve/Lock).
**Cấu trúc dữ liệu:**
- `Hash: promo:{id}:budget` chứa: `total_qty`, `reserved_qty`, `used_qty`, `total_amount`, `reserved_amount`, `used_amount`.
- `String: promo:{id}:reservation:{order_id}` (Làm TTL tracking 15 phút, không ghi DB).
- `ZSET: promo:reservation:index` (member=`{promotion_id}:{order_id}`, score=`expire_at_epoch_ms`) để Sweeper quét ổn định.

```lua
-- KEYS[1] = budget_hash, KEYS[2] = reservation_key, KEYS[3] = reservation_index_zset
-- ARGV[1] = req_qty (thường là 1), ARGV[2] = req_amount (số tiền giảm), ARGV[3] = reservation_key_ttl_seconds (business_ttl + cleanup_grace)
-- ARGV[4] = reservation_member (promotion_id:order_id), ARGV[5] = expire_at_epoch_ms

-- Nếu reservation đã tồn tại (Chống lặp)
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 1 -- SUCCESS (Idempotent)
end

local b = redis.call('HMGET', KEYS[1], 'total_qty', 'reserved_qty', 'used_qty', 'total_amount', 'reserved_amount', 'used_amount')
local t_qty = tonumber(b[1] or 0)
local r_qty = tonumber(b[2] or 0)
local u_qty = tonumber(b[3] or 0)
local t_amt = tonumber(b[4] or 0)
local r_amt = tonumber(b[5] or 0)
local u_amt = tonumber(b[6] or 0)
local req_qty = tonumber(ARGV[1])
local req_amt = tonumber(ARGV[2])

-- Validate số lượng
if (t_qty - u_qty - r_qty < req_qty) then
    return -1 -- BUDGET_QTY_EXHAUSTED
end

-- Validate giá trị ngân sách (Nếu budget vô hạn, t_amt có thể là -1)
if t_amt > -1 and (t_amt - u_amt - r_amt < req_amt) then
    return -2 -- BUDGET_AMOUNT_EXHAUSTED
end

-- Chấp nhận Reserve
redis.call('HINCRBY', KEYS[1], 'reserved_qty', req_qty)
redis.call('HINCRBYFLOAT', KEYS[1], 'reserved_amount', req_amt)

-- Đặt Key TTL để làm cờ tự động Rollback
local payload = '{"req_qty":' .. req_qty .. ',"req_amt":' .. req_amt .. '}'
redis.call('SETEX', KEYS[2], tonumber(ARGV[3]), payload)
redis.call('ZADD', KEYS[3], tonumber(ARGV[5]), ARGV[4])

return 1 -- SUCCESS
```

### Script 2B: Batch Reserve all-or-nothing (Bắt buộc cho multi-voucher)
**Ngữ cảnh:** 1 đơn có nhiều voucher (shop/platform/freeship). Mục tiêu là không được reserve nửa chừng.

```lua
-- Input: danh sách voucher (budget_hash, reservation_key, req_qty, req_amount, reservation_member)
-- Slot cardinality chuẩn:
--   - SHOP_VOUCHER: tối đa 1 mã cho mỗi shop (seller_id)
--   - PLATFORM_VOUCHER: tối đa 1 mã toàn đơn
--   - FREESHIP_VOUCHER: tối đa 1 mã toàn đơn
-- Tổng số voucher/đơn là biến thiên theo số shop trong đơn.
-- Pass 1 (validate-only): duyệt toàn bộ voucher, nếu có 1 voucher fail thì trả lỗi ngay
-- Pass 2 (apply): chỉ khi pass toàn bộ mới HINCRBY/HINCRBYFLOAT + SETEX + ZADD index cho tất cả voucher
-- Nếu bất kỳ bước apply nào bất thường: rollback ngay trong script trước khi return
-- Output chuẩn:
--   SUCCESS: {"code":"OK","reserve_mode":"ALL_OR_NOTHING"}
--   FAIL:    {"code":"BUDGET_EXHAUSTED","failed_promotion_id":"...","failed_slot":"...","failed_reason":"BUDGET_QTY_EXHAUSTED|BUDGET_AMOUNT_EXHAUSTED","reserve_mode":"ALL_OR_NOTHING"}
```

> Script 2B là bắt buộc ở production. Nhánh fallback reserve tuần tự + rollback chỉ dùng cho dev/test hoặc migration ngắn hạn có kiểm soát feature-flag.
>
> `failed_reason` là contract để Checkout trigger full re-evaluation (không được làm phép trừ số học cục bộ). Mọi mã còn lại phải được evaluate lại qua Promotion Rule Engine.
>
> [!NOTE]
> Budget Guard ở Discovery chỉ là pre-check tối ưu UX. Commit-time quyết định vẫn là Script 2/2B trong `ReservePromotionActivity`.

### Script 3: Xác nhận Mua thành công (Commit Redeem)
**Ngữ cảnh:** Đơn hàng thanh toán xong, hệ thống chốt số tiền thực tế (Redeem).
- Chuyển số từ cột `reserved` sang `used`.
- Redis chỉ dùng lock ngắn hạn chống đua; idempotency dài hạn nằm ở DB.

```lua
-- KEYS[1] = promo:{id}:budget (Hash ngân sách)
-- KEYS[2] = promo:{id}:reservation:{order_id} (Key chứa payload giữ chỗ)
-- KEYS[3] = promo:lock:redeem:{promotion_id}:{order_id} (Khóa chống lặp concurrent theo từng voucher trong đơn)
-- KEYS[4] = promo:reservation:index (ZSET bảng xếp hạng thời gian)
-- ARGV[1] = lock_ttl_seconds (Thời gian giữ khóa, VD: 10s)
-- ARGV[2] = {promotion_id}:{order_id} (Member của ZSET)

-- 1. Đặt khóa chống duplicate concurrent requests
if redis.call('SETNX', KEYS[3], "1") == 0 then
    return 2 -- IN_PROGRESS (Request đang được xử lý)
end
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[1]))

-- 2. Đọc payload reservation
local payload = redis.call('GET', KEYS[2])
if not payload then
    return -1 -- RESERVATION_NOT_FOUND (Đã xử lý hoặc đã bị rollback)
end

-- 3. Giải mã JSON
local data = cjson.decode(payload)
local req_qty = tonumber(data.req_qty)
local req_amt = tonumber(data.req_amt)

-- 4. Trừ ở giỏ Reserved, Cộng vào giỏ Used
redis.call('HINCRBY', KEYS[1], 'reserved_qty', -req_qty)
redis.call('HINCRBYFLOAT', KEYS[1], 'reserved_amount', -req_amt)

redis.call('HINCRBY', KEYS[1], 'used_qty', req_qty)
redis.call('HINCRBYFLOAT', KEYS[1], 'used_amount', req_amt)

-- 5. Xóa key giữ chỗ và dọn dẹp ZSET
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[4], ARGV[2])

-- Lưu ý thiết kế: KHÔNG gỡ KEYS[3] tại đây. Việc gỡ khóa sẽ do Backend thực hiện 
-- sau khi đã chốt dữ liệu vào bảng promotion_event_idempotency trên DB.
return 1 -- SUCCESS
```

> [!IMPORTANT]
> Với đơn có nhiều promotion, backend phải gọi Script 3 theo từng `promotion_id` bằng **Redis Pipelining** để gom N lệnh vào 1 round-trip mạng. Mỗi lệnh dùng lock key riêng `promo:lock:redeem:{promotion_id}:{order_id}` nên không dẫm chân nhau giữa các voucher cùng đơn.

> [!NOTE]
> Nếu script trả `1` nhưng DB commit thất bại, service phải chạy flow repair theo `order_id/promotion_id` (replay hoặc compensate) thay vì coi giao dịch đã hoàn tất.

### Script 4: Nhả ngân sách (Rollback / Release Reservation)
**Ngữ cảnh:** Đơn huỷ / Quá 15p (Cronjob kích hoạt từ reservation index sweep).
Chỉ thao tác trên `reserved...`, không tác động đến `used`.

```lua
-- KEYS[1] = promo:{id}:budget (Hash ngân sách)
-- KEYS[2] = promo:{id}:reservation:{order_id} (Key chứa payload giữ chỗ)
-- KEYS[3] = promo:lock:cancel:{promotion_id}:{order_id} (Khóa chống lặp theo từng voucher trong đơn)
-- KEYS[4] = promo:reservation:index (ZSET bảng xếp hạng thời gian)
-- ARGV[1] = lock_ttl_seconds
-- ARGV[2] = {promotion_id}:{order_id} (Member của ZSET)

-- 1. Đặt khóa chống lặp
if redis.call('SETNX', KEYS[3], "1") == 0 then
    return 2 -- IN_PROGRESS
end
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[1]))

-- 2. Đọc payload
local payload = redis.call('GET', KEYS[2])
if not payload then
    -- Nếu key không tồn tại, có thể nó đã bị hết hạn TTL hoặc payload đã mất.
    -- Dọn index rác nhưng KHÔNG coi đây là release đầy đủ.
    redis.call('ZREM', KEYS[4], ARGV[2])
    return 3 -- RESERVATION_MISSING_PAYLOAD (INDEX_CLEANED_ONLY)
end

-- 3. Giải mã JSON
local data = cjson.decode(payload)
local req_qty = tonumber(data.req_qty)
local req_amt = tonumber(data.req_amt)

-- 4. Trừ lượng đã khóa (Tự động cộng dồn lại vào số lượng Available)
redis.call('HINCRBY', KEYS[1], 'reserved_qty', -req_qty)
redis.call('HINCRBYFLOAT', KEYS[1], 'reserved_amount', -req_amt)

-- 5. Dọn dẹp dấu vết reservation
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[4], ARGV[2])

return 1 -- SUCCESS
```

> [!IMPORTANT]
> Với đơn có nhiều promotion, backend phải gọi Script 4 theo từng `promotion_id` bằng **Redis Pipelining** (1 round-trip). Lock key cấp voucher `promo:lock:cancel:{promotion_id}:{order_id}` đảm bảo các nhánh cancel trong cùng đơn chạy song song an toàn.

### Script 5: Hoàn ngân sách sau đổi trả hàng (Refund Budget)
**Ngữ cảnh:** Giao hàng xong nhưng khách hoàn trả. Trừ lại `used`, trả về cho Available.

```lua
-- KEYS[1] = promo:{id}:budget (Hash ngân sách)
-- KEYS[2] = promo:lock:refund:{promotion_id}:{order_id} (Khóa chống lặp theo từng voucher trong đơn)
-- ARGV[1] = refund_qty (Số lượng hoàn lại)
-- ARGV[2] = refund_amount (Số tiền hoàn lại)
-- ARGV[3] = lock_ttl_seconds

-- 1. Đặt khóa chống lặp cho giao dịch hoàn tiền
if redis.call('SETNX', KEYS[2], "1") == 0 then
    return {2, 0, 0} -- IN_PROGRESS
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local req_qty = tonumber(ARGV[1])
local req_amt = tonumber(ARGV[2])

-- 2. Kiểm tra số lượng đã dùng hiện tại
local current_used_qty = tonumber(redis.call('HGET', KEYS[1], 'used_qty') or 0)
local current_used_amt = tonumber(redis.call('HGET', KEYS[1], 'used_amount') or 0)

-- 3. Cắt giảm lượng hoàn tiền nếu vượt quá số đã sử dụng (Bảo vệ tính toàn vẹn)
if current_used_qty - req_qty < 0 then 
    req_qty = current_used_qty 
end
if current_used_amt - req_amt < 0 then 
    req_amt = current_used_amt 
end

-- 4. Trừ trực tiếp khỏi cột Used (Ngân sách Available tự động tăng lên)
redis.call('HINCRBY', KEYS[1], 'used_qty', -req_qty)
redis.call('HINCRBYFLOAT', KEYS[1], 'used_amount', -req_amt)

return {1, req_qty, req_amt} -- SUCCESS, trả lượng áp dụng thực tế sau clamp
```

> [!IMPORTANT]
> Với `promotion_refund_allocations[]`, backend phải gọi Script 5 theo từng `promotion_id` bằng **Redis Pipelining** (1 round-trip). DB update bắt buộc dùng `applied_refund_qty/applied_refund_amount` trả về từ script để tránh drift khi có clamp bảo vệ không âm.

---

## 3. Quét Tự Động Reservation (Micro-batch Dùng ZSET Index)

Vì để cấu trúc này chạy nhanh nhất, **Database PostgreSQL không lưu trạng thái `RESERVED`**. Trạng thái này chỉ tồn tại runtime ở Redis.
- Background Job Sweep chạy micro-batch định kỳ: `ZRANGEBYSCORE promo:reservation:index -inf now_ms LIMIT 0 1000`.
- Với mỗi member quá hạn `<promotion_id:order_id>`, xử lý theo thứ tự bắt buộc:
    1. Check DB idempotency `REDEEM_COUPON + action_scope_key='FINAL'`.
    2. Nếu đã redeem: chỉ `ZREM` member index (no-op an toàn), không gọi Script 4.
    3. Nếu chưa redeem và reservation key đã mất: gọi Script 4 để nhận mã trả về chuẩn; nếu mã `RESERVATION_MISSING_PAYLOAD` thì `ZREM` index và enqueue reconciliation repair (không được coi là release hoàn tất).
    4. Nếu chưa redeem và reservation key còn: gọi Script 4 để release; Script 4 tự `DEL reservation_key + ZREM index` một cách idempotent.
    5. Sau nhánh release thực tế, ghi `promotion_event_idempotency(event_id='SWEEP-CANCEL:{promotion_id}:{order_id}:FINAL', action_type='CANCEL_COUPON', action_scope_key='FINAL')` với `ON CONFLICT DO NOTHING`.
- Optional: Có thể bật thêm `Redis Keyspace Notifications` để giảm độ trễ, nhưng ZSET index mới là nguồn chuẩn để xử lý chính xác.

> [!IMPORTANT]
> Với số tiền, nên chuẩn hóa về integer minor-unit (đồng/cents) để tránh sai số tích lũy từ `HINCRBYFLOAT`. Nếu chưa chuyển đổi ngay, bắt buộc có reconciliation job định kỳ giữa Redis và PostgreSQL ledger.

### 3.1. Reconciliation bắt buộc (DB -> Redis, không hai chiều)

Để khép kín cửa sổ lỗi "Redis đã mutate nhưng DB chưa commit", hệ thống phải có job reconciliation định kỳ (khuyến nghị mỗi 15 phút):

| Counter | Nguồn chuẩn | Ghi chú |
|---|---|---|
| `total_qty`, `total_amount` | `promotion_budgets` (PostgreSQL) | Cấu hình ngân sách bền vững |
| `used_qty`, `used_amount` | `coupon_usages` + `promotion_event_idempotency` (PostgreSQL) | Ledger nghiệp vụ quyết định số đã dùng |
| `reserved_qty`, `reserved_amount` | Redis reservation index/payload còn hiệu lực (đã lọc idempotency `REDEEM_COUPON FINAL`) | Trạng thái runtime tạm thời |

1. Chọn tập `promotion_id` có biến động gần đây dựa trên outbox/idempotency window và bổ sung full-scan off-peak.
2. Dựng `expected snapshot` theo ma trận nguồn chuẩn bên trên.
3. Đọc `actual snapshot` từ `promo:{promotion_id}:budget` trên Redis và tính drift từng chiều (`used/reserved`, qty/amount).
4. Nếu drift vượt ngưỡng: acquire lock repair (`promo:budget:reconcile:lock:{promotion_id}`) -> re-read snapshot -> apply script đưa counters về expected với guard không âm/không vượt total.
5. Ghi audit `before/after` và emit metrics drift/repair/error.

> [!WARNING]
> Repair chỉ được chạy theo hướng "DB ledger là nguồn quyết định cuối cùng". Không bao giờ ghi ngược DB từ số liệu Redis.

### 3.2. Markdown Expiry Scheduler (Redis ZSET + Cron fallback)

Để đáp ứng nghiệp vụ hết hạn markdown gần chuẩn giây, Promotion Service dùng bộ lập lịch chính trên Redis thay vì chỉ quét cron theo phút.

**Redis keys chuẩn:**
*   `ZSET promo:markdown:expiry:index` (score = `end_time_epoch_ms`, member = `rule_id`).
*   `STRING promo:markdown:expiry:lock:{rule_id}` (khóa `SETNX` TTL ngắn, chống đua giữa nhiều instance).

**Luồng xử lý chuẩn:**
1. **Write-path đồng bộ lịch:** Khi tạo/cập nhật `markdown_rules` đang `ACTIVE`, upsert `ZADD promo:markdown:expiry:index <end_time_epoch_ms> <rule_id>`. Nếu rule chuyển `INACTIVE/ENDED` thì `ZREM` khỏi index.
2. **Scheduler loop near-real-time:** Worker chạy mỗi 250ms-1s, quét `ZRANGEBYSCORE promo:markdown:expiry:index -inf now_ms LIMIT 0 200`.
3. **Claim rule:** Với từng `rule_id`, acquire lock `SETNX promo:markdown:expiry:lock:{rule_id}`. Lock fail thì skip để tránh duplicate processing.
4. **DB-first transaction:**
    - `UPDATE markdown_rules SET status = 'ENDED' WHERE id = :rule_id AND status = 'ACTIVE' AND end_time <= NOW()`
    - Nếu `row_count = 1`, `INSERT` outbox `MARKDOWN_ENDED` (`payload.reason = EXPIRED`) trong cùng transaction.
5. **Post-commit cleanup:** Commit thành công mới `ZREM promo:markdown:expiry:index :rule_id`.
6. **Crash-window handling:** Nếu crash sau commit trước `ZREM`, vòng sau trở thành no-op nhờ DB guard rồi tự dọn index.
7. **Cron fallback self-healing:** Job 10-30 giây quét DB `status='ACTIVE' AND end_time<=NOW()` để vá miss index/scheduler gián đoạn.
8. **Startup rebuild:** Sau restart/failover Redis, bootstrap lại index từ tập rule `ACTIVE` có `end_time > NOW()`.

> [!IMPORTANT]
> Khóa Redis chỉ là lớp chống đua ngắn hạn. Tính đúng đắn cuối cùng của expiry phụ thuộc vào DB guard (`status='ACTIVE' AND end_time<=NOW()`) và transaction outbox.

## 4. Rate Limiting & Bloom Filters Tối Ưu

**A. API Security & Rate Limit:**
Endpoint Discovery (`/voucher-suggestions`) được bọc `Token Bucket (HMSET / PTTL)` mỗi UserID để cản Bot DDoS. (Giới hạn: 20 req/min).

**B. Bloom Filter cho Unique Promo Codes:**
Do mã (Code nhập tay) có thể lên đến hàng triệu, hệ thống dùng **RedisBloom**: `BF.RESERVE promo:codes 0.001 1000000`. Khi khách nhập "HUNT1111", Redis kiểm tra `BF.EXISTS` trước, hạn chế cắm thẳng xuống PostgreSQL nếu mã rõ ràng sai. Lõi lọc này có tỷ lệ chặn 99.9% mã ảo trong cực ngắn (< 1ms).
