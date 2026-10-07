# Frontend Sitemap

Bản đồ route của ứng dụng Next.js trong `frontend/`, gồm storefront, tài khoản người
mua và trang quản trị `/admin`. Mỗi route ghi rõ API nguồn theo `contracts/`, quyền
truy cập, bước xây dựng trong [lộ trình](./implementation-plan.md#1-lộ-trình-tổng-thể)
và hiện trạng code.

Chat Service và Analytics Service nằm ngoài phạm vi; không có route nào cho chúng.

## Quy ước

**Quyền truy cập**

| Ký hiệu | Nghĩa |
|---|---|
| Công khai | Không cần đăng nhập; gateway không chạy ForwardAuth |
| Đăng nhập | Cần access token hợp lệ; client chặn bằng `RequireAuth` |
| `ADMIN` | Cần role `ADMIN` trong claim `roles`; client chặn bằng `RequireRole` |

Hàng rào ở client chỉ phục vụ trải nghiệm. Quyền thật do gateway và từng service kiểm
tra ([gateway/design.md](../gateway/design.md#xác-thực-và-phân-quyền)).

**Hiện trạng**

| Giá trị | Nghĩa |
|---|---|
| có | Đã có và gọi đúng contract |
| sửa | Đã có màn hình nhưng gọi API lệch contract hoặc API legacy |
| mới | Chưa có |
| chờ API | Chưa làm được vì contract chưa có endpoint cần thiết (xem [§5](#5-khoảng-trống-api-ảnh-hưởng-tới-route)) |

**Chiến lược render** theo [ADR-002](./decisions/ADR-002-rendering-and-auth-boundary.md):
route công khai render phía server (ISR/SSR) để có SEO; route cần đăng nhập và toàn bộ
`/admin` render phía client.

## 1. Storefront công khai

Layout: `app/(site)/layout.tsx` (header + footer).

| Route | Màn hình | API chính | Quyền | Bước | Hiện trạng |
|---|---|---|---|---|---|
| `/` | Trang chủ: danh mục nổi bật, sản phẩm mới, sản phẩm đang khuyến mãi | `GET /api/v2/search/products` (`sort=newest`, `has_promotion=true`), `GET /api/v2/search/categories` | Công khai | 4, 13 | có — Search v2 render phía server |
| `/search?q=` | Kết quả tìm kiếm, lọc giá/còn hàng/khuyến mãi, sắp xếp | `GET /api/v2/search/products`; ô tìm kiếm dùng `GET /api/v2/search/suggestions` | Công khai | 4 | có |
| `/c/[slug]` | Trang danh mục với facet lọc thuộc tính | `GET /api/v2/search/products?category={slug}`, `GET /api/v2/search/categories/{category_id}/facets` | Công khai | 4 | có |
| `/products/[slug]` | Chi tiết sản phẩm: gallery, chọn biến thể, giá, tình trạng hàng, tóm tắt đánh giá, thêm vào giỏ | `GET /api/v1/storefront/products/{slug}` (Product Detail BFF) | Công khai | 4 | có — PDP BFF, UNKNOWN degrade độc lập |
| `/products/[slug]/reviews` | Toàn bộ đánh giá đã duyệt, lọc theo sao/SKU/có ảnh | `GET /api/v1/reviews/products/{product_id}`, `GET …/aggregate` | Công khai | 4 | có |

## 2. Xác thực

Layout: `app/(auth)/layout.tsx` (hai cột, không header/footer).

| Route | Màn hình | API chính | Quyền | Bước | Hiện trạng |
|---|---|---|---|---|---|
| `/login` | Đăng nhập | `POST /api/v1/auth/sessions` | Công khai | — | có |
| `/register` | Đăng ký | `POST /api/v1/auth/registrations` | Công khai | — | có |
| `/verify-email` | Nhập OTP xác minh email, gửi lại OTP | `POST /api/v1/auth/email-verifications`, `POST …/email-verification-requests` | Công khai | — | có |
| `/forgot-password` | Yêu cầu link đặt lại mật khẩu | `POST /api/v1/auth/password-reset-requests` | Công khai | — | có |
| `/reset-password` | Đặt mật khẩu mới bằng token trong link | `POST /api/v1/auth/password-resets` | Công khai | — | có |

## 3. Người mua đã đăng nhập

Layout: `app/(site)/(protected)/layout.tsx` (bọc `RequireAuth`).

### 3.1. Mua hàng

| Route | Màn hình | API chính | Quyền | Bước | Hiện trạng |
|---|---|---|---|---|---|
| `/cart` | Giỏ hàng: đổi số lượng theo delta, xoá đơn/nhiều dòng, cảnh báo đổi giá, xử lý giá chưa xác minh | `GET /api/v1/carts/mine`, `POST/DELETE /api/v1/carts/mine/items` | Đăng nhập | 5 | có — protected, Cart v1/Money, idempotency retry, cache chung theo account; nút sang checkout ở bước 6 |
| `/checkout` | Chọn địa chỉ giao, chọn voucher, xem báo giá, đặt hàng | `POST /api/v1/checkout/preview` (tải trang gọi không kèm `previous_checkout_token`; `409 SUBMISSION_IN_PROGRESS` → màn trạng thái), `POST …/submit`, `GET …/submissions/{checkout_token}`; `GET /api/v1/users/me/addresses`; voucher: `GET /api/v1/checkout/previews/{checkout_token}/voucher-options`, `PUT /api/v1/me/promotion-vouchers/{id}` (body rỗng), `POST /api/v1/me/promotion-voucher-claims`; xử lý lỗi theo `action` của `CheckoutProblem` | Đăng nhập | 6, 11 | có — checkout không voucher, token recovery/polling/Stripe; voucher bước 11 và live còn chờ |
| `/orders/[orderId]/payment` | Thanh toán bằng Stripe Payment Element cho đơn từ checkout và thanh toán lại | `GET /api/v1/orders/{id}/payment-attempts/{n}`, `POST /api/v1/orders/{id}/payment-attempts` | Đăng nhập | 6, 7 | có — nối submission usable của bước 6; chờ Stripe Sandbox E2E |
| `/orders/[orderId]/payment/complete` | Trang `return_url` sau khi Stripe chuyển hướng (3DS); poll Order vì webhook là nguồn cuối | `GET /api/v1/orders/{id}` (polling) | Đăng nhập | 6, 7 | có |

### 3.2. Đơn hàng và sau mua

| Route | Màn hình | API chính | Quyền | Bước | Hiện trạng |
|---|---|---|---|---|---|
| `/orders?tab=` | Đơn hàng của tôi theo tab `TO_PAY`, `TO_SHIP`, `TO_RECEIVE`, `COMPLETED`, `CANCELLED`, `RETURN_REFUND`; tìm kiếm | `GET /api/v1/orders` | Đăng nhập | 7 | có — cursor, tab và tìm kiếm đồng bộ URL |
| `/orders/[orderId]` | Chi tiết đơn: dòng hàng theo kho, trạng thái giao từng dòng, thanh toán, huỷ đơn chưa thanh toán, theo dõi hoàn tiền | `GET /api/v1/orders/{id}`, `POST …/cancellation-requests`, `GET …/refund-case`, `GET /api/v1/order-operations/{id}` | Đăng nhập | 7 | có |
| `/orders/[orderId]/return` | Tạo yêu cầu trả hàng: chọn dòng (đủ số lượng), lý do; chỉ được gửi một lần | `POST /api/v1/orders/{id}/return-requests` | Đăng nhập | 7 | có |
| `/reviews?tab=` | Sản phẩm chờ đánh giá (`pending`, mặc định) và đánh giá đã viết (`written`) | `GET /api/v1/reviews/me/pending`, `GET /api/v1/reviews/me` | Đăng nhập | 9 | có — tên/ảnh sản phẩm lấy từ snapshot của Review ([ADR-012](../review/decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md)) |
| `/reviews/new?eligibility=` | Viết đánh giá, tải ảnh/video | `POST /api/v1/reviews/media/upload-urls`, `PUT` thẳng storage, `POST /api/v1/reviews`, `GET /api/v1/reviews/me/eligibilities/{id}` (đọc phục hồi) | Đăng nhập | 9 | có — không idempotency key, phục hồi theo eligibility |
| `/reviews/[reviewId]` | Xem đánh giá của mình (kể cả khi bị ẩn/gỡ) và sửa đúng một lần khi còn `APPROVED` | `GET /api/v1/reviews/me/{id}`, `PATCH /api/v1/reviews/{id}` | Đăng nhập | 9 | có — PATCH chỉ gửi trường đổi; media là thay toàn bộ |
| `/notifications?filter=unread` | Hộp thông báo: đánh dấu đã đọc/chưa đọc, ẩn, đọc tất cả; mở thông báo chỉ theo link cùng origin | `GET /api/v1/notifications/inbox`, `PATCH/DELETE …/inbox/{id}`, `POST …/mark-all-read` | Đăng nhập | 9 | có |
| `/vouchers` | Ví voucher của tôi | — | Đăng nhập | — | chờ API |

Nút báo cáo đánh giá (`POST /api/v1/reviews/{id}/reports`) nằm ngay trong danh sách đánh
giá ở PDP, không có route riêng. Chuông thông báo trên header dùng
`GET /api/v1/notifications/inbox/unread-count` (poll 45 giây). Chi tiết đơn `COMPLETED`
có nút "Đánh giá sản phẩm" ở từng dòng còn entitlement (khớp `order_id` của
`GET /api/v1/reviews/me/pending`). Màn đánh giá không có link về trang sản phẩm vì Order
không giữ `slug` trong snapshot.

### 3.3. Tài khoản

Layout: `app/(site)/(protected)/profile/layout.tsx` (sidebar cài đặt).

| Route | Màn hình | API chính | Quyền | Bước | Hiện trạng |
|---|---|---|---|---|---|
| `/profile` | Hồ sơ cá nhân | `GET/PATCH /api/v1/users/me/profile` | Đăng nhập | — | có |
| `/profile/addresses` | Sổ địa chỉ | `/api/v1/users/me/addresses/**` | Đăng nhập | — | có |
| `/profile/preferences` | Tuỳ chọn thông báo | `GET/PUT /api/v1/users/me/preferences` | Đăng nhập | — | có |
| `/profile/security` | Đổi mật khẩu, đổi email (OTP), phiên đăng nhập đang hoạt động, xoá tài khoản | `PUT /api/v1/auth/password`, `POST …/email-change-requests`, `POST …/email-changes/confirmations`, `GET/DELETE /api/v1/auth/sessions[/{id}]`, `POST …/accounts/me/deletion-requests` | Đăng nhập | 0 | có — đã kiểm chứng với Auth thật qua `gateway-auth` |

## 4. Quản trị `/admin`

Layout: `app/admin/layout.tsx` (bọc `RequireRole role="ADMIN"`, admin shell riêng, không
dùng header/footer storefront) theo [ADR-001](./decisions/ADR-001-admin-in-storefront-app.md).
Mọi route dưới đây yêu cầu `ADMIN`.

### 4.1. Chung

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin` | Tổng quan: lối tắt tới các việc thường làm | — (không có API thống kê; Analytics ngoài phạm vi) | 1 | mới |

### 4.2. Catalog

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin/products` | Danh sách sản phẩm, lọc theo danh mục và trạng thái | `GET /api/v1/products` | 1 (bản chỉ đọc), 2 | có |
| `/admin/products/new` | Tạo sản phẩm DRAFT cùng ít nhất một SKU | `POST /api/v1/products` | 2 | có |
| `/admin/products/[productId]` | Sửa sản phẩm theo tab: thông tin, SKU, media, trạng thái (`DRAFT`/`ACTIVE`/`HIDDEN`), xoá | `GET/PUT/DELETE /api/v1/products/{id}`, `PATCH …/status`, `/skus/**`, `/media/**` (đều cần `If-Match`) | 2 | có |
| `/admin/categories` | Cây danh mục | `GET/POST /api/v1/categories` | 2 | có |
| `/admin/categories/[categoryId]` | Sửa danh mục, gán thuộc tính (bắt buộc / là trục biến thể) | `GET/PUT/DELETE /api/v1/categories/{id}`, `/attributes/**` | 2 | có |
| `/admin/attributes` | Danh sách thuộc tính | `GET/POST /api/v1/attributes` | 2 | có |
| `/admin/attributes/[attributeId]` | Sửa thuộc tính và option của thuộc tính kiểu SELECT | `GET/PUT/DELETE /api/v1/attributes/{id}`, `/options/**` | 2 | có |

### 4.3. Kho

Bản tối thiểu ở bước 3 là màn **nhập lệnh** và **tra cứu theo mã**. Từ
[Inventory ADR-024](../inventory/decisions/ADR-024-admin-surface-and-error-catalog.md) contract
đã có các operational query phân trang (kho, stock position, movement, dispatch/inspection
queue, quarantine item); màn danh sách dùng chúng thuộc bước 12. Kho có `name` hiển thị và
sửa kho phải gửi `expected_revision` ([Inventory ADR-027](../inventory/decisions/ADR-027-pre-implementation-contract-corrections.md)).

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin/inventory` | Trang tra cứu: nhập mã kho, mã SKU, mã movement, mã lệnh | — | 3 | có — đã kiểm chứng live |
| `/admin/inventory/warehouses/new` | Tạo kho | `POST /api/v1/admin/inventory/warehouses` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/warehouses/[warehouseId]` | Xem và sửa kho (tên, địa chỉ, nhận phân bổ mới, ưu tiên) | `GET/PATCH /api/v1/admin/inventory/warehouses/{id}` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/stock/[warehouseId]/[skuId]` | Toàn bộ bucket tồn của một SKU tại một kho | `GET /api/v1/admin/inventory/stock-positions/{wh}/{sku}` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/receipts/new` | Nhập hàng | `POST /api/v1/admin/inventory/stock-receipts` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/adjustments/new` | Điều chỉnh tồn có lý do và bằng chứng | `POST /api/v1/admin/inventory/stock-adjustments` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/movements/[movementId]` | Bằng chứng movement, tạo reversal | `GET /api/v1/admin/inventory/stock-movements/{id}`, `POST …/reversals` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/commands/[commandId]` | Kết quả của một lệnh admin | `GET /api/v1/admin/inventory/commands/{id}` | 3 | có — đã kiểm chứng live |
| `/admin/inventory/reservations/[orderId]` | Phân bổ của một đơn theo kho; ghi nhận xuất kho từng dòng | `GET /api/v1/admin/inventory/reservations/{order_id}`, `POST /api/v1/admin/inventory/physical-dispatches` | 8 | có — chờ kiểm chứng live |
| `/admin/inventory/refund-scopes/[refundScopeId]` | Hàng trả: nhận hàng vào cách ly, kiểm định PASS/FAIL, xử lý hàng cách ly | `GET /api/v1/admin/inventory/refund-scopes/{id}`, `POST …/return-receipts`, `…/inspections`, `…/quarantine-dispositions[/{id}/reversals]` | 12 | mới |

### 4.4. Đơn hàng

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin/orders` | Tìm đơn theo tab, người mua, thời gian, trạng thái, số tiền | `GET /api/v1/admin/orders` | 8 | có — chờ kiểm chứng live |
| `/admin/orders/[orderId]` | Chi tiết đơn; huỷ đơn chưa thanh toán; hoàn tiền tự động trước xuất kho; liên kết tới phân bổ kho | `GET /api/v1/admin/orders/{id}`, `POST …/cancellation-requests`, `POST …/pre-dispatch-refund-requests`, `GET /api/v1/admin/order-operations/{id}` | 8 | có — chờ kiểm chứng live |

### 4.5. Khuyến mãi

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin/promotions` | Danh sách campaign theo trạng thái xuất bản và phase | `GET /api/v1/admin/promotion-campaigns` | 10 | có — đã kiểm chứng live |
| `/admin/promotions/new` | Tạo campaign DRAFT | `POST /api/v1/admin/promotion-campaigns` | 10 | có — đã kiểm chứng live |
| `/admin/promotions/[campaignId]` | Campaign theo tab: thông tin và lịch, voucher, markdown; publish/pause/cancel; nhân bản | `GET/PATCH …/promotion-campaigns/{id}`, `POST …/transitions`, `POST …/copies` | 10 | có — đã kiểm chứng live |
| `/admin/promotions/[campaignId]/vouchers/new` | Tạo voucher: mức giảm, đơn tối thiểu, điều kiện (Rule AST), thời hạn, ngân sách | `POST …/promotion-campaigns/{id}/promotions` | 10 | có — đã kiểm chứng live |
| `/admin/promotions/[campaignId]/vouchers/[promotionId]` | Sửa voucher khi campaign còn DRAFT; cấp voucher trực tiếp cho người dùng | `GET/PATCH /api/v1/admin/promotions/{id}`, `POST …/grants` | 10 | có — đã kiểm chứng live |
| `/admin/promotions/[campaignId]/markdowns/new` | Tạo markdown giảm giá theo sản phẩm | `POST …/promotion-campaigns/{id}/markdown-rules` | 10 | có — đã kiểm chứng live |
| `/admin/promotions/[campaignId]/markdowns/[ruleId]` | Sửa markdown | `GET/PATCH /api/v1/admin/markdown-rules/{id}` | 10 | có — đã kiểm chứng live |

API get-by-id của voucher và markdown không lồng dưới campaign; route lồng chỉ để có
breadcrumb, `campaign_id` lấy từ chính resource.

### 4.6. Đánh giá

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin/reviews?status=` | Hàng đợi kiểm duyệt `REPORTED` / `HIDDEN_BY_REPORT`; panel bên cạnh để gỡ báo cáo, phản hồi chính thức, takedown | `GET /api/v1/admin/reviews`, `POST …/report-dismissals`, `PUT …/reply`, `POST …/takedown` | 12 | mới |

Không có API đọc một review cho admin, nên chi tiết review mở trong panel từ dòng danh
sách, không có route riêng.

### 4.7. Hệ thống

| Route | Màn hình | API chính | Bước | Hiện trạng |
|---|---|---|---|---|
| `/admin/system/search-reindex` | Chạy full reindex Search và theo dõi tiến độ | `POST /api/v2/search/reindex-runs`, `GET …/{run_id}` | 12 | mới |
| `/admin/system/account-deletion` | Xoá tài khoản theo `account_id` (không thể hoàn tác) | `POST /api/v1/auth/admin/accounts/{id}/deletion-requests` | 12 | mới |

### 4.8. Không có route

Các năng lực sau chỉ gọi được bên trong service mesh bằng workload identity, không có
route qua gateway, nên trình duyệt không gọi được:

- Vận hành Payment: review case, replay webhook, reconciliation, freeze, ledger, dispute
  (`contracts/http/payment/internal/v1`).
- Vận hành Notification: pause/resume lane, redrive, suppression, replay mode
  (`contracts/http/notification/internal/v1`).
- Đối soát tài chính của Order (`contracts/http/order/internal/v1`, principal
  `finance-ops`/`accounting-ops`).

Muốn có giao diện cho chúng thì cần một backend riêng cho ops; việc đó chưa nằm trong
lộ trình.

## 5. Khoảng trống API ảnh hưởng tới route

| Khoảng trống | Route bị ảnh hưởng | Cần gì |
|---|---|---|
| Không có API liệt kê voucher trong ví của người dùng | `/vouchers` | Endpoint đọc wallet không cần checkout context |
| User build lỗi thiếu checksum `golang.org/x/crypto`; Cart/Promotion cùng publish host 8085 | `/checkout` và live bước 6 | Đồng bộ module graph User và cổng Compose; bằng chứng tái hiện ở [step-6-verification](./step-6-verification.md) |
| Payment README module còn nói chưa có runtime, trong khi code/Gate 3 đã hiện thực | `/checkout`, Stripe | Đồng bộ README; Sandbox E2E cần Stripe test keys và webhook thật, chưa có bằng chứng live |
| MFA cho `ADMIN` là production gate; Auth chưa có MFA | Toàn bộ `/admin/inventory/**` (production) | ADR Auth/gateway phát hành và chuyển tiếp MFA assertion; dev/local chạy với role `ADMIN` |
| Product không có API tải media lên, chỉ nhận `storage_key` | `/admin/products/[productId]` (tab media) | Endpoint cấp signed upload URL, tương tự Review |
| Tạo Product không idempotent nhưng API admin không có tra cứu theo slug như mô tả retry của contract | `/admin/products/new` | Thêm lookup theo slug hoặc idempotency store; trước khi có, UI không tự retry lỗi mơ hồ và yêu cầu admin kiểm tra danh sách |
| Không có API liệt kê người dùng cho admin | Không có route "Người dùng" | Endpoint tìm account ở Auth |
| Review/Order snapshot không có `slug` sản phẩm | `/reviews`, `/reviews/[reviewId]` (không link về PDP) | Thêm `product_slug` vào snapshot Checkout → Order → `ORDER_COMPLETED` → Review nếu muốn có link ([ADR-012](../review/decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md) §4) |
| Router customer Promotion (`promotion-me`, `promotion-discovery`) đã chuyển sang `authenticated@file` (07/10/2026) | voucher của người mua, `/vouchers`, checkout | Trước đó dùng identity cố định. `promotion-admin` đã chuyển ở bước 10; Cart/Review đã chuyển ở bước 4; Review public list/aggregate tách router anonymous |
| Không có API tìm người dùng và API liệt kê voucher đã cấp | `/admin/promotions/[campaignId]/vouchers/[promotionId]` (cấp voucher) | Endpoint tìm account (Auth) và endpoint đọc wallet theo promotion; trước khi có, admin dán UUID và UI chỉ hiện kết quả lần cấp vừa rồi |
| Contract Rule AST không liệt kê allowlist `field` và không có cách diễn đạt “không thêm điều kiện” | Rule builder của voucher | Allowlist v1 (`cart.total_amount`, `cart.items.category_id`, `cart.payment_method`, `cart.checkout_channel`, `user.id`, `user.tier`) nằm ở `lib/promotion/rule.ts`; “mọi đơn hàng” được gửi là `cart.total_amount ≥ 0`. Nên thêm `enum` field và/hoặc node `ALWAYS` ở `rule_schema_version` kế tiếp |

## 6. Điều hướng

**Header storefront**: logo, ô tìm kiếm (gợi ý từ Search v2), mega menu danh mục, chuông
thông báo (khi đăng nhập), giỏ hàng, menu tài khoản.

**Menu tài khoản** (khi đăng nhập): Đơn hàng của tôi, Đánh giá của tôi, Thông báo, Tài
khoản, *Trang quản trị* (chỉ hiện khi có role `ADMIN`), Đăng xuất.

**Sidebar tài khoản** (`/profile/**`): Hồ sơ cá nhân, Sổ địa chỉ, Tuỳ chọn thông báo, Bảo
mật.

**Sidebar admin**, mục chỉ hiện khi bước tương ứng đã xong:

```text
Tổng quan
Catalog      Sản phẩm · Danh mục · Thuộc tính
Kho          Tra cứu · Nhập hàng · Điều chỉnh tồn · Tạo kho
Đơn hàng
Khuyến mãi   Campaign
Đánh giá     Kiểm duyệt
Hệ thống     Reindex Search · Xoá tài khoản
```

## 7. Trang hệ thống

| File | Vai trò |
|---|---|
| `app/not-found.tsx` | 404 chung |
| `app/error.tsx`, `app/global-error.tsx` | Lỗi render không bắt được, có nút thử lại |
| `app/admin/not-found.tsx` | 404 trong admin shell |
| Trạng thái 403 của `RequireRole` | Người đã đăng nhập nhưng không có `ADMIN` |
