# Frontend Step 9 Verification — Đánh giá sau mua và hộp thông báo

Ngày cập nhật: **06/10/2026**.

## Phạm vi đã triển khai

| Route / thành phần | Chức năng |
|---|---|
| `/reviews` | Hai tab: **Chờ đánh giá** (`GET /reviews/me/pending`, sắp hết hạn trước) và **Đã đánh giá** (`GET /reviews/me`, mới nhất trước, gồm cả review bị ẩn/gỡ). Tải thêm theo cursor; đủ skeleton/rỗng/lỗi. Tab nằm trên URL (`?tab=written`). |
| `/reviews/new?eligibility=` | Form viết đánh giá: 1–5 sao (bắt buộc), nhận xét ≤ 5000 ký tự, tối đa 9 ảnh/video. Đọc `GET /reviews/me/eligibilities/{id}` trước: `CLAIMED` dẫn tới review đã viết, `EXPIRED` báo hết hạn, `403/404` là lỗi dứt khoát không có nút thử lại. |
| `/reviews/[reviewId]` | Xem review của chính mình (trạng thái `APPROVED`/`HIDDEN_BY_REPORT`/`REMOVED`, phản hồi của cửa hàng) và **sửa đúng một lần**: chỉ gửi trường đã đổi, hộp thoại xác nhận vì không sửa lại được. |
| `/notifications` | Hộp thư: tab Tất cả/Chưa đọc (`?filter=unread`), mở thông báo (đánh dấu đã đọc rồi điều hướng), đổi đọc/chưa đọc, ẩn khỏi hộp thư, đánh dấu tất cả đã đọc, tải thêm. |
| Chuông trên header | Huy hiệu số chưa đọc từ `GET /notifications/inbox/unread-count`, poll 45 giây, dừng khi tab ẩn; lỗi không làm hỏng header. |
| Menu tài khoản | Thêm "Đánh giá của tôi" và "Thông báo". |
| `/orders/[orderId]` | Đơn `COMPLETED`: dòng hàng còn entitlement của **đúng đơn đó** (khớp `order_id` + `product_id` + `sku_id`) có nút "Đánh giá sản phẩm", kèm banner tóm tắt. |

## Quy tắc quan trọng đã áp dụng

- **Không có Idempotency-Key cho Review.** `X-Request-Id` chỉ để truy vết nên create/edit
  không được gửi lại mù quáng. Khi kết quả không rõ (mất phản hồi, 5xx) UI đọc phục hồi
  theo contract: create đọc eligibility (`CLAIMED` + `review_id` ⇒ thành công), edit đọc
  review (`edit_count ≥ 1` ⇒ lượt sửa đã commit). Nếu vẫn `PENDING`/`edit_count = 0` thì
  hiện lỗi gốc để người dùng quyết định.
- **Upload media ba bước:** `POST /reviews/media/upload-urls` → `PUT` thẳng vào storage
  bằng chỉ dẫn đã ký (XHR để có tiến độ, đúng header, không đổi URL, không gửi token ứng
  dụng) → gửi `asset_id`. Tệp tải ngay khi chọn; asset hết hạn gắn (`expires_at`, mặc định
  15 phút) bị đánh lỗi ở client trước khi gửi thay vì nhận `MEDIA_NOT_VERIFIED`. Lỗi
  `429 MEDIA_QUOTA_EXCEEDED` đọc `Retry-After`.
- **Sửa review theo ngữ nghĩa PATCH của contract:** trường vắng mặt giữ nguyên;
  `content: null` xoá nội dung; `media_asset_ids` là **thay toàn bộ** (mảng rỗng = gỡ hết,
  không giữ lại một phần) nên UI tách "giữ nguyên tệp hiện tại" và "thay hoặc gỡ".
- **Không lộ chi tiết nội bộ:** review bị ẩn chỉ nói "đang bị ẩn để xem xét", không có số
  báo cáo, lý do pháp lý hay media; mọi lỗi đi qua bảng `code` → tiếng Việt.
- **Deep link của thông báo** chỉ được theo khi cùng origin với trang đang mở
  (`lib/notification-link.ts`); link khác origin, `//host`, `javascript:` không bao giờ
  thành nút điều hướng.
- Cache theo `accountId`, đổi tài khoản không để lại dữ liệu cũ; tạo/sửa review
  invalidate danh sách chờ, danh sách của tôi và danh sách review công khai.

## Backend đã bổ sung cho bước này

Thiếu ba thứ nên đã sửa backend và đồng bộ tài liệu theo quyết định của chủ dự án
(06/10/2026), chi tiết ở [Review ADR-012](../review/decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md):

| Thiếu | Đã làm |
|---|---|
| `PendingEligibility`/`OwnerReview` không có tên/ảnh sản phẩm | Order đưa `product_name`, `product_image_url` (snapshot dòng đơn) vào `ORDER_COMPLETED.items[]`; Review lưu snapshot (migration `V2`) và trả thêm `product_name`, `product_image_url` ở `OwnerReview`, cùng `order_id` ở `PendingEligibility`. |
| Không có API liệt kê review của tôi | Review thêm `GET /api/v1/reviews/me` (`listMyReviews`, cursor, mới nhất trước, gồm review không còn công khai, không có signed media/report/lý do pháp lý). |
| Link thông báo `REVIEW_REPLIED` là `/products/{product_id}?review=…` nhưng PDP theo slug | Notification lắp `review_url = {base}/reviews/{review_id}`. |

Tài liệu và contract đã đồng bộ: `contracts/http/review/v1/openapi.yaml`,
`contracts/events/order/v1` (schema + README), `contracts/events/notification/v1/templates/catalog.yaml`,
`contracts/catalog/interactions.yaml`, `contracts/CHANGELOG.md`; `docs/review/*` (README,
data, interfaces, workflows, development, ADR-012, ADR-007 ghi chú, schema `0002`),
`docs/order/{data,interfaces}.md`, `docs/notification/{07-templates,10-interfaces,decisions}.md`.
Type frontend được sinh lại bằng `npm run gen:api`.

## Bằng chứng

| Gate | Kết quả |
|---|---|
| TypeScript | `tsc --noEmit` qua |
| Unit/component tests frontend | 26 file, 180 test qua (+66 so với bước 8): API/phục hồi sau timeout, uploader media, link thông báo, `/reviews`, `/reviews/new`, `/reviews/[id]` (kể cả chỉ gửi trường đã đổi), hộp thư, chuông, nút đánh giá trên chi tiết đơn |
| ESLint | 0 lỗi; 3 cảnh báo có sẵn ngoài code bước 9 |
| Production build | Next.js build qua; có `/reviews`, `/reviews/new`, `/reviews/[reviewId]`, `/notifications` |
| Terra | Không còn màu cứng/shadow nặng/bo góc tự chế (grep checklist); control tối thiểu 44 px; lỗi form có `role="alert"` + `aria-invalid`/`aria-describedby`; radio group sao điều khiển được bằng bàn phím |
| Review service | 45 test đơn vị và toàn bộ IT (Testcontainers Postgres/Kafka/MinIO, `-Dit.test=!OutboxCdcIT`) qua; `OutboxCdcIT` cần Debezium nên không chạy lần này. Thêm test snapshot sản phẩm, `GET /reviews/me` và schema `V2` đồng bộ tài liệu |
| Order service | `CheckoutFlowIT` qua, validate payload `order-completed` mới với JSON Schema |
| Notification service | Test handler/catalog/template Go qua |

### Kiểm chứng live local (06/10/2026)

Stack: Auth + gateway-auth + api-gateway + Postgres/Kafka/Redis + **Review (build lại, Flyway
áp `V2` lên DB có sẵn)** + MinIO + **Notification (+ Mailpit, LocalStack)**.

| Luồng | Kết quả |
|---|---|
| `ORDER_COMPLETED` (có tên/ảnh) → Review | Entitlement lưu đúng snapshot (tiếng Việt nguyên vẹn); `GET /reviews/me/pending` qua gateway trả `order_id`, `product_name`, `product_image_url`. |
| Viết đánh giá có ảnh | Upload `PUT` thẳng vào MinIO (CORS preflight `204` + `PUT 200`), `POST /reviews` `201`, trang chi tiết hiện ảnh bằng signed URL. |
| Sửa đúng một lần | Chỉ gửi `rating` + `content`; ảnh giữ nguyên (`has_media=true`, `edit_count=1`); sau đó không còn nút sửa. |
| Admin reply → Notification | `PUT /admin/reviews/{id}/reply` ghi outbox; chuyển message sang `t.review.events` (thay Debezium) → inbox có `REVIEW_REPLIED` với `deep_link = http://localhost:3000/reviews/{id}`; bấm thông báo trên UI mở đúng trang review và hiện phản hồi. |
| `ORDER_COMPLETED` notification | Inbox hiện "đã giao thành công" kèm link `/orders/{id}?review=1`. |
| Chuông / đọc / đọc tất cả | Huy hiệu đếm đúng, mở thông báo chuyển đã đọc, `POST mark-all-read` `204`, tab "Chưa đọc" rỗng đúng. |
| Responsive / giao diện | 375 px chế độ sáng: không tràn ngang trên `/reviews`, `/reviews/new`; desktop chế độ tối kiểm tra trực quan cả bốn màn. |

## Giới hạn còn lại

- **Chưa có đơn hàng thật đi hết checkout** (bước 6 chưa làm), nên entitlement trong lần
  kiểm chứng live được tạo bằng cách phát `ORDER_COMPLETED` lên `t.order.events` đúng
  contract thay vì từ một đơn thật; nút "Đánh giá sản phẩm" trên chi tiết đơn được kiểm
  chứng bằng test component chứ chưa bấm trên một đơn live.
- **Review outbox → Kafka cần Debezium.** Local không có connector nên message
  `REVIEW_REPLY_NOTIFICATION_REQUESTED` được chuyển tay; production dùng CDC như thiết kế.
- Notification chạy bằng profile `notification` và phải `notifctl migrate up` lần đầu
  (DB `notification_db` có thể chưa tồn tại trên volume Postgres cũ).
- Không có link về trang sản phẩm từ màn đánh giá: Order không giữ `slug` trong snapshot
  ([ADR-012](../review/decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md) §4).
- Chưa kiểm chứng screen reader, E2E Playwright (bước 13) và upload video lớn.
- Phase 1 của Notification không có realtime: huy hiệu cập nhật theo chu kỳ poll.
