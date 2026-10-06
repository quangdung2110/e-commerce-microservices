# Bước 10 — Admin Khuyến mãi: phạm vi và kiểm chứng

Ngày 06/10/2026. Kế hoạch tổng thể ở
[implementation-plan.md §12](./implementation-plan.md#12-bước-10--admin-khuyến-mãi); route ở
[sitemap §4.5](./sitemap.md#45-khuyến-mãi). Contract nguồn:
[`contracts/http/promotion/v1/openapi.yaml`](../../contracts/http/promotion/v1/openapi.yaml)
(nhóm admin), nghiệp vụ ở [promotion/domain.md](../promotion/domain.md).

## 1. Phạm vi đã làm

| Route | Chức năng |
|---|---|
| `/admin/promotions` | Danh sách campaign; lọc `publication_status` và `phase` trên URL; cursor trong state; bảng từ `md`, card dưới `md` |
| `/admin/promotions/new` | Tạo campaign `DRAFT` (mã tự chuẩn hoá thành chữ HOA) |
| `/admin/promotions/[campaignId]` | Bốn tab đồng bộ `?tab=`: thông tin và lịch, voucher, giảm giá sản phẩm, vòng đời |
| `…/vouchers/new`, `…/vouchers/[promotionId]` | Tạo/sửa voucher; xem mức sử dụng; cấp voucher trực tiếp |
| `…/markdowns/new`, `…/markdowns/[ruleId]` | Tạo/sửa giảm giá theo sản phẩm |

Mã nguồn: `src/lib/api/promotion.ts` (client), `src/lib/promotion/` (Rule AST, mức giảm, hạn
dùng, giới hạn, dựng payload và merge-patch), `src/lib/labels/promotion.ts`,
`src/hooks/use-admin-promotions.ts`, `src/components/admin/promotions/`.

## 2. Quy tắc dữ liệu

- **`If-Match` là revision của campaign cha.** Mọi `POST`/`PATCH` trên voucher và markdown tăng
  revision của campaign, nên sau mỗi lệnh thành công mọi ETag đang cache đều cũ; client làm mới
  toàn bộ cache `admin/promotions`. `GET` voucher/markdown trả ETag của campaign cha và được dùng
  trực tiếp cho lệnh sửa.
- **`412` không ghi đè.** Hiện cảnh báo “Campaign vừa được người khác thay đổi” kèm nút tải
  lại; dữ liệu đang nhập vẫn nằm trên form cho tới khi bấm tải lại (form sửa khi đó về dữ liệu
  mới để không vô tình hoàn tác thay đổi của người khác).
- **Merge-patch chỉ gồm trường đã đổi.** Backend thay thế chứ không trộn từng khoá của object
  lồng (`discount`, `validity`, `budget`, `conditions_rule`), nên các khối này luôn gửi nguyên.
  Xoá mô tả gửi `null`. Đổi rule gửi kèm `rule_schema_version`.
- **Campaign đã xuất bản chỉ đọc.** Mọi form hiển thị bị khoá kèm giải thích; đường duy nhất để
  đổi là nhân bản. Nhân bản cần mã mới cho **đúng mọi** voucher của nguồn (contract), UI đọc hết
  các trang voucher (100 mục/trang) để dựng ô nhập.
- **Lệnh tạo không idempotent** (campaign, voucher, markdown): trùng mã bị `409`, nên UI không tự
  gửi lại khi mất kết nối và nhắc admin kiểm tra danh sách trước khi tạo lại.
- **Cấp voucher** dùng natural key `(user_id, promotion_id)` nên gửi lại an toàn: `201` khi tạo
  mới, `200` khi ví đã có (không kéo dài hạn). UI phân biệt hai trường hợp bằng status.
- **Tiền** là VND nguyên (`{amount_minor, currency: "VND"}`); **phần trăm** nhập tối đa hai chữ
  số thập phân và đổi sang basis point bằng `Math.round`, không bao giờ gửi số thực.

## 3. Rule AST

Builder (`rule-builder.tsx`) sinh đúng cây `LOGICAL`/`CONDITION` của contract và kiểm tra giống
`RuleValidator` của backend (độ sâu ≤ 8, ≤ 200 nút, ≤ 50 con, ≤ 100 giá trị, ≤ 255 ký tự, giá trị
không trùng, phép so sánh hợp với field). Backend vẫn là nơi quyết định cuối cùng.

| Field | Phép so sánh | Giá trị |
|---|---|---|
| `cart.total_amount` | `≥ > ≤ < = ≠` | số VND nguyên |
| `cart.items.category_id` | `IN`, `NOT_IN` | mảng ID danh mục (chuỗi) chọn từ Product; `category_ids` của Checkout là ancestry nên chọn danh mục cha cũng khớp sản phẩm con |
| `cart.payment_method` | `=`, `≠` | `STRIPE` |
| `cart.checkout_channel` | `=`, `≠` | chuỗi (Checkout gửi `CHECKOUT`) |
| `user.id` | `IN`, `NOT_IN`, `=`, `≠` | UUID |
| `user.tier` | — | không cho thêm mới: chưa có nguồn hạng nên điều kiện luôn không đạt; rule cũ có vẫn hiển thị |

Hai điểm contract chưa nói rõ (đã ghi ở [sitemap §5](./sitemap.md#5-khoảng-trống-api-ảnh-hưởng-tới-route)):

1. `field` chỉ ghi “thuộc allowlist” mà không liệt kê, nên danh sách nằm ở `lib/promotion/rule.ts`.
2. Không có cách nói “không thêm điều kiện”: gốc phải là một node và nhóm rỗng luôn `false`. Lựa
   chọn “Áp dụng cho mọi đơn hàng” gửi `cart.total_amount ≥ 0` (tổng tiền không bao giờ âm nên luôn
   đúng); rule khi đọc về khớp đúng dạng này thì bật lại lựa chọn đó.

## 4. Backend được sửa cho bước này

Chỉ cấu hình gateway, đã được bạn duyệt trước khi sửa:

- `docker-compose.yml`: router `promotion-admin` đổi middleware từ
  `strip-identity-headers,dev-admin-auth` sang `authenticated`. Router customer giữ `dev-auth`
  tới bước 11.
- Đồng bộ [gateway/runtime-contract.md](../gateway/runtime-contract.md),
  [gateway/implementation_plan.md §3.6](../gateway/implementation_plan.md),
  [sitemap §5](./sitemap.md), [implementation-plan §13](./implementation-plan.md).

Không đổi code Promotion Service hay contract OpenAPI.

## 5. Kiểm chứng

| Kiểm tra | Kết quả |
|---|---|
| `tsc --noEmit` | Qua (sau khi dừng dev server; lỗi khi chạy song song nằm ở `.next/dev/types` do dev server ghi đồng thời) |
| `eslint` | 0 lỗi, 3 cảnh báo cũ ngoài Promotion |
| `vitest` | 209 test qua (thêm 23 test logic thuần, 6 test component, cập nhật test điều hướng) |
| `next build` | Qua; bảy route `/admin/promotions/**` được sinh |
| Gateway `promotion-admin` | Ẩn danh `401`, `USER` `403`, `ADMIN` `200`; `X-User-Roles`/`X-User-Id` do client tự gửi bị bỏ (`401`) |
| Danh tính | `created_by`/`updated_by` là UUID tài khoản admin đăng nhập, không còn ID cố định của `dev-admin-auth` |
| Tạo campaign | Qua UI; mã nhập thường được chuẩn hoá thành `QA-SALE10` |
| Voucher | Tạo qua UI với Rule AST (`cart.total_amount ≥ 200000` AND danh mục) → payload và AST lưu đúng; sửa phần trăm bằng merge-patch chỉ gồm trường đổi |
| Xung đột ETag | Sửa campaign từ nơi khác rồi lưu từ UI → `412`, hiện cảnh báo, giữ giá trị đang nhập; tải lại đưa form về dữ liệu mới |
| Markdown | Dán UUID sản phẩm (Search tắt → picker báo lỗi và gợi ý dán mã), tên sản phẩm được tra từ Product; tạo trùng sản phẩm → `409 MARKDOWN_RULE_ALREADY_EXISTS` |
| Xuất bản | Campaign hiện “Đã xuất bản / Đang diễn ra”; voucher và campaign chuyển thành chỉ đọc |
| Cấp voucher | UUID sai bị chặn ở client; cấp thật `201`, ví `SAVED` hạn +7 ngày; cấp lại `200` cùng ví, không kéo dài hạn |
| Nhân bản | Validate rỗng; nhân bản kèm mã mới cho hai voucher → campaign nháp mới có đủ voucher và markdown |
| Tạm dừng → huỷ | Publish (Sắp diễn ra) → Tạm dừng → Huỷ; nút xác nhận huỷ khoá tới khi gõ đúng mã campaign |
| Responsive | 375px (light): không tràn ngang ở danh sách (card) và form voucher; pane desktop ~800px (dark): sidebar và form đúng |

## 6. Giới hạn đã biết

- Visual QA chưa đủ ma trận 375/768/1280 px × light/dark, chưa kiểm tra bằng screen reader.
- Picker sản phẩm dùng Search v2 nên chỉ thấy sản phẩm `ACTIVE` đã lập chỉ mục; sản phẩm khác
  dán UUID. Search không chạy trong lần kiểm chứng này nên nhánh có kết quả chỉ được kiểm bằng test.
- Cấp voucher phải dán UUID người dùng và không có danh sách người đã nhận (thiếu API).
- Không có API xoá campaign/voucher nên dữ liệu QA (campaign `QA-SALE10`, bản sao `QA-SALE-11`)
  còn lại trong database local `promotion_service`.
- Database `promotion_service` không tự có trên volume Postgres tạo trước khi Promotion được thêm
  vào `init-db.sh`; chạy `docker exec postgres psql -U postgres -c "CREATE DATABASE promotion_service;"`
  rồi khởi động lại `promotion-service`.
- Luồng người mua (lưu, nhận, dùng voucher tại checkout) thuộc bước 11.
