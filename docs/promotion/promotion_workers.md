# Promotion Service - Danh Sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Database & Cache Schema](promotion_db_design.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Promotion Events](promotion_events.md)
*   [Outbox Pattern](promotion_outbox_pattern.md)

---

Promotion Service chịu tải cực lớn từ Giỏ hàng (Cart) và Thanh toán (Checkout), đồng thời phải dọn dẹp bộ nhớ và đồng bộ dữ liệu liên tục. Dưới đây là danh sách toàn bộ các Background Workers và Cronjobs bảo trì hệ thống.

---

## Tổng Hợp Kafka Topics & Consumer Groups

| Topic | Consumer Group | Cờ (Role) | Mô tả |
|---|---|---|---|
| `t.order.events` | `promo-order-consumer` | Consumer | Hứng `ORDER_REFUNDED` để trigger Workflow 5 (refund flow). |
| `t.product.events` | `promo-product-state-consumer` | Consumer | Hứng `PRODUCT_STATE_CHANGED` để apply markdown cho sản phẩm vừa chuyển `ACTIVE`. |
| `t.auth.events` | `promo-auth-consumer` | Consumer (optional) | Hứng `USER_REGISTERED` cho use-case welcome voucher/new-user campaigns. |
| `t.promotion.markdown.events`, `t.promotion.coupon.events`, `t.promotion.budget.events` | - | Producer | Phát các sự kiện do Promotion Service tạo ra (Markdown, Coupon, Budget). |

---

## 1. Trụ cột Message & Event (Kafka)

### 1.1. Outbox CDC Connector (Producer)
**Loại hình:** Kafka Connect Source Connector (Debezium CDC).

**Sứ mệnh:** Đảm bảo mọi sự kiện **đi qua bảng `promotion_outbox`** (markdown/coupon ledger/budget events) được đưa lên Kafka một cách an toàn (At-Least-Once Delivery) mà không cần polling trạng thái trong DB.
*   **Chi tiết thiết kế:** Xem tại [Outbox Pattern](promotion_outbox_pattern.md#4-outbox-cdc-connector-chi-tiết).
*   **Hoạt động:** Debezium đọc WAL PostgreSQL, lọc `INSERT` từ `promotion_outbox`, map `event_type` sang topic đích (`t.promotion.markdown.events` / `t.promotion.coupon.events` / `t.promotion.budget.events`) và dùng `partition_key` làm Kafka message key.
*   **Retry/DLQ:** Quản trị tại Kafka Connect runtime (task retry, DLQ topic, connector lag alert), không ghi trạng thái retry vào bảng outbox.

> [!NOTE]
> Sự kiện claim Flash Sale (`COUPON_CLAIMED`) là luồng direct-produce sau Lua Script, không bắt buộc qua outbox DB.

### 1.2. Order Events Consumer — Refund Flow
**Loại hình:** Kafka Consumer (Long-running).
**Consumer Group:** `promo-order-consumer`

**Sứ mệnh:** Lắng nghe sự kiện refund từ Order Service để thực thi hoàn ngân sách cho Khuyến mãi theo [Workflow 5](promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow).
*   **Hoạt động:**
    1. Hứng `ORDER_REFUNDED` (Trả hàng hoàn tiền): Đây là primary trigger của Workflow 5. Worker ghi idempotency + hoàn budget + cập nhật ví theo policy tái sử dụng.
    2. Với idempotency record `refund_transaction_id` đã tồn tại: skip xử lý, ghi nhận audit-only (chống replay).
*   **Lưu ý kiến trúc:** Đường xử lý `Confirm`/`Rollback` cho checkout là gọi đồng bộ qua Temporal Activities (`ConfirmPromotionActivity`, `RollbackPromotionActivity`, `CancelConfirmedPromotionActivity`) với `idempotency_key` theo từng `promotion_id`. Consumer Kafka này **chỉ** phục vụ refund flow; không nhận `ORDER_PAID`/`ORDER_CANCELLED`.
*   **Bảo vệ Idempotency:** Worker tích hợp Redis Lock (`SETNX`) kết hợp DB Constraint (`promotion_event_idempotency`) để chặn duplicate messages từ Kafka (đảm bảo hiệu ứng xử lý tương đương exactly-once ở tầng nghiệp vụ).
*   **Contract bắt buộc:**
    1. Luồng sync (`/confirm`, `/cancel`, `/cancel-confirmed`, `/refund`) dùng đúng `idempotency_key/request_id` từ Order để ghi `promotion_event_idempotency.event_id`.
    2. Luồng async Kafka (`ORDER_REFUNDED`) dùng đúng `event_id` envelope làm `promotion_event_idempotency.event_id`.
    3. Promotion không tự sinh `event_id` cho các nhánh này.
*   **Idempotency gating:**
    1. Nếu idempotency record `refund_transaction_id` đã tồn tại thì event được xử lý dạng audit-only, không mutate Redis/DB.
    2. Event có payload hash lệch với bản ghi hiện tại phải vào parking-lot + alert, không tự động ghi đè.

> [!NOTE]
> Vì slot model cố định 1 `PLATFORM_VOUCHER`/đơn, happy path của các nhánh Confirm/Cancel/Refund (bao gồm lane `cancel-confirmed`) chỉ gọi script đúng 1 lần cho `promotion_id` đó. Tuy nhiên pattern Redis Pipelining (1 round-trip) + lock key cấp voucher `promo:lock:{action}:{promotion_id}:{order_id}` vẫn được giữ để nhất quán với cấu hình cluster và đảm bảo idempotency khi nhánh Refund partial mở rộng theo từng promotion.

### 1.3. Auth Events Consumer (Optional)
**Loại hình:** Kafka Consumer (Long-running).
**Consumer Group:** `promo-auth-consumer`

**Sứ mệnh:** Xử lý `USER_REGISTERED` để cấp welcome voucher/new-user wallet record theo feature-flag campaign.
*   **Hoạt động:**
    1. Hứng `USER_REGISTERED` từ `t.auth.events`.
    2. Map envelope theo compatibility adapter (`data -> payload`, `timestamp_ms -> registered_at`) nếu producer dùng format Auth native.
    3. Kiểm tra campaign active và rule chống cấp trùng.
    4. Upsert `user_coupon_wallets` theo `(user_id, promotion_id)` để đảm bảo idempotent, đồng thời gán `saved_at = acquired_at` và `expired_at = acquired_at + INTERVAL '30 days'`.

### 1.4. Product State Consumer
**Loại hình:** Kafka Consumer (Long-running).
**Consumer Group:** `promo-product-state-consumer`

**Sứ mệnh:** Lắng nghe `PRODUCT_STATE_CHANGED` từ Product Service để xử lý kịch bản sản phẩm chuyển từ trạng thái chưa hiển thị sang `ACTIVE` trong khi markdown rule vẫn còn hiệu lực.
*   **Hoạt động:**
    1. Hứng `PRODUCT_STATE_CHANGED` từ `t.product.events`.
    2. Nếu `new_status = ACTIVE`, re-evaluate markdown rules đang `ACTIVE` theo scope (PRODUCT/CATEGORY) cho `product_id` vừa kích hoạt.
    3. Phát lại luồng `MARKDOWN_CREATED`/`MARKDOWN_UPDATED` tương ứng khi cần để Product Service apply giá markdown cho SKU mới lên sàn.
    4. Bảo vệ idempotency theo `event_id` + `product_id` để tránh apply lặp khi replay.

### 1.5. Flash Sale Claim Async Worker (Consumer / Internal Queue)
**Loại hình:** Kafka Consumer (High-throughput) / Internal Queue Worker.
**Consumer Group:** `promo-claim-consumer`

**Sứ mệnh:** Xử lý bất đồng bộ kết quả giật mã Flash Sale. Giúp tách rời việc giành mã trên RAM (siêu tốc, 0 độ trễ) với quá trình cập nhật xuống đĩa mềm PostgreSQL (chậm, dễ nghẽn), bảo vệ DB khỏi bão Traffic.
*   **Chi tiết luồng xử lý:** Xem tại [Thuật toán Giật Mã Flash Sale O(1)](redis_caching_design.md#script-1-thuật-toán-giật-mã-flash-sale-o1-spop-claiming).
*   **Hoạt động:**
    1. Lắng nghe event `COUPON_CLAIMED` (hoặc thông điệp `CLAIM`) đẩy từ Topic `t.promotion.coupon.events` (hoặc internal queue). Event này do API Backend bắn ra ngay sau khi chạy Lua Script SPOP trên Redis thành công.
    2. Áp dụng idempotency 2 lớp cho claim stream: dedup theo `event_id` (delivery) và dedup nghiệp vụ theo `claim_id` (thực thể vé claim bất biến).
    3. Thực thi Database Transaction: Cập nhật `UPDATE coupon_claims SET owner_id = :user_id, status = 'CLAIMED', claimed_at = NOW() WHERE id = :claim_id AND status = 'AVAILABLE' AND owner_id IS NULL;` và insert/upsert mã vào `user_coupon_wallets` theo khóa duy nhất `(user_id, promotion_id)` với policy `saved_at = acquired_at`, `expired_at = acquired_at + INTERVAL '30 days'`.
    4. Invalidate ví user sau khi ghi DB thành công: lần Discovery kế tiếp sẽ tự query DB và thấy mã mới (không qua cache).
    5. Dọn dẹp tàn dư: Gọi lên Redis `HDEL promo:{promotion_id}:pending_claims:data :claim_id` + `ZREM promo:{promotion_id}:pending_claims:index :claim_id` để xác nhận Event đã được ghi đĩa an toàn, tránh bị Sweeper Job (Mục 2.2) quét trúng.

---

## 2. Jobs Đồng bộ Cache (Redis)

### 2.1. Snapshot Cache Cold-Miss Monitor (Optional)

**Loại hình:** Cronjob giám sát mỗi 5 phút (chỉ emit metric + alert, không tự warmup).

**Sứ mệnh:** Theo dõi hit-rate của snapshot cache `promo:{promotion_id}:snapshot` để phát hiện sớm các tình huống cache miss hàng loạt (ví dụ: snapshot TTL 1h trôi qua cùng lúc cho nhiều promo, hoặc Redis failover vừa xảy ra).
*   **Hoạt hoạt động:**
    1. Đếm số `snapshot_cache.hit` và `snapshot_cache.miss` trong window 5 phút qua (metric từ request-path).
    2. Nếu miss-rate > 20% trong window bình thường hoặc > 50% sau deploy: emit alert để SysAdmin điều tra (Redis health, version fence đang drift, hoặc TTL quá ngắn).
    3. Không tự động warmup — service chỉ cần miss + SELECT từ DB là đủ để recover.


### 2.2. Flash Sale Pending Claims Sweeper
**Loại hình:** Cronjob micro-batch quét mỗi 1 phút (batch rescue near-real-time).

**Sứ mệnh:** Cứu hộ hàng đợi Flash Sale SPOP. Tránh việc user claim được vé ảo từ Redis Cache nhưng crash ở Backend trước khi Event gắn với Kafka, dẫn tới mất vé của hệ thống và gây ức chế cho User (đã nhận response OK nhưng ví trống trơn).
*   **Hoạt động:**
*   Sử dụng cặp key Redis:
    *   `ZSET promo:{promotion_id}:pending_claims:index` (score = timestamp)
    *   `HASH promo:{promotion_id}:pending_claims:data` (value chứa payload claim)
*   Job quét theo lô (ví dụ `LIMIT 1000`) các claim có `score < NOW() - 5 phút` từ ZSET index, đọc payload tương ứng trong Hash data và **push (publish) lại `COUPON_CLAIMED` lên Kafka**.
*   Khi republish bắt buộc giữ `claim_id` bất biến (`business_id = claim_id`, `payload.claim_id = claim_id`), tạo `event_id` mới cho lần phát lại, và gắn `payload.production_source = SWEEPER_RESCUE`.
*   Consumer downstream phải dedup hai tầng: `event_id` (delivery-level) + `claim_id` (business-level).
*   **Chi tiết:** Xem thuật toán SPOP [Tranh mã Flash Sale](redis_caching_design.md#script-1-thuật-toán-giật-mã-flash-sale-o1-spop-claiming).

### 2.3. Reservation TTL Sweep
**Loại hình:** Cronjob micro-batch quét định kỳ (mỗi 1 phút).

**Sứ mệnh:** Khi user đang ở Checkout, mã giảm giá bị khoá tạm (Reserve) vào Redis. Nếu họ treo không bấm thanh toán, slot này bị giam. Cần nhả lại ngân sách sau 15 phút.
*   **Hoạt động:**
    *   Mỗi reservation ghi song song 2 cấu trúc: key TTL `promo:{promotion_id}:reservation:{order_id}` và index `ZSET promo:reservation:index` (member=`{promotion_id}:{order_id}`, score=`expire_at_epoch_ms`).
    *   Job quét `ZRANGEBYSCORE promo:reservation:index -inf now_ms LIMIT 0 1000` để lấy reservation quá hạn.
    *   Trước khi rollback, bắt buộc check DB idempotency `REDEEM_COUPON + action_scope_key='FINAL'`; nếu đã redeem thì chỉ `ZREM` index member (no-op an toàn).
    *   Nếu chưa redeem và reservation key đã mất (TTL hết hoặc luồng khác đã dọn), gọi Script 4 để nhận mã trả về chuẩn; nếu mã `RESERVATION_MISSING_PAYLOAD` thì chỉ `ZREM` index member + enqueue reconciliation repair task (không coi là release đầy đủ).
    *   Nếu chưa redeem và reservation key còn tồn tại, gọi Lua Script 4 để release `reserved_qty/reserved_amount`; Script 4 tự dọn `DEL reservation_key + ZREM index` theo nguyên tắc idempotent.
    *   Sau nhánh release thực tế, ghi `promotion_event_idempotency(event_id='SWEEP-CANCEL:{promotion_id}:{order_id}:FINAL', action_type='CANCEL_COUPON', action_scope_key='FINAL')` theo chiến lược conflict-safe (`ON CONFLICT DO NOTHING`).
    *   Luồng Confirm/Rollback/Sweep không khóa nhau toàn cục; tính đúng đắn được bảo vệ bằng idempotency DB và thao tác Redis idempotent.

### 2.4. Wallet Expiry (Lazy Inline — không cần job nền)
**Loại hình:** Không có job nền. Logic `SAVED → EXPIRED` chạy lazy trong [Voucher Discovery API](voucher_discovery_api.md#51-lazy-expiry-update-inline-trong-discovery).

**Nguyên tắc:**
*   Khi Discovery query `user_coupon_wallets` lấy danh sách mã `SAVED` của user, với mỗi row có `expired_at <= NOW()`: thực hiện `UPDATE status = 'EXPIRED' WHERE id = :id AND status = 'SAVED'` (idempotent qua guard `status = 'SAVED'`).
*   Sau update, row bị filter ra khỏi response và truy vấn kế tiếp không thấy nữa.
*   `expired_at` được gán ngay lúc acquire/save bằng `saved_at + 30 ngày`.

**Trade-off:**
*   Ưu: Không tốn Cronjob resource, đơn giản hóa vận hành, không có lag giữa lúc mã hết hạn và lúc job chạy.
*   Nhược: User không mở Discovery sẽ giữ `status = 'SAVED'` dù đã quá hạn (cosmetic, không ảnh hưởng business — filter query `expired_at > NOW()` vẫn loại ra khi cần).
*   Nếu cần dữ liệu audit chính xác, query bổ sung `WHERE status = 'EXPIRED'` cho ra trạng thái đã chốt.

---

## 3. Data Maintenance & Cleanup Jobs (PostgreSQL)

### 3.1. Coupon Claims Cleanup Job
**Loại hình:** Cronjob chạy ban đêm (VD: 3:00 AM).

**Sứ mệnh:** Hard delete các record "Tranh mã ảo" ở bảng `coupon_claims` đã quá retention, tránh làm phình DB sau các đợt Siêu Sale.
*   **Hoạt động:**
    *   Áp dụng Domain-Driven Sweeping: Chỉ xóa những record thuộc về campaign đã kết thúc (Ended) + Hết hiệu lực quá 30 ngày.
    *   Không chạy lưu trữ dài hạn (Cold Storage) do đây chỉ là vé tranh lượt.

### 3.2. Idempotency Key Cleanup
**Loại hình:** Cronjob chạy hàng ngày (VD: 2:00 AM).

**Sứ mệnh:** Dọn rác bảng Log giao dịch chống lặp (Idempotency).
*   **Hoạt động:**
    *   Retention tối thiểu phải >= `refund_window + 15 ngày` (khuyến nghị 45 ngày) để tránh mở lại cửa duplicate khi replay trễ.
    *   Ví dụ chuẩn vận hành: `DELETE FROM promotion_event_idempotency WHERE processed_at < NOW() - INTERVAL '45 days';`

### 3.3. Outbox Garbage Collection
**Loại hình:** Cronjob chạy hàng ngày.

**Sứ mệnh:** Dọn rác bảng Outbox.
*   **Hoạt động:**
*   Xóa record quá hạn theo `created_at`, nhưng phải chặn bằng mốc an toàn của connector để không xóa sớm event chưa được ship:
*   `DELETE FROM promotion_outbox WHERE created_at < NOW() - INTERVAL '7 days' AND created_at < :connector_safe_watermark;`
    *   *(Đã bao gồm trong thiết kế [Outbox Pattern](promotion_outbox_pattern.md#44-dọn-rác-outbox-garbage-collection)).*

### 3.4. Markdown Expiry Scheduler (Redis ZSET va Cron Fallback)
**Loại hình:** Redis-driven scheduler loop (near-real-time 250ms-1s) + CronJob fallback self-healing (10-30 giây).

**Sứ mệnh:** Kết thúc `markdown_rules` đúng mốc `end_time` gần chuẩn giây để phát event [`MARKDOWN_ENDED`](promotion_events.md#23-markdown_ended) (reason: `EXPIRED`). Cron fallback chỉ đóng vai trò cứu hộ khi miss index hoặc scheduler loop gián đoạn.

**Cấu trúc Redis bắt buộc:**
*   `ZSET promo:markdown:expiry:index` (score = `end_time_epoch_ms`, member = `rule_id`).
*   `STRING promo:markdown:expiry:lock:{rule_id}` (khóa `SETNX` TTL ngắn chống xử lý trùng giữa nhiều instance).

**Chi tiết luồng hoạt động:**
1. **Write-path đồng bộ lịch:** Mỗi khi rule `ACTIVE` được tạo/cập nhật `end_time`, service upsert `ZADD promo:markdown:expiry:index <end_time_epoch_ms> <rule_id>`. Nếu rule chuyển `INACTIVE/ENDED`, phải `ZREM` khỏi index.
2. **Scheduler loop (đường chính):** Worker chạy chu kỳ ngắn, quét `ZRANGEBYSCORE promo:markdown:expiry:index -inf now_ms LIMIT 0 200`.
3. **Claim + guard:** Với từng `rule_id` quá hạn, acquire lock `SETNX promo:markdown:expiry:lock:{rule_id}`. Nếu lock fail thì skip vòng hiện tại.
4. **DB transaction (source-of-truth):**
    - `UPDATE markdown_rules SET status = 'ENDED' WHERE id = :rule_id AND status = 'ACTIVE' AND end_time <= NOW()`
    - Nếu `row_count = 1`, insert outbox: `INSERT INTO promotion_outbox (... event_type='MARKDOWN_ENDED', payload.reason='EXPIRED' ...)` trong cùng transaction.
5. **Post-commit cleanup:** Commit thành công mới `ZREM promo:markdown:expiry:index :rule_id`.
6. **Crash-window an toàn:** Nếu crash sau commit trước `ZREM`, lần xử lý sau trở thành no-op nhờ DB guard `status='ACTIVE' AND end_time<=NOW()`, rồi tự dọn index.
7. **Cron fallback self-healing:** Job định kỳ 10-30 giây quét DB để vá miss schedule/index drift:
    ```sql
    SELECT id, campaign_id, scope_type, ref_id
    FROM markdown_rules
    WHERE status = 'ACTIVE' AND end_time <= NOW()
    LIMIT 200 FOR UPDATE SKIP LOCKED;
    ```
    Sau đó chạy cùng transaction ở bước 4.
8. **Startup rebuild index:** Khi service khởi động hoặc sau Redis failover, bootstrap lại `promo:markdown:expiry:index` từ tập rule `ACTIVE` có `end_time > NOW()`.

*   **Idempotency & đa instance:** Khóa Redis chỉ chống đua ngắn hạn. Tính đúng đắn cuối cùng do DB guard + transaction outbox quyết định; `row_count = 0` luôn là no-op hợp lệ.

### 3.5. CDC Dead Letter Monitor
**Loại hình:** CronJob mỗi 5 phút.

**Sứ mệnh:** Theo dõi tình trạng sức khoẻ của hệ thống Event-driven.
*   **Hoạt động:**
    *   Quét DLQ topic của Kafka Connect (ví dụ `t.promotion.outbox.dlq`) và trạng thái connector task (`FAILED/UNASSIGNED`), đồng thời theo dõi connector lag.
*   Bắn cảnh báo tới SysAdmin qua Slack/PagerDuty; xử lý bằng cách replay message từ DLQ hoặc restart task connector theo runbook.
*   Không dùng `status = 'DEAD'` trong bảng outbox vì retry/DLQ ownership đã chuyển sang Kafka Connect.

### 3.6. Redis-DB Budget Reconciliation Job
**Loại hình:** CronJob micro-batch mỗi 15 phút (kèm full-scan off-peak hằng ngày).

**Sứ mệnh:** Phát hiện và sửa drift giữa Redis counters (`used/reserved`) và trạng thái nghiệp vụ bền vững sau các tình huống crash window (Redis script thành công nhưng DB commit lỗi, replay trễ, hoặc timeout giữa các bước Saga).

**Nguồn chuẩn (Source of Truth) bắt buộc - DB-first:**

| Counter | Nguồn chuẩn | Vai trò |
|---|---|---|
| `total_qty`, `total_amount` | `promotion_budgets` (PostgreSQL) | Cấu hình ngân sách bền vững |
| `used_qty`, `used_amount` | `coupon_usages` (PostgreSQL ledger) + gate `promotion_event_idempotency` | Sổ cái quyết định trạng thái đã dùng |
| `reserved_qty`, `reserved_amount` | Reservation còn hiệu lực trong Redis index/payload, đã lọc qua idempotency `REDEEM_COUPON FINAL` | Trạng thái tạm thời runtime (không lưu trong DB ledger) |

*   **Hoạt động:**
    1. Chọn tập `promotion_id` cần đối soát từ cửa sổ biến động gần đây (idempotency/outbox) và bổ sung full-scan định kỳ để bắt drift âm thầm.
    2. Dựng `expected snapshot`:
        * `expected_total_*` từ `promotion_budgets`.
        * `expected_used_*` từ `coupon_usages` (đã trừ phần refund lũy kế) có đối chiếu idempotency.
        * `expected_reserved_*` từ `promo:reservation:index` + reservation payload còn tồn tại; bỏ qua reservation đã có bản ghi `REDEEM_COUPON FINAL`.
    3. Đọc `actual snapshot` từ Redis `promo:{id}:budget` và tính drift theo từng chiều (`used_qty`, `used_amount`, `reserved_qty`, `reserved_amount`).
    4. Nếu drift vượt ngưỡng, acquire lock `promo:budget:reconcile:lock:{promotion_id}`. Nếu lock contention thì skip vòng hiện tại, retry ở vòng kế tiếp.
    5. Sau khi lock thành công, re-read Redis snapshot để loại bỏ stale read; chỉ repair nếu drift vẫn còn.
    6. Apply repair script đưa Redis counters về `expected snapshot` với guard bất biến (`>= 0`, không vượt `total_*`).
    7. Ghi audit đầy đủ (`before/after`, `run_id`, nguyên nhân), emit metrics `promotion_budget_drift_count`, `promotion_budget_repair_count`, `promotion_budget_repair_failed_count`, và alert khi drift lặp lại trên cùng `promotion_id`.
    8. Tuyệt đối không ghi ngược PostgreSQL từ số liệu Redis trong worker này. Hướng đối soát duy nhất là **DB -> Redis**.
