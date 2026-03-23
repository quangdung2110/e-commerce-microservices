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
2. **[Backend API]** Ghi dữ liệu vào PostgreSQL. Đồng thời ghi Event vào bảng [`promotion_outbox`](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing) trong cùng Transaction (Xem chi tiết [Outbox Pattern](promotion_outbox_pattern.md)).
3. **[Redis Pub/Sub]** Backend bắn Pub/Sub event `PROMO_EVICT {promotion_id}`.
4. **[Caffeine L1 Cache]** Các Java Instances nhận được event, thay vì xoá trắng (invalidate), Caffeine kích hoạt cơ chế `refreshAfterWrite`. 
5. **[Async Reload]** Caffeine ném 1 luồng duy nhất ngầm xuống DB để bốc cấu hình (JSONB rules) lên RAM. Lúc này, nếu có 100k user hit vào, chúng vẫn nhận Stale Data (dữ liệu cũ) với tốc độ O(1), không ai bị đứng chờ.
6. **[Zero-Latency CQRS (Dành riêng cho Markdown)]** Nếu Admin tạo mã Gạch Chéo (`markdown_rules`), [Outbox Relay Worker](promotion_outbox_pattern.md) đọc Outbox và phát Event [`MARKDOWN_CREATED`](promotion_events.md#21-markdown_created) lên Kafka. Product Service hứng event này, tự động join giá và update giá `promotional_price`, sau đó đẩy Event sang Search Service dán thẳng vào Elasticsearch. Trang Danh sách sản phẩm của người dùng không cần tính toán lại giá.

---

## 2. Luồng Tranh Mã Flash Sale (SPOP Claiming)

**Mục tiêu:** 10,000 người dùng giành 1.000 vé cào trong đúng 1 giây mà CSDL vẫn tĩnh lặng.

1. **[Pre-warming Job]** Admin đăng 1.000 mã Flash Sale. Hệ thống ngầm Insert 1.000 dòng `AVAILABLE` vào bảng `coupon_claims` (DB) lấy được 1.000 cái ID (Vé). Push 1.000 ID này vào Redis Set: `promo:{promotion_id}:claim_pool`.
2. **[Client]** Nhấn nút "Lưu Mã" ngay khoảnh khắc 00:00:00.
3. **[Backend API -> Redis Lua]** Chạy [Lua Script 1](redis_caching_design.md#script-1-thuật-toán-giật-mã-flash-sale-o1-spop-claiming) (Tranh mã O(1)):
    *   Kiểm tra `promo:{promotion_id}:claimed:user:{user_id}` xem user bốc chưa, chặn spam.
    *   Gọi `SPOP` lôi 1 vé Ngẫu nhiên từ hồ chứa trả về.
    *   Lưu Backup vội vào Redis Hash: `promo:{promotion_id}:pending_claims` để đề phòng sập App.
4. **[Kafka Producer]** Backend ném Event Async: [`COUPON_CLAIMED`](promotion_events.md#24-coupon_claimed) `{ claim_id: 549, user_id: 99 }` vào Kafka. Trả kết quả về cho Client "Thành công".
5. **[Kafka Consumer (Worker)]** Thong thả đọc Event, gọi lệnh SQL: `UPDATE coupon_claims SET owner_id = 99, status = 'CLAIMED' WHERE id = 549`. Xong việc, vòng lên gọi `HDEL promo:{promotion_id}:pending_claims 549`.

> **[Background Job Cứu hộ (Fault Tolerance Job)]** 
Dành cho trường hợp App bị tắt nguồn (Killed) ngay giữa bước 3 và bước 4 (Redis đã báo lưu mã nhưng Kafka mất điện):
Chạy mỗi 5 phút, quét `pending_claims`. Những vé kẹt > 5 phút được hỏi thăm xuống DB. Nếu DB báo trống trơn (`AVAILABLE`), Job tự động nhồi lại Event vào Kafka giùm, cứu sống dữ liệu dòng tiền hoàn hảo!

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

---

## 4. Luồng Thanh Toán (Saga Checkout Flow)

**Mục tiêu:** Giữ chỗ ngân sách voucher đúng tại thời điểm User thực sự Submit đặt hàng, tránh lạm dụng hệ thống, và đối soát Ledger CSDL khi thanh toán thành công/thất bại.

> **[QUYẾT ĐỊNH THIẾT KẾ — Chống lạm dụng (Anti-Abuse)]**
> Promotion budget **KHÔNG được khóa tại bước Checkout Preview**. Nếu khóa tại Preview, kẻ xấu có thể dùng automated tool mở hàng loạt checkout session, áp voucher Flash Sale và treo đó không mua, làm cạn kiệt ngân sách mà không phát sinh đơn hàng thực sự. Tại bước Preview, Promotion Service chỉ **validate điều kiện và tính toán** số tiền giảm (đọc thuần túy, không thay đổi bất kỳ counter nào trên Redis). Reservation chỉ xảy ra trong **Temporal Saga** (`ReservePromotionActivity`) ngay sau khi User bấm Submit — thời điểm có cam kết mua hàng thực sự và đã có `order_id` cụ thể.

### BƯỚC A: KHÓA NGÂN SÁCH KHI SUBMIT (TRY)
1. **[Order Service / Temporal]** Sau khi User bấm "Xác nhận Đặt hàng", Temporal Workflow chạy `ReservePromotionActivity`, gọi API `/reserve` với `{order_id, promotion_ids[], discount_amounts[]}`.
2. **[Promotion Backend -> Redis Lua]** Gọi [Lua Script 2](redis_caching_design.md#script-2-thuật-toán-giữ-chỗ-ngân-sách-budget-reservation) (Giữ Ngân sách). Dùng `HMGET`, check chéo cả `limit_qty` và `limit_budget`. Đủ tiền đủ vé -> Cộng lượng khóa vào `reserved_qty` và `reserved_budget` (Lệnh `HINCRBY/HINCRBYFLOAT`).
3. **[Reservation TTL Tracking]** Backend tạo Key Redis [`promo:{promotion_id}:reservation:{order_id}`](redis_caching_design.md#24-reservation-ttl-tracking-chống-khóa-vĩnh-viễn-ngân-sách) với TTL = 15 phút, chứa thông tin ngân sách đã khóa.
4. **[Nếu voucher hết budget]** Trả lỗi `BUDGET_EXHAUSTED` → Temporal chạy `RollbackInventoryActivity` → Saga dừng lại → Checkout Service nhận lỗi và thông báo user: *"Rất tiếc, voucher vừa được dùng hết. Vui lòng chọn voucher khác."*
5. API trả về "Khoá thành công", Timeout của khoá là 15 phút (gắn với Temporal Workflow Timer).

### BƯỚC B: THANH TOÁN THÀNH CÔNG (CONFIRM)
1. **[Order Service / Temporal]** Sau khi nhận Payment Success, Temporal gọi đồng bộ `ConfirmPromotionActivity` sang Promotion Service (không chờ Kafka).
2. **[Promotion Backend -> Redis Lua]** Gọi [Lua Script 3](redis_caching_design.md#script-3-xác-nhận-mua-thành-công-commit-redeem) (Commit Budget). Trước khi commit, backend gia hạn key reservation thêm 60 giây (`EXPIRE +60s`) để tránh race với Sweeper tại mốc timeout.
3. Script được bọc sẵn lệnh Chống Lặp `SETNX promo:{promotion_id}:idemp...` để chặn retry/duplicate request ở tầng service.
4. **[Xóa Reservation Key]** Backend xóa Key `promo:{promotion_id}:reservation:{order_id}` — không cần chờ TTL.
4. **[Promotion Service]** Sau khi Redis báo OK, service tiếp tục gõ xuống DB (Thong thả, không lo Lock):
    *   Lưu chốt Idempotent ở CSDL: `INSERT INTO promotion_event_idempotency (order_id, action = 'REDEEM')`.
    *   Tăng Ledger Budget DB: `UPDATE promotion_budgets SET used_amount = ...`
    *   Cập nhật Ví User: `UPDATE user_coupon_wallets SET status = 'USED'` (kèm theo `used_at = NOW()`).
    *   Gắn sổ cái dòng tiền: `INSERT INTO coupon_usages(platform_funding, seller_funding, status = 'REDEEMED')`.
5. **[Order Service]** Sau khi toàn bộ Post-Payment Saga hoàn tất, phát event `ORDER_PAID` cho downstream read-model/notification/cart cleanup.

### BƯỚC C: THANH TOÁN THẤT BẠI / TỰ HUỶ (CANCEL/ROLLBACK)
1. **[Order Service / Temporal]** Hết hạn 15 phút hoặc User ấn Hủy Đơn, Temporal gọi đồng bộ `RollbackPromotionActivity` sang Promotion Service.
2. **[Promotion Backend -> Redis Lua]** Gọi [Lua Script 4](redis_caching_design.md#script-4-nhả-ngân-sách-rollback--release-reservation) (Nhả Ngân sách). Bọc `SETNX` chống lặp y như Bước B. Lôi cái ngân sách ở giỏ `reserved` thả ra ngoài hồ.
3. **[Xóa Reservation Key]** Backend xóa Key `promo:{promotion_id}:reservation:{order_id}`.
4. **[Promotion Service]** Gõ DB:
    *   `INSERT INTO promotion_event_idempotency(action = 'CANCEL')`. 
    *   Đảo nghịch trạng thái Ví User về `'SAVED'`.
    *   Cập nhật trạng thái Sổ cái `coupon_usages` về `'CANCELLED'` (kèm `cancelled_at = NOW()`).

### BƯỚC D: HẾT HẠN RESERVATION (TTL EXPIRY — Tự động nhả Budget)
Xử lý trường hợp user abandon checkout mà **không có Event nào** từ Order Service (network partition, user đóng app, Temporal bị lag).
1. **[Background Job Sweep]** Chạy mỗi 2 phút, query `coupon_usages` tìm các record `status = 'RESERVED'` có `reserved_at < NOW() - 15 phút` **và không có cờ `confirm_inflight = true`**. Xem chi tiết cơ chế tại [Reservation TTL Tracking](redis_caching_design.md#24-reservation-ttl-tracking-chống-khóa-vĩnh-viễn-ngân-sách).
2. Với mỗi Reservation quá hạn, Job tự động trigger luồng Cancel (Script 4 + DB update) y như Bước C.
3. **Kết quả:** Ngân sách không bao giờ bị khóa vĩnh viễn. Xem Job [Reservation TTL Sweep](promotion_workers.md#23-cart-usage-reservation-evictor) để biết chi tiết luồng quét ngầm.

---

## 5. Luồng Đổi Trả Hàng / Hoàn Tiền (Refund Flow)

**Mục tiêu:** Khi đơn hàng đã giao thành công (`REDEEMED`) nhưng khách hàng đổi trả hàng hoàn tiền, cần hoàn lại ngân sách Promotion đã sử dụng.

1. **[Order Service/Kafka]** Bắn Event [`ORDER_REFUNDED`](promotion_events.md#33-order_refunded-inbound) kèm thông tin `order_id`, `promotion_id`, `discount_amount`.
2. **[Promotion Backend -> Redis Lua]** Gọi [Lua Script 5](redis_caching_design.md#script-5-hoàn-ngân-sách-sau-đổi-trả-hàng-refund-budget) (Refund Budget). Trừ `redeemed_qty` và `redeemed_budget` — ngân sách trả về hồ Available.
3. **[Kafka Consumer (Worker)]** Gõ DB:
    *   `INSERT INTO promotion_event_idempotency(action = 'REFUND')`.
    *   `UPDATE promotion_budgets SET used_quantity = used_quantity - 1, used_budget = used_budget - :amount`.
    *   Đảo nghịch trạng thái Ví User về `'SAVED'` (user có thể tái sử dụng mã).
    *   Cập nhật trạng thái Sổ cái `coupon_usages` về `'REFUNDED'` (kèm `refunded_at = NOW()`).

> [!WARNING]
> **Phân biệt CANCEL vs REFUND:**
> - **CANCEL** (Bước 4.C): Đơn hàng chưa thanh toán → nhả `reserved`. Trạng thái `coupon_usages` → `CANCELLED`.
> - **REFUND** (Bước 5): Đơn hàng đã thanh toán và giao hàng xong → nhả `redeemed`. Trạng thái `coupon_usages` → `REFUNDED`.
> Hai luồng này KHÔNG ĐƯỢC trộn lẫn để đảm bảo đối soát Ledger tài chính chính xác.

---

## 6. Luồng Gợi ý Voucher tại Checkout (Voucher Discovery Flow)

**Mục tiêu:** Hiển thị danh sách mã giảm giá khả dụng cho khách hàng khi mở khung voucher tại màn hình Checkout, đồng thời tự động lưu và áp dụng mã sàn `UNLIMITED_ALL` khi khách chọn dùng.

> Chi tiết đầy đủ về API design, request/response, caching và edge cases xem tại: **[Voucher Discovery API](voucher_discovery_api.md)**.

### Tóm tắt Luồng

1. **[Checkout Service]** Khách mở tray "Chọn Mã Giảm Giá" — Checkout gọi `GET /v1/promotions/voucher-suggestions` kèm `cart_context` (subtotal từng shop, tổng shipping fee, payment_method).
2. **[Promotion Backend → Redis]** Check `wallet:{user_id}:active_vouchers` (TTL 5 phút):
    *   **Cache HIT:** Lấy danh sách ví → bỏ qua DB query.
    *   **Cache MISS:** Query `user_coupon_wallets JOIN promotions` (Index Scan `user_id, status`) → Populate cache.
3. **[Promotion Backend → Caffeine L1]** Bổ sung các mã `UNLIMITED_ALL` đang ACTIVE từ RAM (không phát sinh I/O) — loại trừ mã đã có trong ví.
4. **[Rule Engine In-Memory]** Eval `conditions_rule` AST với `cart_context`, tính `estimated_discount`, gắn cờ `eligible/ineligible` cho từng mã (`< 1ms/mã`).
5. **[Grouping]** Gom thành **3 slot** trả về:
    *   `SHOP_VOUCHER[]`: 1 slot/shop, sort by `estimated_discount DESC`.
    *   `PLATFORM_VOUCHER`: 1 slot toàn đơn.
    *   `FREESHIP_VOUCHER`: 1 slot — **chỉ nhận mã sàn (`PLATFORM`)**.
6. **[Khi khách chọn mã `source = UNLIMITED_ALL`]** Checkout gọi lại Preview với `promotion_id` đó → Promotion Service **upsert** `user_coupon_wallets` (INSERT SAVED, ON CONFLICT DO NOTHING) → Reserve ngân sách (Lua Script 2) → Calculate discount → Trả về CheckoutToken mới.

### Quy tắc Freeship (Hard Rule)

Slot `FREESHIP_VOUCHER` chỉ có 1 chỗ và chỉ chấp nhận mã do Platform phát hành (`owner_type = PLATFORM`). Seller không được tạo/áp dụng mã freeship trong mọi luồng checkout.

---

## TỔNG KẾT
Kiến trúc Workflow trên thể hiện đỉnh cao của thiết kế **Data-Intensive Applications**:
1. Đẩy các luồng đọc (READ) lên In-memory (Caffeine AST) và CQRS (Elasticsearch).
2. Đẩy các luồng tranh chấp đồng thời (CONCURRENCY WRITE) lên Lua Script chạy Single Thread.
3. Bảo vệ chuỗi phân tán (SAGA) bằng Kỹ thuật kẹp bánh mì (Redis SETNX kết hợp vòng sau là PostgreSQL Unique Key Idempotent).
4. Khắc phục độ võng của Network Event bằng Background Job đền bù dữ liệu (Pending Claims Sweep + Reservation TTL Sweep).
5. Đảm bảo Reliable Event Publishing bằng [Outbox Pattern](promotion_outbox_pattern.md) thay vì phụ thuộc vào Kafka Produce trực tiếp.





