# Promotion Service - Database & Cache Schema Design

**Liên kết nhanh (Related Documents):**
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Rule Engine Design](rule_engine_design.md)
*   [Rule Engine Demo (Java)](RuleEngineDemo.java)
*   [Kafka Events Schema](promotion_events.md)
*   [Outbox Pattern](promotion_outbox_pattern.md)
*   [Coupon Claims Deletion Strategy](coupon_claims_deletion_strategy.md)

---

Tài liệu này mô tả chi tiết thiết kế cơ sở dữ liệu (PostgreSQL) và cấu trúc bộ nhớ đệm (Redis) cho Promotion/Discount Service, giải quyết các bài toán về đồng thời, hiệu năng, Rule Engine đa hình và Idempotency.
---
## 1. PostgreSQL Schema (Dữ liệu Lõi)
Sử dụng PostgreSQL để tận dụng kiểu dữ liệu `JSONB` cho Rule Engine và đảm bảo tính toàn vẹn dữ liệu (ACID).

### 1.1. `campaigns` (Chiến dịch Khuyến mãi)
Nhóm các chương trình khuyến mãi nhỏ lẻ vào một chiến dịch lớn (VD: "Mega Sale 11/11").
*   `id`: BIGSERIAL PRIMARY KEY
*   `name`: VARCHAR(255) (VD: "Siêu Sale Tháng 11")
*   `start_time`: TIMESTAMPTZ (Luôn lưu chuẩn UTC)
*   `end_time`: TIMESTAMPTZ
*   `status`: VARCHAR(50) (UPCOMING, ACTIVE, ENDED, SUSPENDED)
*   `created_at`, `updated_at`

### 1.2. `promotions` (Mã giảm giá Khuyến mãi - Cart Vouchers)
Lưu trữ thông tin cốt lõi, loại khuyến mãi, và Rule Engine bằng `JSONB`. Mã nhập vào lúc giỏ hàng/thanh toán.
*   `id`: BIGSERIAL PRIMARY KEY
*   `campaign_id`: BIGINT NULL (Foreign Key -> `campaigns.id`. Có thể NULL do platform tự tạo voucher lẻ ngoài chiến dịch).
*   `deleted_owner_type`: Xác định mã do Platform tạo hay hệ thống. Với mô hình không có Seller, chỉ còn `PLATFORM` (Admin tạo voucher toàn nền tảng).
*   `code`: VARCHAR(50) (VD: "HAPPYBDAY", "HUNT1111").
*   `type`: VARCHAR(50) - Phân loại theo cấu trúc Vận hành kinh doanh cốt lõi:
    *   **`UNLIMITED_ALL`**: Mã do sàn tạo ra có thể gửi tới toàn user (VD: Chúc mừng sinh nhật, Chào mừng new user). Không cần quản lý tồn kho ngặt nghèo.
    *   **`LIMITED_FLASH`**: Mã "Săn" do Sàn tung ra, có giới hạn ngân sách (VD: Flash Sale toàn sàn mã giảm 500k chỉ có 100 lượt). User phải tranh giành.
    *   **`PLATFORM_VOUCHER`**: Mã do Platform (Admin) tự tạo cho riêng nền tảng mình, có ngân sách giới hạn.
*   `discount_type`: VARCHAR(50) - (PERCENTAGE, FIXED_AMOUNT)
    > Với mô hình không có Seller, Admin chỉ tạo voucher thuộc loại `discount_type` là `PERCENTAGE` hoặc `FIXED_AMOUNT`.
*   `discount_value`: DECIMAL(10,2) (Giá trị giảm, vd: 10%, hoặc 50000đ)
*   `max_discount_amount`: DECIMAL(10,2) (Giảm tối đa bao nhiêu, áp dụng cho loại PERCENTAGE)
*   `min_purchase_amount`: DECIMAL(10,2) (Điều kiện giá trị đơn hàng tối thiểu để được áp dụng mã. Đưa ra ngoài JSONB để truy vấn nhanh trên giao diện "Gợi ý mã khuyến mãi").
  > [!IMPORTANT]
  > **Quy tắc Source of Truth bắt buộc:** API chỉ nhận 1 giá trị `min_purchase_amount` chuẩn hoá. Backend sẽ tự động đồng bộ xuống cả cột riêng và node tương ứng trong `conditions_rule`.
  >
  > Không chấp nhận payload gửi 2 giá trị mâu thuẫn. Nếu phát hiện mismatch giữa cột và JSON rule ở luồng update/migration, request phải bị reject để tránh drift logic giữa Preview và Submit.
*   `conditions_rule`: **JSONB** (Cây Abstract Syntax Tree chứa điều kiện áp dụng - Phân tích chi tiết tại [Rule Engine Design](rule_engine_design.md)).
*   `priority`: INT (Độ ưu tiên giải quyết xung đột mã, số cao áp dụng trước).
*   `start_time`, `end_time`: TIMESTAMPTZ
  *   **Claim Window (thời gian tranh/lưu mã):** Dùng để quyết định còn được claim/save mã mới hay không.
  *   **Không phải Use Window:** Voucher đã sở hữu trong ví không hết hạn theo cặp này; hạn dùng khi redeem lấy từ `user_coupon_wallets.expired_at`.
*   `status`: VARCHAR(50) (ACTIVE, INACTIVE)

### 1.3. `promotion_budgets` (Ngân sách & Tồn kho)
Tách bảng ngân sách ra khỏi bảng `promotions` để tránh Row Lock Contention (nghẽn cổ chai) khi nhiều user cùng lấy mã làm cho các update khác trên promotion bị treo.
*   `promotion_id`: BIGINT PRIMARY KEY (Foreign Key -> `promotions.id`)
*   `total_quantity`: INT (Tổng lượt sử dụng cho phép)
*   `used_quantity`: INT (Số lượt đã được REDEEMED / Áp dụng thành công)
*   `total_budget`: DECIMAL(15,2) (Tổng ngân sách tiền tệ. VD: Cho phép giảm tối đa 500 triệu)
*   `used_budget`: DECIMAL(15,2) (Số tiền đã xài)
*   **Naming Mapping bắt buộc:** Redis dùng cặp tên `total_amount / used_amount / reserved_amount`; PostgreSQL dùng `total_budget / used_budget`. Mapping này là 1-1 theo nghiệp vụ tiền tệ, không được trộn lẫn khi implement (`amount` ở Redis tương đương `budget` ở DB).
*   **Source-of-Truth cho Reconciliation:** `promotion_budgets` giữ cấu hình tổng (`total_*`) và aggregate bền vững (`used_*`) để truy vấn nhanh; `reserved_*` không tồn tại trong DB này mà chỉ nằm ở Redis reservation runtime.
*   **Nguyên tắc chiều đồng bộ bắt buộc:** Khi chạy Redis-DB reconciliation, chỉ được sửa Redis theo hướng DB-first (`PostgreSQL -> Redis`), không ghi ngược `promotion_budgets` từ counters Redis.
*   **Chống Race Condition:** Nhờ có tầng Redis Cache kết hợp Lua Script làm "Bảo vệ cửa" (Gatekeeper) tuyệt đối cho cả Số lượng và Tiền tệ (Budget), ở tầng CSDL ta không cần các câu lệnh locking phức tạp hay `@Version` nữa. Khi Worker lấy event thanh toán thành công từ Kafka, nó chỉ cần chạy câu lệnh đơn giản: `UPDATE promotion_budgets SET used_quantity = used_quantity + 1, used_budget = used_budget + :discountAmount WHERE promotion_id = :id;`

### 1.4. `coupon_claims` (Lịch sử Tranh mã - Đổi tên từ `unique_coupons`)
Theo cách phân loại mới, mã `LIMITED_FLASH` là mã chung nhưng số lượng ít. Khi Admin xuất bản mã loại này với `total_quantity = 1000`, hệ thống sẽ batch-insert (tạo sẵn) 1.000 dòng dữ liệu vào bảng này với `status = AVAILABLE` và `owner_id = NULL`. Khi 10.000 user cùng "Tranh" (Claim) mã này, ta sẽ dùng cơ chế DB-Lock nâng cao để cấp suất (slot) này cho user.
*   `id`: BIGSERIAL PRIMARY KEY (Để có thể insert ban đầu khi chưa ai giành mã)
*   `promotion_id`: BIGINT (Foreign Key -> `promotions.id`)
*   `owner_id`: BIGINT (Mặc định `NULL`, Điền `user_id` khi có người giành được)
*   `status`: VARCHAR(50) (AVAILABLE, CLAIMED)
*   `claimed_at`: TIMESTAMPTZ NULL (Ghi mốc claim thành công, phục vụ audit và sweep đối soát)
*   **State Invariant bắt buộc:**
    * `status = 'AVAILABLE'` -> `owner_id IS NULL` và `claimed_at IS NULL`
    * `status = 'CLAIMED'` -> `owner_id IS NOT NULL` và `claimed_at IS NOT NULL`
    * Gợi ý DB CHECK:
    ```sql
    ALTER TABLE coupon_claims
    ADD CONSTRAINT chk_coupon_claims_state_owner
    CHECK (
      (status = 'AVAILABLE' AND owner_id IS NULL AND claimed_at IS NULL)
      OR
      (status = 'CLAIMED' AND owner_id IS NOT NULL AND claimed_at IS NOT NULL)
    );
    ```
*   **Lưu ý nhất quán trạng thái:** `PENDING_CLAIM` chỉ tồn tại ở Redis (`pending_claims:data/index`) để chống crash window, KHÔNG phải trạng thái DB của `coupon_claims`.
*   **Partial Unique Index (`promotion_id`, `owner_id`)**: Do ban đầu 1.000 dòng insert vào có `owner_id` là NULL, ta không thể dùng (promotion_id, owner_id) làm Primary Key. Thay vào đó, ta sử dụng: `CREATE UNIQUE INDEX foo ON coupon_claims(promotion_id, owner_id) WHERE owner_id IS NOT NULL;`. Cách này đảm bảo tuyệt đối ở tầng DB rằng mỗi user chỉ được quyền claim 1 suất.
*   **Chiến lược Update (Lịch sử: `SELECT FOR UPDATE SKIP LOCKED`):** 
    * *Thiết kế cũ:* Thuật toán cấp mã siêu hạng: `WITH cte AS (SELECT id FROM coupon_claims WHERE promotion_id = :promo_id AND status = 'AVAILABLE' LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE coupon_claims SET owner_id = :user_id, status = 'CLAIMED' FROM cte WHERE coupon_claims.id = cte.id;`
    * *Thiết kế Hiện tại (Đã tối ưu):* Tuy nhiên, do hiện tại ở tầng Cache Redis chúng ta đã áp dụng hàng đợi nguyên tử **SPOP Claiming** để bốc `claim_id` trực tiếp từ RAM, nên cơ chế tranh giành race-condition đã được chặn đứng hoàn toàn ở ngoài cửa. Worker backend nhận được `claim_id` duy nhất từ Redis chỉ việc chạy câu lệnh update có guard: `UPDATE coupon_claims SET owner_id = :user_id, status = 'CLAIMED', claimed_at = NOW() WHERE id = :claim_id AND status = 'AVAILABLE' AND owner_id IS NULL`. Không cần dùng tới khoá `SKIP LOCKED` ở tầng DB nữa, nhưng đây vẫn là một giải pháp dự phòng CSDL tuyệt vời nếu sau này tắt Redis.
*   **Chiến lược vòng đời dữ liệu:** Bảng này áp dụng [Hard Deletion theo Domain-Driven Sweeping](coupon_claims_deletion_strategy.md) thay vì Cold Data Archiving.
    

### 1.5. `user_coupon_wallets` (Ví Voucher của User)
Bảng này quản lý trạng thái voucher theo từng cặp `(user_id, promotion_id)`. Mỗi user có thể sở hữu nhiều voucher PLATFORM, nhưng mỗi `promotion_id` chỉ có tối đa 1 dòng trong ví cho user đó.
*   `id`: BIGSERIAL PRIMARY KEY
*   `user_id`: `UUID` (Index, canonical chuẩn hóa toàn hệ thống; xem [Service Contract Matrix §0.1](../_contracts/CONTRACT_MATRIX.md#01-id-convention))
*   `promotion_id`: BIGINT (Foreign Key -> `promotions.id`)
*   `status`: VARCHAR(50) (SAVED, USED, EXPIRED)
*   `saved_at`: TIMESTAMPTZ (Mốc user sở hữu mã; đóng vai trò `acquired_at` theo policy hiện tại)
*   `used_at`: TIMESTAMPTZ (Cập nhật sau khi thanh toán thành công)
*   `expired_at`: TIMESTAMPTZ (Hạn dùng theo ví; mặc định gán `saved_at + INTERVAL '30 days'` tại thời điểm acquire/save)
*   **Tách bạch 2 cửa sổ thời gian (bắt buộc):**
  *   Claim window lấy từ `promotions.start_time/end_time`.
  *   Use window lấy từ `user_coupon_wallets.expired_at`.
*   **Unique Constraint (`user_id`, `promotion_id`)**: Đảm bảo dữ liệu trong ví luôn duy nhất theo từng promotion. Không áp dụng quy tắc "toàn ví chỉ 1 mã".
*   **Save-and-Apply semantics (bắt buộc):**
  * Nếu row đã `SAVED` và còn hạn: trả thành công idempotent, không gia hạn thêm `expired_at`.
  * Nếu row `EXPIRED`: cho phép re-save bằng `ON CONFLICT DO UPDATE` để chuyển lại `SAVED`, reset `saved_at/expired_at` theo policy hiện hành.
  * Nếu row `USED`: mặc định không re-save (trừ policy promotion cho phép reacquire rõ ràng).
*   **Composite Index (`user_id`, `status`)**: Tối ưu truy vấn "Xem danh sách mã SAVED đang khả dụng trong ví của user" — API `GET /wallet?status=SAVED` sẽ chạy Index Scan chính xác thay vì quét toàn bộ ví.

### 1.6. `coupon_usages` (Sổ cái Phân tán - Ledger & Audit Trail)
Lưu lại Dấu vết quá trình áp dụng mã vào Đơn hàng. Đây là bảng quan trọng nhất để đối soát dòng tiền và hoàn mã.
*   `id`: BIGSERIAL PRIMARY KEY
*   `user_id`: `UUID` **(Index)** — Canonical chuẩn hóa toàn hệ thống; phục vụ truy vấn "Lịch sử voucher của tôi" (`GET /users/{id}/coupon-history`). Xem [Service Contract Matrix §0.1](../_contracts/CONTRACT_MATRIX.md#01-id-convention).
*   `promotion_id`: BIGINT
*   `order_id`: BIGINT (Index)
*   `discount_amount`: DECIMAL(10,2) (Số tiền thực tế đã giảm)
*   **`platform_funding`**: DECIMAL(10,2) (Số tiền Platform chịu toàn bộ. Với mô hình không có Seller, 100% chi phí voucher do Platform chịu)
*   **`admin_funding`**: DECIMAL(10,2) (Số tiền platform chịu. VD: Mã giảm 50k, platform chịu 30k)
*   `status`: VARCHAR(50) (REDEEMED, PARTIALLY_REFUNDED, REFUNDED)
    * `REDEEMED`: Đơn hàng thanh toán thành công, ngân sách đã xác nhận trừ.
    * `PARTIALLY_REFUNDED`: Đơn đã có hoàn tiền một phần, vẫn còn phần voucher đã sử dụng.
    * `REFUNDED`: Đơn hàng đã giao thành công nhưng sau đó khách **đổi trả hàng hoàn tiền toàn phần** — hoàn ngược toàn bộ ngân sách đã Redeemed.
    * (Lưu ý: Không lưu trạng thái `RESERVED` hay `CANCELLED` ở bảng này vì hành động giữ chỗ (Reserve/Cancel) chỉ thao tác thuần túy trên Redis để tối ưu tốc độ, không ghi DB ledger.)
*   `reserved_at`: TIMESTAMPTZ (Timestamp tham chiếu lấy từ reservation Redis tại thời điểm commit REDEEMED; có thể NULL nếu không truy hồi được)
*   `redeemed_at`: TIMESTAMPTZ (Lúc nhận event Order Success)
*   `refunded_at`: TIMESTAMPTZ (Lúc nhận event Order Refunded — sau delivery)
*   `refunded_amount`: DECIMAL(10,2) DEFAULT 0 (Tổng số tiền voucher đã hoàn lũy kế)
*   `refund_count`: INT DEFAULT 0 (Số lần refund đã áp dụng cho bản ghi)
*   **Funding Invariant bắt buộc:** `platform_funding >= 0`, `admin_funding >= 0`, và `platform_funding + admin_funding = discount_amount`.
*   **Refund Invariant bắt buộc:** `0 <= refunded_amount <= discount_amount`, `refund_count >= 0`, và `status` phải khớp trạng thái hoàn (`REDEEMED` khi `refunded_amount = 0`; `PARTIALLY_REFUNDED` khi `0 < refunded_amount < discount_amount`; `REFUNDED` khi `refunded_amount = discount_amount`).
*   **DB CHECK gợi ý (khuyến nghị triển khai):**
  ```sql
  ALTER TABLE coupon_usages
  ADD CONSTRAINT chk_coupon_refund_bounds
  CHECK (refunded_amount >= 0 AND refunded_amount <= discount_amount AND refund_count >= 0);
  ```
*   **Unique Constraint (`order_id`, `promotion_id`):** Đảm bảo không áp dụng trùng 1 mã cho 1 đơn trong ledger chính. Refund flow sẽ UPDATE bản ghi hiện có, không INSERT thêm bản ghi mới cho cùng cặp này.
*   **Vai trò Source-of-Truth bắt buộc:** Với worker reconciliation, `coupon_usages` (kèm gate idempotency) là nguồn chuẩn để rebuild `used_qty/used_amount`; không lấy Redis `used_*` làm chuẩn để ghi ngược DB.

### 1.7. `promotion_event_idempotency` (Chống lặp Message Kafka)
Đảm bảo khi Order Service gửi Event hoàn mã, nếu Kafka delivery 2 lần, ta không bị cộng dồn ngân sách 2 lần. Bảng này hoạt động như chốt chặn cuối cùng bảo vệ DB, kết hợp cùng lệnh `SETNX` của Redis ở vòng ngoài.
*   `event_id`: VARCHAR(255) PRIMARY KEY
  * **Contract nguồn dữ liệu bắt buộc:** Promotion Service **không tự sinh** `event_id`.
  * Luồng sync qua Temporal (`/confirm`, `/cancel`, `/cancel-confirmed`, `/refund`): nhận `idempotency_key/request_id` từ Order và ghi thẳng vào `event_id`.
  * Luồng async qua Kafka (`ORDER_PAID/CANCELLED/REFUNDED`): lấy đúng `event_id` từ Kafka envelope và ghi thẳng vào `event_id`.
*   `order_id`: BIGINT **(Index)** — Hỗ trợ truy vấn đối soát "Tìm tất cả action đã xử lý cho đơn hàng X".
*   `promotion_id`: BIGINT **(Index)** — Bắt buộc để phân biệt 1 đơn áp nhiều voucher PLATFORM.
*   `action_type`: VARCHAR(50) 
    *   `REDEEM_COUPON`: Đánh dấu đơn hàng thành công, trừ lượng Budget đã Reservation.
    *   `CANCEL_COUPON`: Đơn hàng bị huỷ, nhả lại Budget đã khóa về Available.
    *   `REFUND_COUPON`: Trả hàng hoàn tiền, hoàn lại số lượng mã/ngân sách.
*   `action_scope_key`: VARCHAR(100) (Khóa phạm vi idempotent theo action; ví dụ `FINAL`, `refund_txn_id`, hoặc `refund_batch_id`).
*   `payload_hash`: VARCHAR(128) NULL (Checksum payload để điều tra replay sai nội dung)
*   `processed_at`: TIMESTAMPTZ
*   **Unique Constraint (`order_id`, `promotion_id`, `action_type`, `action_scope_key`)**: Đảm bảo không xử lý lặp lại cùng một thao tác trong cùng phạm vi nghiệp vụ.
  * REDEEM/CANCEL dùng `action_scope_key = 'FINAL'`.
  * REFUND partial dùng `action_scope_key = refund_transaction_id` (hoặc `refund_batch_id`) để vừa chống duplicate, vừa cho phép nhiều lần refund hợp lệ.
*   **Format idempotency key chuẩn hóa từ Order (khuyến nghị bắt buộc):**
  * `REDEEM_FINAL:{order_id}:{promotion_id}`
  * `CANCEL_FINAL:{order_id}:{promotion_id}`
  * `REFUND_FULL:{order_id}:{promotion_id}:{refund_request_id}`
  * `REFUND_PARTIAL:{order_id}:{promotion_id}:{refund_request_id}`
*   **Vai trò trong Reconciliation:** `REDEEM_COUPON + action_scope_key='FINAL'` là gate bắt buộc để loại reservation đã chốt khỏi tập `reserved_*` runtime khi đối soát Redis.
*   **Retention bắt buộc:** Thời gian giữ dữ liệu idempotency phải >= `refund_window + 15 ngày` (khuyến nghị 45 ngày) để không mở lại cửa duplicate khi replay trễ.

### 1.8. `promotion_outbox` (Outbox Pattern — Reliable Event Publishing)
Đảm bảo mọi sự kiện Kafka được phát đi đáng tin cậy sau khi ghi DB thành công, tránh mất Event khi Worker crash giữa chừng. Chi tiết thiết kế xem tại [Outbox Pattern](promotion_outbox_pattern.md).
*   `event_id`: UUID PRIMARY KEY
*   `event_type`: VARCHAR(100) (VD: `MARKDOWN_CREATED`, `COUPON_REDEEMED` — xem [Events Schema](promotion_events.md))
*   `schema_version`: INT DEFAULT 1 (đồng bộ với envelope top-level trong [promotion_events.md](promotion_events.md))
*   `aggregate_type`: VARCHAR(50) (VD: `Promotion`, `MarkdownRule`, `CouponUsage`)
*   `aggregate_id`: VARCHAR(255) (ID của entity liên quan)
*   `partition_key`: VARCHAR(255) (Khóa partition được chốt ngay lúc ghi outbox, không suy luận lại từ payload ở CDC Connector)
*   `payload`: JSONB (Nội dung Event)
*   `created_at`: TIMESTAMPTZ
*   **Nguyên tắc CDC:** Bảng outbox giữ dạng append-only, không chứa các cột polling-specific như `status/retry_count/max_retries/next_retry_at/published_at/error_message`. Retry/DLQ được quản trị tại Kafka Connect.

---
## 2. Quy tắc Áp dụng Voucher (Stacking & Sequencing)
Mô hình voucher theo chuẩn platformee, áp dụng bộ quy tắc cứng (Hard-coded) về số lượng và thứ tự khấu trừ.
### 2.1. Giới hạn Voucher theo Slot (PLATFORM only)

Một đơn hàng áp dụng theo slot:

* toàn đơn tối đa 1 `PLATFORM_VOUCHER`

Tổng số voucher có thể áp dụng là tối đa 1 (chỉ còn PLATFORM sau Phase 14).

Sơ đồ slot:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  SLOT 1: PLATFORM VOUCHER (≤ 1 mã giảm giá toàn đơn — PERCENTAGE/FIXED) │
└─────────────────────────────────────────────────────────────────────────┘
```

**Mapping chi tiết Slot:**

| Slot | Điều kiện | Giải thích |
|:---|:---|:---|
| **SLOT 1: PLATFORM Voucher** | `deleted_owner_type = 'PLATFORM'` + `discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')` | Một đơn hàng chỉ được chọn **1 Voucher duy nhất** (toàn đơn) |


### 2.2. Mapping với Order Service (`order_promotions.promotion_type`)
`promotions.type` và `order_promotions.promotion_type` là hai chiều phân loại khác nhau, không được dùng thay thế cho nhau.

| Trường | Ý nghĩa |
|:---|:---|
| `promotions.type` | Kiểu vận hành ngân sách/phát hành mã (`UNLIMITED_ALL`, `LIMITED_FLASH`, `PLATFORM_VOUCHER`) |
| `order_promotions.promotion_type` | Vai trò giảm giá trong đơn (`CART_DISCOUNT`) |

Quy tắc map bắt buộc:
* `discount_type IN (PERCENTAGE, FIXED_AMOUNT)` -> `order_promotions.promotion_type = CART_DISCOUNT`


### 2.3. Thứ tự Khấu trừ (Discount Sequencing — Waterfall)
Giá trị thanh toán cuối cùng tính theo trình tự ưu tiên (Waterfall):

1.  **Giá gốc (Original Price):** Giá hiển thị trên trang sản phẩm (đã bao gồm Markdowns/Price Slashes nếu có).
2.  **Bước 1: Áp Voucher PLATFORM:** Giảm trên tổng giá trị sản phẩm.
    *   `subtotal_after_voucher = subtotal - voucher_discount`
3.  **Tổng cộng:**
    *   `final_amount = subtotal_after_voucher`

> Với mô hình không có Seller và không có freeship (sau Phase 14), waterfall chỉ còn 1 bước đơn giản.


---
## 3. Thiết kế Giảm giá Trực tiếp (Markdowns / Price Slashes)
Khác với `promotions` (Mã Voucher áp dụng ở Giỏ hàng), **Giảm giá gạch chéo** (Markdowns) tác động trực tiếp vào giá hiển thị của sản phẩm. 

**Vấn đề (Row Explosion):** Nếu Khuyến mãi áp dụng cho "Tất cả sản phẩm Category Điện thoại", việc insert hàng triệu record map `product_id` vào `discount_items` sẽ làm sập DB và cực khó quản lý vòng đời.

**Giải pháp Kiến trúc:** Tách bảng Định nghĩa Luật (Rules) và Bảng Mappings riêng. Dùng Engine Tính toán Động.

### 3.1. `markdown_rules` (Luật Giảm giá Gạch chéo)
Định nghĩa luật giảm cơ bản. Không map trực tiếp vào sản phẩm ở DB Quan hệ (PostgreSQL).
*   `id`: BIGSERIAL PRIMARY KEY
*   `campaign_id`: BIGINT NULL (Foreign Key -> `campaigns.id`. Cho phép gom Markdown rules vào cùng chiến dịch với Vouchers. VD: "Mega Sale 11/11" bao gồm cả mã giảm giá lẫn gạch giá trực tiếp).
*   `owner_id`: BIGINT NULL
*   `deleted_owner_type`: VARCHAR(50) (PLATFORM — không còn Seller)
*   `name`: VARCHAR(255) (VD: "Điện thoại Apple giảm 10%")
*   `scope_type`: VARCHAR(50) (PRODUCT, CATEGORY)
*   `ref_id`: VARCHAR(255) NULL (Chứa ID của Product/Category tương ứng với scope, đồng nhất định dạng ID string liên service)

> [!IMPORTANT]
> **Validation Matrix bắt buộc:**
> * `scope_type IN (PRODUCT, CATEGORY)` -> `ref_id IS NOT NULL`
> * Không mở rộng thêm scope enum khi chưa cập nhật đầy đủ contract ở `promotion_events.md` và Product consumer.

> [!NOTE]
> Không có FK trực tiếp giữa `promotions` và `markdown_rules`. Hai thực thể độc lập, có thể cùng tham chiếu `campaign_id` để gom chiến dịch nhưng không ràng buộc 1-1.

> [!NOTE]
> **Design Decision (Polymorphic Association):** Cột `ref_id` là một **Polymorphic Foreign Key** — ý nghĩa của nó phụ thuộc vào giá trị `scope_type`. Khi `scope_type = PRODUCT` thì `ref_id` trỏ tới `products.id`, khi `scope_type = CATEGORY` thì trỏ tới `categories.id`. Đây là trade-off có chủ đích: ta hy sinh referential integrity ở tầng DB để đổi lấy sự đơn giản về schema (tránh tạo bảng mapping riêng cho từng scope). Tính toàn vẹn được bảo vệ ở **tầng Application Logic** (Validate `ref_id` tồn tại bằng API call tới Product Service trước khi lưu).

*   `discount_type`: VARCHAR(50) (PERCENTAGE, FIXED_AMOUNT)
*   `discount_value`: DECIMAL(10,2)
*   `max_discount_amount`: DECIMAL(10,2) NULL (Giới hạn số tiền được giảm tối đa)
*   `start_time`, `end_time`: TIMESTAMPTZ
*   `status`: VARCHAR(50) (ACTIVE, INACTIVE, ENDED) — Phân biệt Rule đang áp dụng, tạm ngưng, hoặc đã hết hạn. Được [Redis Cache](redis_caching_design.md#25-cache-giảm-giá-trực-tiếp-markdown--price-slashes) lọc để chỉ cache các Rule `ACTIVE`.

### 2.2. Xử lý "Zero-Latency" tính toán Best Deal mà không cần `discount_items` mapping
Thay vì ép DB Promotion Service phải JOIN hàng triệu dòng, chúng ta chia tách hệ thống theo mô hình CQRS nơi **Product Service là Write Model**, và **Search Service là Read Model**.

**Cơ chế Tính toán Async (Zero-Latency ở trang Danh sách Product/Catalog):**
Trang Danh sách (Search/Filter) không thể vừa query Data, vừa gọi Promotion Service để lấy rule, rồi vừa tính toán giá giảm. Nó cần đọc thẳng một con số `final_price` có sẵn. Luồng hoạt động (Event-Driven) sẽ diễn ra NGAY KHI Rule được tạo, chứ không phải lúc User đang lướt Web:
1. **[Promotion Service]** Admin Sàn tạo 1 Rule: `Giảm 15% cho toàn bộ Category Điện Thoại (ID: 10)`. Rule được lưu vào `markdown_rules`.
2. **[Promotion Service -> Kafka]** Service này ném một Event: `MARKDOWN_CREATED { rule_id: 1, scope: CATEGORY, ref_id: 10, discount: 15% }` lên Kafka. Xem chi tiết Event schema tại [promotion_events.md](promotion_events.md#21-markdown_created).
3. **[Product Service - Write Model]** Hứng Event này. Nó query Database của chính nó: `SELECT id, original_price FROM products WHERE category_id = 10` (Trích xuất toàn bộ điện thoại).
    * **Tại sao không ném thẳng qua Search Service?** Bởi vì Promotion Service chỉ biết "luật giảm 15%", nó hoàn toàn không biết Category 10 gồm những sản phẩm nào, và quan trọng nhất là không nắm giữ `original_price` (Giá gốc). **Product Service mới là Nguồn Chân Lý (Source of Truth)** về Sản phẩm và Giá. Nếu giao việc tính toán này cho Search Service (Read Model), ElasticSearch sẽ phải gánh vác các logic nhân chia cộng trừ giá tùy chỉnh, làm mất đi đặc tính "Chỉ Đọc" siêu tốc của nó.
    * Bằng một Batch Job Update, Product Service ngầm nhồi công thức tính toán ra giá chéo (ví dụ: `promotional_price = original_price * 0.85`).
    * Sau đó cập nhật hàng loạt trường `promotional_price` này thẳng vào Database của Product Service.
4. **[Product Service -> Kafka]** Khi giá sản phẩm thay đổi, Product Service ném Event: `PRODUCT_PRICE_UPDATED { product_id, sku_id, original_price, promotional_price, best_price, has_promotion, version, event_sequence_id, change_source }` (Luồng CDC - Change Data Capture).
5. **[Search Service - Read Model]** Hứng luồng thay đổi giá này, và **Update trực tiếp vào Elasticsearch (hoặc Typesense/Meilisearch)**.

**Kết quả tuyệt đối:** Khi người dùng vào Giao diện (App/Web), họ gọi request tìm kiếm. **Search Service** chỉ đơn thuần móc từ Elasticsearch ra một danh sách sản phẩm gồm `[Tên SP, Hình ảnh, Giá gốc (original_price), Giá đã giảm (promotional_price)]`. 
* Latency ở mức **dưới 10ms (Miliseconds)** vì toàn bộ cục gánh nặng "Tính toán Giảm giá" đã được đẩy về phía Backend chạy ngầm (Async) trước đó rồi!

**Cơ chế On-The-Fly (Dùng cho Detail Page):**
    * Khi user xem trang Chi tiết (1 sản phẩm). Back-end chọc Redis lấy list Active Markdowns của Sàn & platform.
    * Đưa qua thuật toán lấy giá: `P_Sàn = Giá gốc - Giảm Sàn (Category)`, `P_platform = Giá gốc - Giảm platform (SKU)`.
    * Người dùng luôn thấy "Best Deal".
---
## 3. Thiết kế Cơ sở dữ liệu Đa hình (Rule Engine JSONB cho Vouchers)
Cột `conditions_rule` trong bảng `promotions` sẽ lưu trữ một cây Abstract Syntax Tree (AST). Việc lồng ghép ngẫu nhiên này giúp cấu hình 100% linh hoạt. Chi tiết thuật toán đánh giá xem tại [Rule Engine Design](rule_engine_design.md).

**Ví dụ JSON payload `conditions_rule`:**

> [!IMPORTANT]
> JSON Schema bắt buộc phải có trường `node_type` để Jackson Deserializer (`@JsonTypeInfo`) phân biệt `LogicalNode` vs `ConditionNode`. Trường `children` (không phải `rules`) chứa danh sách các node con. Xem [RuleEngineDemo.java](RuleEngineDemo.java) để đối chiếu.

```json
{
  "node_type": "LOGICAL",
  "operator": "AND",
  "children": [
    {
      "node_type": "CONDITION",
      "operator": "GREATER_THAN_OR_EQUAL",
      "field": "cart.total_amount",
      "value": 200000
    },
    {
      "node_type": "CONDITION",
      "operator": "IN",
      "field": "user.tier",
      "value": ["GOLD", "DIAMOND"]
    },
    {
      "node_type": "CONDITION",
      "operator": "NOT_IN",
      "field": "cart.items.category_id",
      "value": [101, 104]
    },
    {
      "node_type": "LOGICAL",
      "operator": "OR",
      "children": [
        {
          "node_type": "CONDITION",
          "operator": "EQUAL",
          "field": "cart.payment_method",
          "value": "VNPAY"
        },
        {
          "node_type": "CONDITION",
          "operator": "EQUAL",
          "field": "cart.checkout_channel",
          "value": "MOBILE_APP"
        }
      ]
    }
  ]
}
```
*   **Performance:** Tại thời điểm `/calculate-discount`, payload API từ Order/Cart gửi lên có đủ thông tin giá trị `cart` và `user`. Code Java (có thể tự viết Visitor pattern hoặc xài EasyRules) sẽ loop qua JSON tree này trong `O(n)` với latency ở mức micro-second (dưới bộ nhớ RAM), hoàn toàn không phải query DB nữa.
---
## 4. Cấu trúc Redis Caching Schema (Chống sập DB & Tối ưu hiệu năng)
Chi tiết kiến trúc Multi-level Caching (L1/L2) và các mã giả Lua Script cho giao dịch nguyên tử (Atomic Transactions) đã được tách riêng ra file thiết kế chuyên sâu: **[redis_caching_design.md](redis_caching_design.md)**.

Bao gồm các luồng:
1. **L1/L2 Metadata Cache chống Thundering Herd**: Nạp cây AST Rule Engine lên RAM cục bộ cực nhanh, sử dụng cơ chế `refreshAfterWrite` của Caffeine.
2. **Ngân sách & Tiền tệ (`HMGET/HINCRBY`)**: LUA Script tối ưu hoá Atomically `Reserve/Redeem/Refund` cho cả Số lượng và Quy mô Tiền của mã giảm giá Checkout. Bảng Postgres trở nên "vô can" với race condition.
3. **Thuật toán SPOP Tranh Mã O(1)**: Hàng đợi tranh vé rút thăm may mắn Flash Sale chống Deadlock. Có trang bị bộ bảo vệ `pending_claims` Job quét rác chống mất dòng dữ liệu nếu rớt mạng Kafka.
4. **Rate Limiting & Token Bucket BF**: Giới hạn số lượng Request tránh Bot, và siêu tối ưu kiểm duyệt Unique Codes bằng Bloom Filter.
5. **Idempotency Kép (Saga Guard)**: Redis khoá `SETNX` cho Message Kafka kết hợp Unique Constraint ở bảng `promotion_event_idempotency`.

---
Thiết kế này kết hợp hài hoà:
- Trọng tâm độ đúng đắn và tiền bạc (ACID, Ledger, Idempotent) nằm ở **Postgres**.
- Trọng tâm chống tắc nghẽn, kiểm tra số lượng và luồng concurrency 100.000 user đẩy lên **Redis + Lua Script**.
- Trọng tâm đánh giá điều kiện phức tạp nằm ở **In-memory Code Engine (JSONB AST)**.