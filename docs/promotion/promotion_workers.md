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
| `t.order.events` | `promo-order-consumer` | Consumer | Hứng sự kiện Order (`ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_REFUNDED`) để đối soát và xử lý fallback. |
| `t.promotion.markdown.events`, `t.promotion.coupon.events`, `t.promotion.budget.events` | - | Producer | Phát các sự kiện do Promotion Service tạo ra (Markdown, Coupon, Budget). |

---

## 1. Trụ cột Message & Event (Kafka)

### 1.1. Outbox Relay Worker (Producer)
**Loại hình:** Polling Worker chạy liên tục.

**Sứ mệnh:** Đảm bảo mọi sự kiện sinh ra trong Promotion Service (như tạo Rule cấu hình Giảm giá gạch chéo, user claim mã thành công) đều được đưa lên Kafka một cách an toàn (At-Least-Once Delivery).
*   **Chi tiết thiết kế:** Xem tại [Outbox Pattern](promotion_outbox_pattern.md#4-outbox-relay-worker-chi-tiết).
*   **Hoạt động:** Quét bảng `promotion_outbox` mỗi 500ms, lấy các record `PENDING` hoặc `FAILED` (đến hạn retry), map `event_type` sang đúng topic đích (`t.promotion.markdown.events` / `t.promotion.coupon.events` / `t.promotion.budget.events`) rồi `producer.send(...)`. Xử lý cập nhật DB và Exponential Backoff nếu Kafka lỗi.

### 1.2. Order Events Consumer (Consumer)
**Loại hình:** Kafka Consumer (Long-running).
**Consumer Group:** `promo-order-consumer`

**Sứ mệnh:** Lắng nghe chu trình sống của Đơn hàng từ bảng Sổ cái phân tán (Order Service) để thực thi trừ / hoàn ngân sách cho Khuyến mãi.
*   **Chi tiết luồng xử lý:** Xem tại [Promotion Workflows - Mục 5](promotion_workflow.md#5-luồng-đồng-bộ-sau-thanh-toán-kafka-consumers).
*   **Hoạt động:**
    1. Hứng `ORDER_PAID`: Đối soát/reconciliation và fallback recovery khi cần.
    2. Hứng `ORDER_CANCELLED`: Đối soát trạng thái, xử lý fallback khi synchronous rollback bị gián đoạn.
    3. Hứng `ORDER_REFUNDED` (Trả hàng hoàn tiền): Đối soát và kích hoạt fallback hoàn ngân sách khi cần.
*   **Lưu ý kiến trúc:** Đường xử lý chính cho Confirm/Rollback trong checkout là gọi đồng bộ qua Temporal Activities (`ConfirmPromotionActivity`, `RollbackPromotionActivity`). Kafka consumer này đóng vai trò safety-net.
*   **Bảo vệ Idempotency:** Worker tích hợp Redis Lock (`SETNX`) kết hợp DB Constraint (`promotion_event_idempotency`) để chặn duplicate messages từ Kafka (đảm bảo xử lý Exactly-Once).

### 1.3. Flash Sale Claim Async Worker (Consumer / Internal Queue)
**Loại hình:** Kafka Consumer (High-throughput) / Internal Queue Worker.
**Consumer Group:** `promo-claim-consumer`

**Sứ mệnh:** Xử lý bất đồng bộ kết quả giật mã Flash Sale. Giúp tách rời việc giành mã trên RAM (siêu tốc, 0 độ trễ) với quá trình cập nhật xuống đĩa mềm PostgreSQL (chậm, dễ nghẽn), bảo vệ DB khỏi bão Traffic.
*   **Chi tiết luồng xử lý:** Xem tại [Thuật toán Giật Mã Flash Sale O(1)](redis_caching_design.md#script-1-thuật-toán-giật-mã-flash-sale-o1-spop-claiming).
*   **Hoạt động:**
    1. Lắng nghe event `COUPON_CLAIMED` (hoặc thông điệp `CLAIM`) đẩy từ Topic `t.promotion.coupon.events` (hoặc internal queue). Event này do API Backend bắn ra ngay sau khi chạy Lua Script SPOP trên Redis thành công.
    2. Thực thi Database Transaction: Cập nhật `UPDATE coupon_claims SET owner_id = :user_id, status = 'CLAIMED', claimed_at = NOW() WHERE id = :claim_id;` và insert mã vào `user_coupon_wallets`.
    3. Dọn dẹp tàn dư: Gọi lên Redis `HDEL promo:{promotion_id}:pending_claims :claim_id` để xác nhận Event đã được ghi đĩa an toàn, tránh bị Sweeper Job (Mục 2.2) quét trúng.

---

## 2. Jobs Đồng bộ Cache (Redis)

### 2.1. Rule & Metadata Cache Warmer (Cronjob / Trigger-based)
**Loại hình:** Chạy khi Service Startup, lưu trên Local Cache (Caffeine) & Redis, invalidate qua Redis Pub/Sub.

**Sứ mệnh:** Giữ cho cây cấu hình Luật Khuyến Mãi (JSONB AST) luôn sẵn sàng trên RAM của mọi instance Promotion Service để engine duyệt tính toán không độ trễ (< 10ms).
*   **Hoạt động:**
    1. Khởi động: Tải toàn bộ `campaigns` và `promotions` có trạng thái `ACTIVE` vào Caffeine L1.
    2. Admin thay đổi: Bắn Pub/Sub `channel:promo:cache:evict` → Tất cả instance xóa cache local, gọi về DB lấy mới.
*   **Chi tiết:** Xem [Rule & Metadata Cache (L1 / L2)](redis_caching_design.md#21-rule--metadata-cache-l1--l2).

### 2.2. Flash Sale Pending Claims Sweeper
**Loại hình:** Cronjob quét mỗi 1 phút / 5 phút.

**Sứ mệnh:** Dọn dẹp các phiếu phát hành Voucher (Hàng đợi Flash Sale SPOP) đang treo ghim do user claim được vé ảo từ Redis Cache nhưng crash / không thèm gọi API lấy vé thực, dẫn tới mất slot.
*   **Hoạt động:**
    *   Sử dụng Redis `ZSET pending_claims:{promo_id}`.
    *   Job quét các claim có `timestamp < NOW() - 5 phút`. Lấy list vé kẹt đó đẩy trả lại vào `AVAILABLE_POOL` (Set) cho lượt tranh giành mới.
*   **Chi tiết:** Xem thuật toán SPOP [Tranh mã Flash Sale](redis_caching_design.md#script-5-tranh-mã-flash-sale-anti-deadlock-spop).

### 2.3. Cart Usage Reservation Evictor
**Loại hình:** Cronjob dọn dẹp hoặc dùng Redis Key Expiration (TTL).

**Sứ mệnh:** Khi user đang ở Checkout, mã giảm giá bị khoá tạm (Reserve) vào Redis. Nếu họ treo không bấm thanh toán, slot này bị giam. Cần nhả lại ngân sách sau 15 phút.
*   **Hoạt động:**
    *   Mỗi mã giữ một Redis Hash `promo:{promotion_id}:reservation:{order_id}` có TTL 15 phút.
    *   Khi hết hạn, Redis bắn Key Space Notification hoặc Worker định kỳ đối chiếu bảng `coupon_usages` (status = `RESERVED` > 15m) để Rollback: +1 Quantity, +Budget cho Campaign.

---

## 3. Data Maintenance & Cleanup Jobs (PostgreSQL)

### 3.1. Coupon Claims Cleanup Job
**Loại hình:** Cronjob chạy ban đêm (VD: 3:00 AM).

**Sứ mệnh:** Xóa mềm/cứng các record "Tranh mã ảo" ở bảng `coupon_claims` hoặc đẩy vào kho lạnh, tránh làm phình DB sau các đợt Siêu Sale.
*   **Hoạt động:**
    *   Áp dụng Domain-Driven Sweeping: Chỉ xóa những record thuộc về campaign đã kết thúc (Ended) + Hết hiệu lực quá 30 ngày.
    *   Không chạy lưu trữ dài hạn (Cold Storage) để tiết kiệm chi phí do đây chỉ là vé tranh lượt.
*   **Chi tiết:** Xem [Coupon Claims Deletion Strategy](coupon_claims_deletion_strategy.md).

### 3.2. Idempotency Key Cleanup
**Loại hình:** Cronjob chạy hàng ngày (VD: 2:00 AM).

**Sứ mệnh:** Dọn rác bảng Log giao dịch chống lặp (Idempotency).
*   **Hoạt động:**
    *   Câu lệnh SQL dọn sạch `promotion_event_idempotency` đối với các event đã processed quá 14 ngày (cover thời gian trả hàng).
    *   `DELETE FROM promotion_event_idempotency WHERE processed_at < NOW() - INTERVAL '14 days';`

### 3.3. Outbox Garbage Collection
**Loại hình:** Cronjob chạy hàng ngày.

**Sứ mệnh:** Dọn rác bảng Outbox.
*   **Hoạt động:**
    *   Xóa các sự kiện đã bắn thành công (`PUBLISHED`) hoặc thất bại vĩnh viễn (`DEAD`) dồn ứ trên 7 ngày để giải phóng không gian ổ cứng cho PostgreSQL Outbox:
    *   `DELETE FROM promotion_outbox WHERE status IN ('PUBLISHED', 'DEAD') AND created_at < NOW() - INTERVAL '7 days';`
    *   *(Đã bao gồm trong thiết kế [Outbox Pattern](promotion_outbox_pattern.md#44-dọn-rác-outbox-garbage-collection)).*

### 3.4. Markdown Expiry Scanner (CronJob)
**Loại hình:** CronJob chạy mỗi 1 phút.

**Sứ mệnh:** Quét các `markdown_rules` đã hết hạn → phát event [`MARKDOWN_ENDED`](promotion_events.md#23-markdown_ended) (reason: `EXPIRED`) để Product Service reset giá `promotional_price`. Đây là trigger duy nhất cho event `MARKDOWN_ENDED` khi rule hết hạn — Product Service **KHÔNG** tự quét markdown expiry.

**Chi tiết luồng hoạt động:**
1. **Quét DB:**
   ```sql
   SELECT id, campaign_id, scope_type, ref_id
   FROM markdown_rules
   WHERE status = 'ACTIVE' AND end_time <= NOW()
   LIMIT 100 FOR UPDATE SKIP LOCKED;
   ```
2. **Với mỗi rule tìm được** (trong cùng 1 PostgreSQL transaction):
   - `UPDATE markdown_rules SET status = 'ENDED' WHERE id = :rule_id`
   - `INSERT INTO promotion_outbox (event_type, payload) VALUES ('MARKDOWN_ENDED', '{"rule_id": :rule_id, "scope_type": :scope_type, "ref_id": :ref_id, "reason": "EXPIRED"}')`
3. **[Outbox Relay Worker](promotion_outbox_pattern.md)** forward event lên Kafka `t.promotion.markdown.events`.
4. **Product Service [Worker #5](../product/product_workers.md#5-markdown-price-calculator-worker)** nhận `MARKDOWN_ENDED` → reset `promotional_price = NULL` cho SKUs liên quan.

*   **Idempotency:** Status check `WHERE status = 'ACTIVE'` đảm bảo rule chỉ được xử lý 1 lần. `FOR UPDATE SKIP LOCKED` cho phép multi-instance chạy song song an toàn.

### 3.5. Dead Letter Handler
**Loại hình:** CronJob mỗi 5 phút.

**Sứ mệnh:** Theo dõi tình trạng sức khoẻ của hệ thống Event-driven.
*   **Hoạt động:**
    *   Quét bảng `promotion_outbox` lấy ra các event có `status = 'DEAD'` (vượt quá `max_retries` publish thất bại).
    *   Bắn cảnh báo tới SysAdmin qua Slack/PagerDuty để xử lý bằng tay (như reset lại status thành PENDING để thử lại).
