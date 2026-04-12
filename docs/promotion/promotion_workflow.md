# Quy trình Hoạt động (Workflows) - Promotion Service

**Liên kết nhanh (Related Documents):**
*   [Database Schema](promotion_db_design.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   [Rule Engine Design](rule_engine_design.md)
*   [Kafka Events Schema](promotion_events.md)
*   [Outbox Pattern](promotion_outbox_pattern.md)
*   [Background Workers & Jobs](promotion_workers.md)
*   [Coupon Claims Deletion Strategy](coupon_claims_deletion_strategy.md)

---

Tài liệu này tổng hợp toàn cảnh các luồng xử lý (Workflows) từ phía Client, Server, Database, Cache, đến Message Queue (Kafka), Worker, và Background Job dành riêng cho Promotion Service.

---

## 1. Luồng Tạo mới và Cập nhật Khuyến mãi (Admin Workflow)

**Mục tiêu:** Admin tạo mã giảm giá hoặc thay đổi cấu hình mà không làm sập Database do `Thundering Herd` (Bão truy cập).

1. **[Admin UI]** Admin thêm mới / chỉnh sửa `promotions` hoặc `markdown_rules`.
2. **[Backend API]** Ghi dữ liệu vào PostgreSQL. Các event cần tích hợp liên service (đặc biệt `MARKDOWN_*`, `COUPON_REDEEMED/CANCELLED/REFUNDED`, `BUDGET_EXHAUSTED`) phải ghi vào bảng [`promotion_outbox`](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing) trong cùng Transaction (Xem chi tiết [Outbox Pattern](promotion_outbox_pattern.md)).
3. **[Redis Pub/Sub]** Backend bắn Pub/Sub event `PROMO_EVICT {promotion_id}`.
4. **[Caffeine L1 Cache]** Các Java Instances nhận được event, thay vì xoá trắng (invalidate), Caffeine kích hoạt cơ chế `refreshAfterWrite`. 
5. **[Async Reload]** Caffeine ném 1 luồng duy nhất ngầm xuống DB để bốc cấu hình (JSONB rules) lên RAM. Lúc này, nếu có 100k user hit vào, chúng vẫn nhận Stale Data (dữ liệu cũ) với tốc độ O(1), không ai bị đứng chờ.
6. **[Zero-Latency CQRS (Dành riêng cho Markdown)]** Nếu Admin tạo/cập nhật mã Gạch Chéo (`markdown_rules`), [Outbox CDC Connector](promotion_outbox_pattern.md) đọc WAL của PostgreSQL cho bảng Outbox và phát Event [`MARKDOWN_CREATED`](promotion_events.md#21-markdown_created) / [`MARKDOWN_UPDATED`](promotion_events.md#22-markdown_updated) lên Kafka kèm `start_time/end_time`. Product Service hứng event và chỉ apply giá khi đạt `start_time` (nếu event đến sớm thì xếp lịch local), sau đó đẩy Event sang Search Service dán thẳng vào Elasticsearch. Trang Danh sách sản phẩm của người dùng không cần tính toán lại giá.
7. **[Markdown Expiry Scheduler - Redis ZSET + Cron Fallback]** Cùng write-path ở bước 2, service đồng bộ lịch hết hạn vào Redis `ZSET promo:markdown:expiry:index` (score = `end_time_epoch_ms`, member = `rule_id`). Scheduler loop 250ms-1s quét rule đến hạn, chốt `status='ENDED'` + insert outbox `MARKDOWN_ENDED(reason='EXPIRED')` trong cùng transaction; Cron fallback 10-30 giây chỉ dùng để self-heal khi miss index hoặc scheduler gián đoạn.

---

## 2. Luồng Tranh Mã Flash Sale (SPOP Claiming)

**Mục tiêu:** 10,000 người dùng giành 1.000 vé cào trong đúng 1 giây mà CSDL vẫn tĩnh lặng.

1. **[Pre-warming Job]** Admin đăng 1.000 mã Flash Sale. Hệ thống ngầm Insert 1.000 dòng `AVAILABLE` vào bảng `coupon_claims` (DB) lấy được 1.000 cái ID (Vé). Push 1.000 ID này vào Redis Set: `promo:{promotion_id}:claim_pool`.
2. **[Client]** Nhấn nút "Lưu Mã" ngay khoảnh khắc 00:00:00.
3. **[Backend API -> Redis Lua]** Chạy [Lua Script 1](redis_caching_design.md#script-1-thuật-toán-giật-mã-flash-sale-o1-spop-claiming) (Tranh mã O(1)):
    *   Kiểm tra `promo:{promotion_id}:claimed:user:{user_id}` xem user bốc chưa, chặn spam.
    *   Gọi `SPOP` lôi 1 vé Ngẫu nhiên từ hồ chứa trả về.
    *   Lưu Backup vào Redis theo cặp key: `Hash promo:{promotion_id}:pending_claims:data` (payload theo `claim_id`) + `ZSET promo:{promotion_id}:pending_claims:index` (score = timestamp) để quét timeout an toàn.
4. **[Kafka Producer]** Backend ném Event Async: [`COUPON_CLAIMED`](promotion_events.md#24-coupon_claimed) vào Kafka với contract bắt buộc: `event_id` mới cho mỗi lần publish, `business_id = claim_id`, `payload.claim_id` bất biến, `payload.production_source = API_DIRECT`. Trả kết quả về cho Client "Thành công".
5. **[Kafka Consumer (Worker)]** Thong thả đọc Event, gọi lệnh SQL có guard: `UPDATE coupon_claims SET owner_id = 99, status = 'CLAIMED', claimed_at = NOW() WHERE id = 549 AND status = 'AVAILABLE' AND owner_id IS NULL`. Xong việc, dọn tàn dư Redis bằng `HDEL promo:{promotion_id}:pending_claims:data 549` + `ZREM promo:{promotion_id}:pending_claims:index 549`.

> **[Background Job Cứu hộ (Fault Tolerance Job)]** 
Dành cho trường hợp App bị tắt nguồn (Killed) ngay giữa bước 3 và bước 4 (Redis đã báo lưu mã nhưng Kafka mất điện):
Chạy micro-batch mỗi 1 phút, quét `promo:{promotion_id}:pending_claims:index` lấy các claim có score `< NOW() - 5 phút`. Với mỗi claim bị kẹt, job đọc payload từ Hash data rồi republish `COUPON_CLAIMED` với `event_id` mới, `business_id = claim_id`, `payload.claim_id` giữ nguyên, `payload.production_source = SWEEPER_RESCUE`; sau đó giữ nguyên claim trong Redis cho tới khi Worker DB xử lý thành công và dọn sạch. Downstream bắt buộc dedup 2 tầng (`event_id` + `claim_id`). Vé KHÔNG bị trả lại Pool, bảo vệ tuyệt đối trải nghiệm của khách hàng đã nhận thông báo "Thành công".

---

## 3. Luồng Tính Giá Giỏ Hàng (In-memory Rule Engine)

**Mục tiêu:** Tính toán giá tiền giảm với điều kiện nghiệp vụ đa hình (JSON) mà độ trễ chỉ `< 1ms`.

1. **[Checkout Service]** Bắn API `/calculate-discount` gửi toàn bộ Payload giỏ hàng và định danh User sang Promotion.
2. **[Promotion Backend]** Đọc Caffeine Cache (L1) lấy được JSON AST Rules đã được Parse sẵn thành `RuleNode` Object (VD: check tổng giỏ `> 500k` VÀ thanh toán `VNPAY`). Xem chi tiết thuật toán tại [Rule Engine Design](rule_engine_design.md).
3. **[Evaluation]** Dùng Visitor Pattern quét Node Object cấp tốc dưới RAM dựa theo Payload.
4. **[Shopee Calculation Flow]**: Xếp hạng và tính khấu trừ theo trình tự:
    *   (1) Áp Shop Voucher lên Giá gốc.
    *   (2) Áp Shopee Voucher lên giá đã trừ Shop Voucher.
    *   (3) Áp Freeship lên Phí vận chuyển.
5. Trả response về cho Order/Cart bao gồm tổng tiền giảm và chi tiết từng loại mã.

> [!NOTE]
> **Dependency một chiều giữa các slot (bắt buộc):**
> - Đổi Shop Voucher có thể làm đổi eligibility của Platform/Freeship do thay đổi `total_after_shops`.
> - Đổi Platform Voucher hoặc Freeship Voucher không làm đổi eligibility của Shop Voucher vì Shop luôn evaluate trên sub-cart trước bước Platform/Freeship.

### 3.1. Recompute Contract với Checkout Preview

- Khi user đổi voucher shop/platform/freeship, Checkout vẫn gọi `/calculate-discount`; Promotion full evaluate cả 3 slot trong một lần để trả kết quả nhất quán.
- Khi user đổi địa chỉ giao hàng, Checkout phải gọi lại `/calculate-discount` sau khi Inventory trả về tổng phí ship mới.
- Quantity mutation không diễn ra trong Checkout Preview; input số lượng luôn lấy từ Cart snapshot.

> [!IMPORTANT]
> Promotion Service là nguồn tính toán chuẩn cho discount lines. Checkout Service chỉ orchestration và hiển thị. Không được tồn tại 2 engine tính giảm giá độc lập cho cùng một request.

---

## 4. Luồng Thanh Toán (Saga Checkout Flow)

**Mục tiêu:** Giữ chỗ ngân sách voucher đúng tại thời điểm User thực sự Submit đặt hàng, tránh lạm dụng hệ thống, và đối soát Ledger CSDL khi thanh toán thành công/thất bại.

> **[QUYẾT ĐỊNH THIẾT KẾ — Chống lạm dụng (Anti-Abuse)]**
> Promotion budget **KHÔNG được khóa tại bước Checkout Preview**. Nếu khóa tại Preview, kẻ xấu có thể dùng automated tool mở hàng loạt checkout session, áp voucher Flash Sale và treo đó không mua, làm cạn kiệt ngân sách mà không phát sinh đơn hàng thực sự. Tại bước Preview, Promotion Service chỉ **validate điều kiện và tính toán** số tiền giảm (đọc thuần túy, không thay đổi bất kỳ counter nào trên Redis). Reservation chỉ xảy ra trong **Temporal Saga** (`ReservePromotionActivity`) ngay sau khi User bấm Submit — thời điểm có cam kết mua hàng thực sự và đã có `order_id` cụ thể.

### BƯỚC A: KHÓA NGÂN SÁCH KHI SUBMIT (TRY)
1. **[Order Service / Temporal]** Sau khi User bấm "Xác nhận Đặt hàng", Temporal Workflow đã có `order_id` từ `CreateOrderActivity` (step đầu pre-payment), rồi chạy `ReservePromotionActivity` gọi API `/reserve` với tập mã đã chọn theo slot:
    * `selected_shop_vouchers` (map `seller_id -> promotion_id`, mỗi shop tối đa 1 mã)
    * `selected_platform_voucher_id` (0 hoặc 1 mã)
    * `selected_freeship_voucher_id` (0 hoặc 1 mã)
    * `discount_amounts_by_promotion_id`
    *(không auto-reserve toàn bộ mã gợi ý)*.
2. **[Promotion Backend -> Redis Lua]** Với đơn nhiều mã (shop/platform/freeship), bắt buộc gọi [Lua Script 2B](redis_caching_design.md#script-2b-batch-reserve-all-or-nothing-bắt-buộc-cho-multi-voucher) theo chế độ **all-or-nothing**. Script thực hiện 2 pass: `validate-only` toàn bộ mã trước, rồi mới `apply` đồng loạt khi tất cả đều hợp lệ.
3. **[Reservation TTL Tracking]** Chỉ khi pass toàn bộ, **Lua Script 2B** tự ghi key [`promo:{promotion_id}:reservation:{order_id}`](redis_caching_design.md#24-reservation-ttl-tracking-chống-khóa-vĩnh-viễn-ngân-sách) cho từng mã và `ZADD promo:reservation:index <expire_at_epoch_ms> <promotion_id:order_id>` trong cùng phiên atomic. TTL key reservation dùng **business timeout 15 phút + cleanup grace window** để Sweeper luôn có payload release trong cửa sổ quét biên.
4. **[Xử Lý lỗi có cấu trúc]** Nếu bất kỳ mã nào fail ở pass validate (ví dụ freeship hết ngân sách), Script 2B trả lỗi có cấu trúc, ví dụ:
     ```json
     {
         "error_code": "BUDGET_EXHAUSTED",
         "failed_promotion_id": "FREESHIP_123",
         "failed_slot": "FREESHIP_VOUCHER",
         "failed_reason": "BUDGET_AMOUNT_EXHAUSTED",
         "reserve_mode": "ALL_OR_NOTHING"
     }
     ```
5. **[Order Service / Temporal]** Nhận lỗi reserve có cấu trúc -> chạy compensation pre-payment theo LIFO (`RollbackInventoryActivity` -> `CancelOrderActivity`) rồi kết thúc Saga ở trạng thái thất bại nghiệp vụ (không phải lỗi hệ thống 500).
6. **[Nếu reserve thành công]** API trả về "Khoá thành công" cho toàn bộ mã, timeout lock là 15 phút (gắn với Temporal Workflow Timer).

### BƯỚC A.1: RESERVE FAIL-FAST + FULL RE-EVALUATION (Checkout Proposal)
Áp dụng khi `ReservePromotionActivity` thất bại với `BUDGET_EXHAUSTED` hoặc lỗi eligibility tại submit-time.

1. **[Checkout Service]** Nhận lỗi từ Order/Temporal kèm `failed_promotion_id`.
2. **[Checkout Service]** Loại mã fail khỏi tập mã đã chọn, tạo `candidate_surviving_promotions[]`.
3. **[Checkout Service -> Promotion Service]** Gọi lại API evaluate (`/calculate-discount`) với **đúng cart_context hiện tại** và `candidate_surviving_promotions[]`.
4. **[Promotion Rule Engine]** Re-evaluate toàn bộ theo AST trong Caffeine L1, trả:
     * `eligible=true/false` cho từng mã candidate.
    * `ineligible_reason` chuẩn hoá (ví dụ `CONDITIONS_NOT_MET`, `MIN_PURCHASE_NOT_MET`, `PAYMENT_METHOD_NOT_MATCHED`, `CATEGORY_NOT_MATCHED`, `EXPIRED`).
     * `estimated_discount` và tổng tiền dự kiến sau khi loại mã không hợp lệ.
5. **[Checkout Service]** Dựng `dropped_promotions[]` chỉ gồm các mã bị loại (bao gồm mã fail budget ban đầu và mã bị kéo theo không còn hợp lệ).
    * Nếu `candidate_surviving_promotions[]` rỗng sau re-evaluation, `proposal_checkout_data.surviving_promotions` phải trả mảng rỗng và `new_total_amount` được tính theo trường hợp không áp mã.
    * `failed_reason` từ reserve (`BUDGET_QTY_EXHAUSTED|BUDGET_AMOUNT_EXHAUSTED`) chỉ dùng nội bộ để trace; reason public cho FE phải map về `BUDGET_EXHAUSTED`.
6. **[Checkout Service -> Frontend]** Trả `HTTP 409 Conflict` với contract:
     ```json
     {
         "error_code": "PROMOTION_RECALCULATION_NEEDED",
         "message": "Giỏ hàng có sự thay đổi về mã giảm giá.",
         "action": "RECALL_PREVIEW",
         "dropped_promotions": [
             {
                 "promotion_id": "FREESHIP_123",
                 "reason": "BUDGET_EXHAUSTED",
                 "user_message": "Mã Freeship đã hết ngân sách."
             },
             {
                 "promotion_id": "PLATFORM_789",
                 "reason": "CONDITIONS_NOT_MET",
                 "user_message": "Đơn hàng không còn đủ điều kiện áp dụng mã này do thay đổi tổng tiền."
             }
         ],
         "proposal_checkout_data": {
             "new_total_amount": 550000,
             "surviving_promotions": [
                 "SHOP_456"
             ]
         }
     }
     ```
7. **[Frontend Contract bắt buộc]** Khi user đồng ý phương án mới, client phải gọi lại `POST /checkout/preview` để lấy `checkout_token` mới rồi mới được submit lại. Không được tái sử dụng token đã vào trạng thái `SUBMITTED`.

### BƯỚC B: THANH TOÁN THÀNH CÔNG (CONFIRM)
1. **[Order Service / Temporal]** Sau khi nhận Payment Success, Temporal gọi đồng bộ `ConfirmPromotionActivity` sang Promotion Service (không chờ Kafka), và **bắt buộc truyền `idempotency_key` từ Order** cho từng `promotion_id` trong payload confirm.
2. **[Promotion Backend -> Redis Lua]** Với đơn nhiều promotion, backend gọi [Lua Script 3](redis_caching_design.md#script-3-xác-nhận-mua-thành-công-commit-redeem) theo từng `promotion_id` bằng **Redis Pipelining** (1 round-trip mạng), mỗi lệnh dùng lock key cấp voucher: `promo:lock:redeem:{promotion_id}:{order_id}`. Trước khi commit từng promotion, backend gia hạn key reservation thêm 60 giây (`EXPIRE +60s`) để tránh race với Sweeper tại mốc timeout.
3. Script chỉ xử lý atomic move `reserved -> used` và **không tự kết luận hoàn tất nghiệp vụ khi chưa có DB commit**. Idempotency quyết định cuối cùng nằm ở `promotion_event_idempotency`.
4. **[Dọn Reservation Key + Index]** Script 3 tự `DEL reservation_key + ZREM reservation:index` theo hướng atomic. Backend có thể `ZREM` best-effort lần nữa để self-heal khi retry, nhưng không được coi thao tác ngoài script là bước commit chính.
5. **[Promotion Service]** Sau khi Redis báo OK, service tiếp tục gõ xuống DB (Thong thả, không lo Lock):
    *   Lưu chốt Idempotent ở CSDL: `INSERT INTO promotion_event_idempotency (event_id = :idempotency_key_from_order, order_id, promotion_id, action_type = 'REDEEM_COUPON', action_scope_key = 'FINAL')`.
    *   Tăng Ledger Budget DB: `UPDATE promotion_budgets SET used_budget = ...`
    *   Cập nhật Ví User: `UPDATE user_coupon_wallets SET status = 'USED'` (kèm theo `used_at = NOW()`).
    *   Gắn sổ cái dòng tiền: `INSERT INTO coupon_usages(platform_funding, seller_funding, status = 'REDEEMED')` (không có trạng thái RESERVED trong DB).
    *   Nếu insert idempotency conflict, phải đọc trạng thái DB hiện hữu và trả về thành công idempotent, tuyệt đối không rollback ngược Redis đã commit.
    *   Promotion Service **không tự sinh `event_id`** ở nhánh sync này; chỉ dùng đúng `idempotency_key` do Order truyền vào.
6. **[Order Service]** Ngay khi branch `PAYMENT_SUCCESS` commit FSM `PENDING_PAYMENT` -> `PAID`, phát event `ORDER_PAID` cho downstream read-model/notification/cart cleanup (mốc tài chính). Mốc vận hành hoàn tất được phản ánh bằng `ORDER_STATUS_CHANGED` (`PAID` -> `PROCESSING`).

> [!WARNING]
> Nếu `ConfirmPromotionActivity` trả `RESERVATION_NOT_FOUND` (reservation đã hết hạn/được rollback trước đó), Order Service phải rẽ nhánh xử lý **late payment success**: chuyển đơn vào luồng hoàn tiền ngay lập tức thay vì cố redeem lại mã.

### BƯỚC C: THANH TOÁN THẤT BẠI / TỰ HUỶ (CANCEL/ROLLBACK)
1. **[Order Service / Temporal]** Hết hạn 15 phút hoặc User ấn Hủy Đơn, Temporal gọi đồng bộ `RollbackPromotionActivity` sang Promotion Service và **bắt buộc truyền `idempotency_key` từ Order** cho từng `promotion_id`.
2. **[Promotion Backend -> Redis Lua]** Với đơn nhiều promotion, backend gọi [Lua Script 4](redis_caching_design.md#script-4-nhả-ngân-sách-rollback--release-reservation) theo từng `promotion_id` bằng **Redis Pipelining** (1 round-trip mạng), mỗi lệnh dùng lock key cấp voucher `promo:lock:cancel:{promotion_id}:{order_id}`. Script release ngân sách ở giỏ `reserved` theo từng voucher độc lập.
3. **[Xóa Reservation Key + Index]** Script 4 tự `DEL reservation_key + ZREM reservation:index` theo nguyên tắc idempotent. Backend chỉ cần retry/verify best-effort khi gặp lỗi mạng sau khi script đã chạy.
4. **[Promotion Service]** Gõ DB:
    *   `INSERT INTO promotion_event_idempotency(event_id, order_id, promotion_id, action_type, action_scope_key) VALUES (:idempotency_key_from_order, ..., ..., 'CANCEL_COUPON', 'FINAL')`.
    *   Không mutate trạng thái ví ở nhánh cancel pre-payment: Reserve chỉ khóa runtime `reserved_*`, chưa consume voucher trong DB.
    *   Giữ nguyên trạng thái ví hiện hữu (thông thường vẫn là `'SAVED'`), không thực hiện update `USED -> SAVED` ở bước này.
    *   *(Không cần cập nhật `coupon_usages` vì luồng Reserve hoàn toàn không ghi Log này vào DB).*
    *   Promotion Service **không tự sinh `event_id`** ở nhánh sync cancel; chỉ dùng đúng `idempotency_key` do Order truyền vào.

### BƯỚC D: HẾT HẠN RESERVATION (TTL EXPIRY — Tự động nhả Budget)
Xử lý trường hợp user abandon checkout mà **không có Event nào** từ Order Service (network partition, user đóng app, Temporal bị lag).
1. **[Background Job Sweep Redis]** Chạy micro-batch mỗi 1 phút, quét `ZSET promo:reservation:index` với điều kiện score `<= NOW()` (`LIMIT 0 1000`) để lấy danh sách reservation đến hạn. Không dùng cách scan key đã hết TTL vì key có thể đã bị Redis xóa.
2. Với mỗi member `<promotion_id:order_id>`:
    *   Check DB idempotency `REDEEM_COUPON + action_scope_key='FINAL'`. Nếu đã tồn tại -> chỉ `ZREM` member index (no-op an toàn), không rollback.
    *   Nếu chưa redeem và reservation key đã mất -> gọi Script 4 để nhận mã kết quả chuẩn; nếu nhận `RESERVATION_MISSING_PAYLOAD` thì chỉ `ZREM` member index và đẩy task vào hàng đợi reconciliation repair (không coi là release đầy đủ).
    *   Nếu chưa redeem và reservation key còn -> gọi Script 4 để release reservation (Script 4 tự dọn `DEL + ZREM`).
    *   Sau nhánh release thực tế, ghi `promotion_event_idempotency(event_id='SWEEP-CANCEL:{promotion_id}:{order_id}:FINAL', action_type='CANCEL_COUPON', action_scope_key='FINAL')` theo quy tắc conflict-safe (`ON CONFLICT DO NOTHING`).
3. **[Chống đua Confirm/Rollback vs Sweep]** Hai nhánh không khóa nhau toàn cục; tính đúng đắn dựa trên idempotency DB + thao tác Redis idempotent. Trạng thái "không còn key/member" chỉ được coi no-op khi DB gate đã chốt `REDEEM_FINAL` hoặc `CANCEL_FINAL`; nếu thiếu payload khi chưa finalize thì bắt buộc đẩy reconciliation repair.
4. **Kết quả:** Ngân sách không bao giờ bị khóa vĩnh viễn dù bất kỳ dịch vụ nào sập. Xem Job [Reservation TTL Sweep](promotion_workers.md#23-reservation-ttl-sweep) để biết chi tiết luồng quét ngầm.

> [!IMPORTANT]
> **Gating bắt buộc cho fallback qua Kafka (`ORDER_PAID/CANCELLED/REFUNDED`):**
> 1. Nếu đã có bản ghi `promotion_event_idempotency` tương ứng (`FINAL` hoặc `refund_transaction_id`) thì coi message là audit-only, không mutate Redis/DB.
> 2. Chỉ kích hoạt fallback mutation khi synchronous activity trước đó bị thiếu hiệu ứng (mất response, timeout, hoặc crash giữa chừng).
> 3. Nếu payload hash của event trễ không khớp trạng thái hiện tại, phải đẩy parking-lot + alert thay vì tự động ghi đè.

### BƯỚC E: REDIS-DB BUDGET RECONCILIATION (Hậu kiểm)
1. Worker [3.6 Redis-DB Budget Reconciliation Job](promotion_workers.md#36-redis-db-budget-reconciliation-job) chạy định kỳ để vá drift do crash window/replay trễ.
2. Hướng đối soát là **DB -> Redis**: PostgreSQL ledger/idempotency là nguồn quyết định cuối cùng; Redis budget hash là runtime state có thể rebuild.
3. `reserved_*` không tồn tại trong DB ledger nên được rebuild từ Redis reservation index/payload còn hiệu lực, có lọc qua idempotency `REDEEM_COUPON FINAL`.

---

## 5. Luồng Đổi Trả Hàng / Hoàn Tiền (Refund Flow)

**Mục tiêu:** Khi đơn hàng đã giao thành công (`REDEEMED`) nhưng khách hàng đổi trả hàng hoàn tiền, cần hoàn lại ngân sách Promotion đã sử dụng.

1. **[Order Service/Kafka]** Bắn Event [`ORDER_REFUNDED`](promotion_events.md#33-order_refunded-inbound) bắt buộc có `event_id` ở envelope, `refund_transaction_id` và `promotion_refund_allocations[]` (mỗi phần tử chứa `promotion_id`, `promotion_scope_type`, `scope_seller_id`, `refunded_discount_amount`, `platform_funding_refund`, `seller_funding_refund`, `partial_restore_rule`, `voucher_restore_mode`).
2. **[Promotion Backend -> Redis Lua]** Với `promotion_refund_allocations[]`, backend gọi [Lua Script 5](redis_caching_design.md#script-5-hoàn-ngân-sách-sau-đổi-trả-hàng-refund-budget) theo từng `promotion_id` bằng **Redis Pipelining** (1 round-trip mạng), mỗi lệnh dùng lock key `promo:lock:refund:{promotion_id}:{order_id}`. Script trả về `applied_refund_qty/applied_refund_amount` thực tế sau guard không âm.
    - Nếu `refund_type = PARTIAL`, chỉ xử lý allocation `SHOP` thỏa `partial_restore_rule = RESTORE_ONLY_IF_FULL_SHOP_COVERED`.
    - Allocation `PLATFORM`/`SHIPPING` trong `PARTIAL` phải được coi là `0` (skip logic hoàn ngân sách).
3. **[Kafka Consumer (Worker)]** Gõ DB:
    *   `INSERT INTO promotion_event_idempotency(event_id = :kafka_envelope_event_id, order_id, promotion_id, action_type = 'REFUND_COUPON', action_scope_key = :refund_transaction_id)`.
    *   `UPDATE promotion_budgets SET used_quantity = used_quantity - :applied_refund_qty, used_budget = used_budget - :applied_refund_amount`.
    *   Cập nhật ví theo policy tái sử dụng + hiệu lực:
        *   Chỉ trả về `'SAVED'` khi promo cho phép tái sử dụng **và** `(expired_at IS NULL OR expired_at > NOW())`.
        *   Nếu promo cho phép tái sử dụng nhưng voucher đã quá hạn (`expired_at <= NOW()`), chuyển ví sang `'EXPIRED'` (hoặc giữ `'USED'` nếu không còn row ví để chuyển trạng thái).
        *   Nếu promo không cho phép tái sử dụng, giữ `'USED'`.
    *   Cập nhật `coupon_usages.refunded_amount += :applied_refund_amount`, `refund_count += 1`, set `status = PARTIALLY_REFUNDED` hoặc `REFUNDED` khi hoàn đủ, kèm `refunded_at = NOW()`.
4. **[Voucher unavailable - non-blocking]** Nếu script/DB xác định voucher không còn khả dụng ở thời điểm restore:
    - Ghi nhận `restore_outcome = VOUCHER_UNAVAILABLE` + reason code vào log/audit.
    - Phát cảnh báo vận hành để xử lý thủ công nếu cần.
    - **Không** yêu cầu rollback tiền hoàn đã thành công ở Payment.

> [!IMPORTANT]
> **Contract tạo `event_id` cho Confirm/Cancel/Refund:**
> - **Luồng Sync qua Temporal Saga (`/confirm`, `/cancel`, `/refund`)**: Promotion Service bắt buộc dùng đúng `idempotency_key`/`request_id` do Order truyền vào để ghi `promotion_event_idempotency.event_id`.
> - **Luồng Async qua Kafka (`ORDER_PAID/CANCELLED/REFUNDED`)**: Promotion Service bắt buộc dùng đúng `event_id` từ Kafka envelope để ghi DB.
> - Promotion Service tuyệt đối không tự sinh `event_id` cho các nhánh trên.

> [!WARNING]
> **Phân biệt CANCEL vs REFUND:**
> - **CANCEL** (Bước 4.C): Đơn hàng chưa thanh toán → nhả `reserved`. Không ghi trạng thái vào `coupon_usages`.
> - **REFUND** (Bước 5): Đơn hàng đã thanh toán và giao hàng xong → nhả `redeemed`. Cập nhật `coupon_usages` theo partial/full refund.
> Hai luồng này KHÔNG ĐƯỢC trộn lẫn để đảm bảo đối soát Ledger tài chính chính xác.

---

## 6. Luồng Gợi ý Voucher tại Checkout (Voucher Discovery Flow)

**Mục tiêu:** Hiển thị danh sách mã giảm giá khả dụng cho khách hàng khi mở khung voucher tại màn hình Checkout theo nguyên tắc read-only ở Discovery. Tray Voucher Sàn/Freeship chỉ dùng mã đã lưu trong ví; không có auto-save ở Submit.

> Chi tiết đầy đủ về API design, request/response, caching và edge cases xem tại: **[Voucher Discovery API](voucher_discovery_api.md)**.
>
> **Quy ước thời gian bắt buộc:** `promotions.start_time/end_time` là claim window (thời gian được tranh/lưu mã mới). Hạn dùng thực tế khi redeem cho mã đã sở hữu lấy theo `user_coupon_wallets.expired_at` (mặc định 30 ngày từ lúc acquire/save).

### Tóm tắt Luồng

1. **[Checkout Service]** Khách mở tray "Chọn Mã Giảm Giá" — Checkout gọi `GET /v1/promotions/voucher-suggestions` kèm `cart_context` (subtotal từng shop, tổng shipping fee, payment_method).
2. **[Promotion Backend → Redis]** Check `wallet:{user_id}:active_vouchers` (TTL 5 phút):
    *   **Cache HIT:** Lấy danh sách ví → bỏ qua DB query.
    *   **Cache MISS:** Query `user_coupon_wallets JOIN promotions` (Index Scan `user_id, status`) → Populate cache.
3. **[Rule Engine In-Memory]** Eval `conditions_rule` AST với `cart_context`, tính `estimated_discount`, gắn cờ `eligible/ineligible` cho từng mã (`< 1ms/mã`).
4. **[Budget Guard Read-time (Option A)]** Với tập mã đang `eligible`, Promotion đọc batch Redis budget hash bằng pipelining; mã cạn ngân sách/lượt bị downgrade sang `ineligible_reason = BUDGET_EXHAUSTED`. Nếu guard timeout, hệ thống fail-open và đánh dấu degraded cho observability.
5. **[Grouping]** Gom theo **slot model động** trả về:
    *   `SHOP_VOUCHER[]`: 1 slot/shop, sort by `estimated_discount DESC`.
    *   `PLATFORM_VOUCHER`: 1 slot toàn đơn.
    *   `FREESHIP_VOUCHER`: 1 slot — **chỉ nhận mã sàn (`PLATFORM`)**.
6. **[Khi khách chọn mã tại tray Sàn/Freeship]** Checkout gọi lại Preview với `promotion_id` đã lưu trong ví để tính giá dự kiến. Discovery không ghi ví và Submit cũng không auto-save thêm voucher.

> [!NOTE]
> Budget Guard ở Discovery giúp giảm stale UX nhưng **không thay thế** commit-time Reserve. Quyết định cuối cùng về exhausted budget vẫn do `ReservePromotionActivity` khi Submit.

### Quy tắc Freeship (Hard Rule)

Slot `FREESHIP_VOUCHER` chỉ có 1 chỗ và chỉ chấp nhận mã do Platform phát hành (`owner_type = PLATFORM`). Seller không được tạo/áp dụng mã freeship trong mọi luồng checkout.

---

## TỔNG KẾT
Kiến trúc Workflow trên thể hiện đỉnh cao của thiết kế **Data-Intensive Applications**:
1. Đẩy các luồng đọc (READ) lên In-memory (Caffeine AST) và CQRS (Elasticsearch).
2. Đẩy các luồng tranh chấp đồng thời (CONCURRENCY WRITE) lên Lua Script chạy Single Thread.
3. Bảo vệ chuỗi phân tán (SAGA) bằng idempotency nhiều tầng, trong đó PostgreSQL `promotion_event_idempotency` là chốt quyết định cuối cùng và Redis đóng vai trò guard tốc độ.
4. Khắc phục độ võng của Network Event bằng Background Job đền bù dữ liệu (Pending Claims Sweep + Reservation TTL Sweep).
5. Đảm bảo Reliable Event Publishing bằng [Outbox Pattern + CDC](promotion_outbox_pattern.md) thay vì phụ thuộc vào Kafka Produce trực tiếp.





