# Frontend Implementation Plan

Kế hoạch xây dựng giao diện người mua và giao diện quản trị trong `frontend/`. Tài liệu
gồm lộ trình tổng thể và các bước 0–5, 7–10. Bước 3 đã hoàn thành code, kiểm thử offline và
kiểm chứng live local với Inventory Service qua gateway; MFA vẫn là gate production.

Route và API của từng màn hình nằm ở [sitemap.md](./sitemap.md). Quy tắc giao diện nằm
trong skill Terra (`.claude/skills/terra-ui/`).

## 1. Lộ trình tổng thể

Làm theo lát cắt dọc cho tới khi mua được một đơn hàng thật từ đầu đến cuối. Màn admin
nào tạo ra dữ liệu thì làm ngay trước màn người mua cần dữ liệu đó.

Trạng thái cập nhật ngày **06/10/2026**:

| Bước | Nội dung | Contract chính | Trạng thái |
|---|---|---|---|
| 0 | Nền tảng: type sinh từ OpenAPI, một HTTP client, helper cho lệnh/ETag/polling/phân trang, lỗi, định dạng, chặn role, công cụ build | Mọi `openapi.yaml` public | Hoàn thành |
| 1 | Khung admin: layout, component quản trị, quy ước dữ liệu admin | `product/v1` (danh sách chỉ đọc làm màn mẫu) | Hoàn thành |
| 2 | Admin Catalog: danh mục, thuộc tính, sản phẩm, SKU, media, trạng thái | `product/v1` | Hoàn thành |
| 3 | Admin Kho bản tối thiểu: tạo kho, nhập hàng, điều chỉnh, tra tồn | `inventory/admin/v1` | Hoàn thành |
| 4 | Duyệt sản phẩm: chuyển sang Search v2, trang danh mục, tìm kiếm, PDP qua BFF, review công khai | `search/v2`, `product-detail-bff/v1`, `review/v1` | Hoàn thành |
| 5 | Giỏ hàng khớp contract | `cart/v1` | Hoàn thành |
| 6 | Checkout và thanh toán Stripe (chưa có voucher) | `checkout/v1`, `order/v1` | Chưa hoàn thành |
| 7 | Đơn hàng của tôi: tab, chi tiết, huỷ, thanh toán lại, trả hàng, hoàn tiền | `order/v1` | Hoàn thành code/offline; chờ kiểm chứng live |
| 8 | Admin Đơn hàng và ghi nhận xuất kho | `order/v1` (admin), `inventory/admin/v1` | Hoàn thành code/offline; chờ kiểm chứng live |
| 9 | Sau mua: đánh giá kèm media, hộp thông báo | `review/v1`, `notification/v1` | Hoàn thành code/offline và live local (Review, Notification); chờ đơn hàng thật từ bước 6 |
| 10 | Admin Khuyến mãi: campaign, voucher, Rule AST, markdown, cấp voucher | `promotion/v1` (admin) | Hoàn thành code/offline và live local (Auth, gateway, Product, Promotion) |
| 11 | Voucher trong checkout: khay voucher qua Checkout, lưu/nhận voucher qua Promotion | `checkout/v1` (voucher options), `promotion/v1` (me: save, claim) | Chưa hoàn thành |
| 12 | Admin kiểm duyệt đánh giá, kho đầy đủ (hàng trả, kiểm định, cách ly), hệ thống | `review/v1` (admin), `inventory/admin/v1`, `search/v2`, `auth/v1` | Chưa hoàn thành |
| 13 | Hoàn thiện: trang chủ, SEO/ISR, trạng thái rỗng/lỗi, responsive, a11y, E2E | — | Chưa hoàn thành |

Mỗi bước đi theo cùng một vòng: phác giao diện → code theo Terra → chạy thử với backend
qua gateway → tự soát bằng checklist Terra.

## 2. Ràng buộc chung

- **Không có Node.js trên máy phát triển.** Mọi lệnh `npm` (cài package, lint,
  typecheck, build, test) chạy trong container `node:20-alpine`, cùng phiên bản với
  `frontend/Dockerfile`. Bước 0 tạo script bọc sẵn.
- **Next.js 16 có thay đổi phá vỡ.** `frontend/AGENTS.md` yêu cầu đọc
  `node_modules/next/dist/docs/` trước khi viết code; thư mục này chỉ có sau khi đã
  `npm ci` trong container.
- **Contract là nguồn chuẩn.** Khi code và `contracts/` lệch nhau thì sửa code theo
  contract; khi contract thiếu thì ghi vào [sitemap §5](./sitemap.md#5-khoảng-trống-api-ảnh-hưởng-tới-route),
  không tự bịa endpoint.
- **Một ứng dụng cho cả storefront và admin** theo
  [ADR-001](./decisions/ADR-001-admin-in-storefront-app.md).
- **Ranh giới render và xác thực** theo
  [ADR-002](./decisions/ADR-002-rendering-and-auth-boundary.md): trang công khai render
  phía server, trang cần đăng nhập và admin render phía client.

---

## 3. Bước 0 — Nền tảng

> **Trạng thái (29/09/2026): xong.** `typecheck`, `lint` (0 lỗi), `test` (38 test) và
> `build` qua trong container. `/profile/security` đã chạy với Auth thật qua gateway sau
> khi có `gateway-auth` (ForwardAuth): liệt kê phiên, thu hồi một phiên, đăng xuất tất cả
> (về `/login` kèm lời giải thích, token cũ bị `401 TOKEN_REVOKED`).

### 3.1. Mục tiêu

Mọi màn hình ở các bước sau gọi API qua cùng một lớp có type đúng contract, xử lý lỗi,
idempotency, ETag, polling và phân trang giống nhau. Không màn hình nào tự viết lại
những việc đó.

### 3.2. Hiện trạng cần xử lý

| Vấn đề | Vị trí | Xử lý ở |
|---|---|---|
| `API_BASE = "/api/v1"` cố định, trong khi Search dùng `/api/v2`; Inventory admin nay nằm dưới `/api/v1/admin/inventory` ([Inventory ADR-024](../inventory/decisions/ADR-024-admin-surface-and-error-catalog.md)) nên không cần base riêng | `src/lib/api/client.ts` | Bước 0 |
| `services/cart.ts` và `services/product.ts` dùng `axios` trần: không gắn token, không refresh, không `X-Request-Id` | `src/services/` | Bước 0 chuyển sang client chung; bước 4 và 5 sửa endpoint |
| Giỏ hàng gọi `PUT/DELETE /carts/items/{sku}` không có trong contract | `src/services/cart.ts` | Bước 5 |
| Trang chủ, danh mục và PDP gọi Search v1 đã bị gỡ khỏi backend | `src/services/product.ts`, `src/hooks/use-product-detail.ts` | Bước 4 |
| `formatDate` nhận epoch dạng chuỗi, dễ lẫn với `formatDateTime` nhận ISO | `src/lib/format.ts` | Bước 0 |
| `next.config.ts` chỉ rewrite `/api/**` | `next.config.ts` | Bước 0 thêm `/media/**`; Inventory admin API đã nằm dưới `/api/**` nên không cần rewrite riêng |
| Hàm đổi email, phiên đăng nhập, xoá tài khoản đã có nhưng chưa có UI | `src/lib/api/auth.ts` | Bước 0 (`/profile/security`) |

### 3.3. Công việc

#### 0.1. Công cụ chạy Node qua Docker

- Tạo `frontend/scripts/node.sh`: chạy một lệnh bất kỳ trong `node:20-alpine`, mount
  `frontend/` vào `/app` và `contracts/` vào `/contracts` chỉ đọc, giữ `node_modules`
  trong một named volume để không cài lại mỗi lần.
- Thêm script vào `package.json`: `typecheck` (`tsc --noEmit`), `test` (Vitest),
  `gen:api` (sinh type ở 0.2).
- Kiểm chứng: `scripts/node.sh npm ci`, `npm run lint`, `npm run typecheck`,
  `npm run build` đều chạy được.
- Đã làm thêm: `frontend/scripts/dev.sh` chạy `next dev` trong container nối vào
  mạng `ecommerce-network` (dùng webpack vì Turbopack không poll được file trên bind
  mount của Docker Desktop); `frontend/scripts/create-dev-account.sh` tạo tài khoản
  test đã xác minh trên stack local bằng cách đọc OTP từ Redis của Auth, không cần
  dựng Notification. `.claude/launch.json` khai báo `frontend-dev` cho browser pane.

#### 0.2. Sinh type TypeScript từ OpenAPI

- Dùng `openapi-typescript` (chỉ sinh type, giữ `axios` và logic refresh hiện có).
- Sinh cho các contract trình duyệt gọi tới: `auth/v1`, `user/v1`, `cart/v1`,
  `checkout/v1`, `order/v1`, `promotion/v1`, `review/v1`, `search/v2`,
  `notification/v1`, `product/v1`, `product-detail-bff/v1`, `inventory/admin/v1`.
- Đầu ra: `src/lib/api/generated/<service>.ts`, commit vào repo, có header "file sinh
  tự động, không sửa tay". Script `gen:api` đọc từ `/contracts`.
- Lỗi cú pháp YAML ở `contracts/http/auth/v1/openapi.yaml` (dòng 551 và 560, giá trị
  `description` bắt đầu bằng backtick) đã được sửa bằng cách đặt trong nháy đơn; nội
  dung không đổi. Cả 12 contract đều sinh được type.
- Mỗi service có một module `src/lib/api/<service>.ts` export hàm gọi API với type lấy
  từ file sinh. Component không import file sinh trực tiếp.

#### 0.3. Một HTTP client

- Đổi `baseURL` của `api` thành rỗng; mỗi module service tự khai báo prefix đầy đủ
  (`/api/v1/orders`, `/api/v2/search`, …).
- Gộp `services/cart.ts`, `services/product.ts`, `services/user/api.ts` vào
  `src/lib/api/`; tất cả dùng `api` để có token, refresh một lần khi 401 và
  `X-Request-Id`. Endpoint sai của cart và search giữ nguyên tới bước 4–5, chỉ đổi
  client.
- Thêm `src/lib/api/server.ts` cho Server Component: `fetch` thẳng tới `API_UPSTREAM`,
  không gắn token, nhận tuỳ chọn `revalidate`/`tags`. Chỉ dùng cho route công khai
  (Search v2, PDP BFF, review công khai).

#### 0.4. Helper cho lệnh có side effect

- **Idempotency**: `newCommandId()` sinh UUID; `useCommand()` bọc `useMutation`, sinh key
  **một lần cho mỗi ý định của người dùng** (mỗi lần mở dialog/submit form) và dùng lại
  đúng key đó khi retry. Contract dùng: cart, checkout preview, lệnh order của buyer và
  admin, Inventory admin.
- **Inventory admin** cần thêm `X-Correlation-ID` và `X-Causation-ID`; với lệnh khởi đầu
  từ admin thì cả hai bằng command ID. `useCommand` nhận tuỳ chọn bật hai header này.
- **ETag / `If-Match`**: hàm đọc lưu ETag cùng dữ liệu trong cache React Query; lệnh sửa
  tự gửi `If-Match` từ cache. `412` hiện thông báo "Dữ liệu vừa được người khác thay đổi"
  kèm nút tải lại, không tự ghi đè. Contract dùng: Product, Promotion admin.
- **Lệnh bất đồng bộ**: `pollOperation(url)` đọc `Location` và `Retry-After` của `202`,
  poll tới trạng thái cuối, dừng khi unmount hoặc quá thời hạn. Dùng cho order
  operation, checkout submission, review eligibility, reindex run, claim voucher `202`.

#### 0.5. Phân trang cursor

- `useCursorList(queryKey, fetcher)` bọc `useInfiniteQuery` theo `next_cursor`; hỗ trợ
  hai kiểu hiển thị: "Tải thêm" (storefront) và trang trước/sau bằng stack cursor
  (bảng admin).
- Tham số lọc đồng bộ với URL search params để link chia sẻ được và nút Back hoạt động.
  Lịch sử cursor của bảng admin nằm trong state (`useCursorPager`), không trên URL —
  cursor là chuỗi mờ và dài; pager tự về trang đầu khi bộ lọc (query key) đổi.

#### 0.6. Lỗi

- Tách `MESSAGES` trong `problem.ts` thành `src/lib/api/problem-messages/` theo nhóm
  service (`identity`, `catalog`, `commerce`, `engagement`, `common`), gộp lại một bảng.
  Mã của Cart, Checkout, Order lấy từ bảng lỗi miền trong `docs/<service>/domain.md`
  vì contract của chúng không liệt kê `code`. Rà mã lỗi trong từng contract để thêm câu tiếng Việt, ưu tiên mã
  của các service sắp làm (Product, Inventory admin, Search, Cart, Checkout, Order).
- Thêm xử lý chung cho `412` (ETag lệch), `409` (xung đột trạng thái), `410` (báo giá
  hết hạn), `425`/`202` (đang xử lý), `503` kèm `Retry-After`.
- Component `ErrorState`: câu tiếng Việt, nút thử lại, `trace_id` nhỏ ở cuối theo
  Terra §5.

#### 0.7. Định dạng

- Tiền: mọi số tiền trong contract là số nguyên VND (`*_vnd`, `*_minor`); `formatVND`
  nhận `number` nguyên, không chia 100.
- Thời gian: backend trả RFC 3339; hiển thị theo `Asia/Ho_Chi_Minh`, định dạng
  `dd/MM/yyyy` và `HH:mm dd/MM/yyyy` (thứ tự chuẩn của vi-VN). Ngày lịch thuần
  `YYYY-MM-DD` (ví dụ `date_of_birth`) giữ nguyên, không quy đổi múi giờ. Bỏ `formatDate` kiểu epoch sau khi đã thay hết
  chỗ dùng.
- Nhãn enum: `src/lib/labels/<domain>.ts` map enum sang tiếng Việt và tông màu badge
  (trạng thái sản phẩm, phase campaign, trạng thái đơn, trạng thái thanh toán, tab đơn,
  trạng thái review). Bước 0 tạo khung và nhãn cho Product; các bước sau thêm dần.

#### 0.8. Chặn theo role

- `RequireRole` (cạnh `RequireAuth`): đang khôi phục phiên thì hiện skeleton; chưa đăng
  nhập thì về `/login?next=`; đã đăng nhập nhưng thiếu role thì hiện trạng thái 403,
  không redirect.
- Hằng số role `ADMIN` ở một chỗ. Review, Product, Promotion, Order, Search và Auth đều
  dùng `ADMIN` (Review cấu hình mặc định `ADMIN` tại
  `java/services/review-service/src/main/resources/application.yml`).
- Menu tài khoản hiện mục "Trang quản trị" khi có role `ADMIN`.

#### 0.9. Cấu hình

- `NEXT_PUBLIC_MEDIA_BASE_URL` (mặc định `/media`) để admin dựng URL ảnh từ
  `storage_key`; rewrite `/media/**` tới gateway.
- Ghi các biến môi trường vào `.env.example`.

#### 0.10. Hoàn thiện phần tài khoản còn thiếu

- `/profile/security`: đổi mật khẩu (chuyển `ChangePasswordCard` về đây), đổi email
  bằng OTP, danh sách phiên đăng nhập và thu hồi từng phiên hoặc tất cả, vùng xoá tài
  khoản có xác nhận gõ lại.
- Thêm mục "Bảo mật" vào sidebar tài khoản.
- Đây là màn mẫu để kiểm tra các helper ở 0.3–0.7 trên API thật trước khi dùng cho
  admin.
- Theo contract Auth, đổi mật khẩu, xác nhận đổi email, thu hồi tất cả phiên và xoá tài
  khoản đều thu hồi **cả phiên hiện tại** (implementation Go cũng vậy). `authApi` tự kết
  thúc phiên local và ghi `signOutReason` vào store; `RequireAuth` đưa người dùng về
  `/login`, nơi form hiển thị lý do. `ChangePasswordCard` cũ báo sai là "chỉ đăng xuất
  thiết bị khác" và đã được sửa.
- Khi phiên kết thúc vì bất kỳ lý do nào, `AuthProvider` xoá cache React Query để dữ
  liệu người dùng cũ không ở lại cho người đăng nhập tiếp theo.

#### 0.11. Test

- Vitest cho: `useCommand` giữ nguyên key qua retry, gửi `If-Match` và xử lý `412`,
  `pollOperation` tôn trọng `Retry-After` và dừng khi unmount, map lỗi `problem.ts`,
  định dạng tiền và ngày.

### 3.4. Ngoài phạm vi bước 0

- Sửa endpoint của giỏ hàng, Search, PDP (bước 4–5).
- Playwright E2E (bước 13).
- Giao diện admin (bước 1).

### 3.5. Tiêu chí xong

| Tiêu chí | Kết quả |
|---|---|
| `lint`, `typecheck`, `test`, `build` qua trong container | Đạt. Lint 0 lỗi; code storefront cũ (danh sách `LEGACY_STOREFRONT` trong `eslint.config.mjs`) bị hạ ba rule xuống cảnh báo cho tới khi viết lại ở bước 4–5 |
| Không còn `import axios from "axios"` ngoài `src/lib/api/client.ts` | Đạt. `services/` đã gộp vào `src/lib/api/` |
| Module trong `src/lib/api/` dùng type sinh từ contract | Đạt với `auth`, `user`. `cart.ts` và `search-legacy.ts` còn type viết tay vì endpoint lệch contract/đã gỡ; viết lại ở bước 4–5 |
| `/profile/security` chạy được với Auth thật qua gateway | Đạt cho liệt kê phiên, thu hồi một phiên, đăng xuất tất cả. Đổi mật khẩu, đổi email, xoá tài khoản dùng cùng cơ chế kết thúc phiên nhưng chưa bấm thử trên UI |
| Tài khoản thiếu `ADMIN` thấy 403 ở `/admin`; tài khoản `ADMIN` thấy menu "Trang quản trị" | `RequireRole` và mục menu đã có; kiểm chứng ở bước 1 khi có route `/admin`. Đã thấy tài khoản `USER` không có mục "Trang quản trị" |

---

## 4. Bước 1 — Khung admin

> **Trạng thái (29/09/2026): xong.** Admin shell, tổng quan và danh sách sản phẩm
> chỉ đọc đã chạy với Product Service thật qua gateway. `typecheck`, `lint` (0 lỗi),
> `test` (42 test) và `build` qua trong container. Đã kiểm tra trực quan ở 375px,
> 768px và 1280px, cả light/dark mode; drawer giữ focus, đóng bằng `Escape`; tài khoản
> `ADMIN` đọc được catalog thật và tài khoản `USER` thấy trạng thái 403.

### 4.1. Mục tiêu

Có admin shell và bộ component quản trị dùng chung, được chứng minh bằng một màn thật:
danh sách sản phẩm chỉ đọc lấy từ `GET /api/v1/products`. Bước 2 trở đi chỉ lắp thêm
màn hình vào khung này.

### 4.2. Cấu trúc route

```text
src/app/admin/
  layout.tsx          RequireRole ADMIN + AdminShell
  page.tsx            Tổng quan (lối tắt)
  not-found.tsx
  products/page.tsx   Danh sách sản phẩm chỉ đọc (màn mẫu)
```

`/admin` là thư mục thường (không phải route group) để URL có prefix `/admin` và
không kế thừa header/footer của `(site)`.

### 4.3. Admin shell

- **Desktop (`lg` trở lên)**: sidebar cố định rộng 240px bên trái, nền `bg-surface-2`,
  viền phải `border-border`; vùng nội dung `max-w-medium` với lề `px-6 md:px-8`.
- **Sidebar**: nhóm mục theo [sitemap §6](./sitemap.md#6-điều-hướng), mục đang chọn dùng
  thanh clay 2px giống sidebar tài khoản. Cấu hình nav là một mảng có cờ `enabled` để mục
  của bước chưa làm không hiện.
- **Topbar**: breadcrumb, nút đổi theme, menu tài khoản, liên kết "Về cửa hàng".
- **Mobile**: sidebar thành drawer mở bằng nút menu trên topbar. Khác với sidebar tài
  khoản (thành tab ngang) vì admin có nhiều nhóm mục hơn.
- **Tổng quan `/admin`**: lưới lối tắt tới việc thường làm (tạo sản phẩm, nhập hàng, tìm
  đơn, hàng đợi kiểm duyệt). Không hiển thị số liệu thống kê vì không có API; không dùng
  số giả.

### 4.4. Component quản trị

Thêm vào `src/components/ui/` (nguyên tố) và `src/components/admin/` (ghép), đồng thời
ghi công thức vào `references/components.md` và pattern vào `references/patterns.md` của
Terra.

| Component | Vai trò | Ghi chú |
|---|---|---|
| `Dialog`, `ConfirmDialog` | Hộp thoại; xác nhận hành động | `ConfirmDialog` có ô lý do bắt buộc và tuỳ chọn gõ lại tên để xác nhận hành động không hoàn tác (takedown, xoá sản phẩm, huỷ campaign, xoá tài khoản) |
| `Sheet` | Panel trượt từ cạnh | Drawer nav trên mobile; panel chi tiết review ở bước 12 |
| `Tabs` | Tab trong trang chi tiết | Sản phẩm, campaign, đơn hàng |
| `Textarea` | Ô nhập nhiều dòng | Lý do, mô tả |
| `DataTable` | Bảng có cột cấu hình, hành động theo dòng, phân trang cursor | Dựa trên `@tanstack/react-table`; đủ năm trạng thái Terra; trên mobile đổi thành danh sách card |
| `FilterBar` | Ô tìm, select, khoảng ngày | Đồng bộ với URL search params |
| `PageHeader` | Tiêu đề serif, mô tả, hành động chính | Một điểm accent clay cho hành động chính |
| `StatusBadge` | Badge trạng thái | Đọc nhãn và tông màu từ `src/lib/labels/` |
| `IdChip` | Hiển thị UUID/mã rút gọn, bấm để sao chép | Mã đơn, mã SKU, command ID |
| `MoneyInput` | Nhập số tiền VND nguyên | Hiển thị có dấu chấm nghìn, gửi số nguyên |
| `DateTimeInput` | Nhập ngày giờ theo giờ Việt Nam, gửi RFC 3339 | Lịch campaign |
| `FormSection`, `StickySaveBar` | Chia form dài thành card; thanh lưu dính đáy khi form dirty | Cảnh báo khi rời trang có thay đổi chưa lưu |
| `EmptyState`, `ErrorState` | Trạng thái rỗng và lỗi | Tách ra từ công thức Terra hiện có |
| `OperationStatus` | Hiển thị tiến trình lệnh bất đồng bộ | Dùng `pollOperation` của bước 0 |

Danh sách này chỉ gồm thứ bước 2–3 cần ngay; component riêng cho Rule AST, upload media,
cây danh mục làm ở bước dùng tới chúng.

### 4.5. Quy ước dữ liệu admin

- Lệnh sửa dùng ETag từ cache và `If-Match` (0.4); lỗi `412` hiện hộp thoại cho tải lại,
  không ghi đè.
- Mỗi lần mở một dialog lệnh tạo một command ID mới; retry trong cùng dialog dùng lại ID.
- Lệnh có hậu quả tài chính hoặc không hoàn tác luôn qua `ConfirmDialog` với lý do.
- Sau lệnh thành công: toast, invalidate query liên quan, không reload trang.
- Danh sách giữ bộ lọc trên URL; lịch sử cursor nằm trong state của `useCursorPager` (0.5).

### 4.6. Màn mẫu: danh sách sản phẩm chỉ đọc

- `GET /api/v1/products` với lọc `status` (`DRAFT`/`ACTIVE`/`HIDDEN`) và `category_id`,
  phân trang `cursor`/`limit`.
- Cột theo `ProductSummary`: tên, slug, danh mục, trạng thái, `catalog_revision`, cập nhật
  lúc. `ProductSummary` chỉ có `category_id` nên tên danh mục tra từ
  `GET /api/v1/categories` (cache chung); không có thumbnail hay số SKU trong danh sách.
- Chưa có tạo/sửa (bước 2); dòng bấm vào chưa điều hướng.
- Kiểm chứng đủ: skeleton, rỗng, lỗi có trace ID, lọc qua URL, trang trước/sau, mobile
  thành card, dark mode.

### 4.7. Kiểm chứng

- Đăng nhập bằng tài khoản admin từ `docs/auth/schema/seed-admin.sql.example` vào được
  `/admin`; tài khoản thường thấy 403.
- Admin shell đúng ở 375px, 768px, 1280px; drawer mở/đóng được bằng bàn phím, focus bị
  giữ trong drawer khi mở.
- Chạy checklist Terra (`references/checklist.md`) cho shell và màn danh sách.
- `lint`, `typecheck`, `test`, `build` qua trong container.

Kết quả ngày 29/09/2026:

| Tiêu chí | Kết quả |
|---|---|
| Tài khoản `ADMIN` vào `/admin`; tài khoản `USER` thấy 403 | Đạt với `admin@technova.test` và `buyer@technova.test` trên stack local |
| Product và category đọc qua gateway | Đạt; sáu sản phẩm mẫu hiện đúng tên, slug, danh mục, trạng thái, revision và giờ Việt Nam |
| Bộ lọc nằm trên URL, cursor không nằm trên URL | Đạt; đã kiểm tra `?status=ACTIVE`, dữ liệu và select đồng bộ |
| Responsive 375px / 768px / 1280px | Đạt; 375px dùng card + drawer, từ 768px dùng bảng, từ 1024px dùng sidebar cố định |
| Drawer dùng bàn phím | Đạt; focus chỉ chạy vòng trong drawer và `Escape` đóng drawer |
| Light/dark mode | Đạt; bề mặt, viền, chữ và badge không có mảng màu sai token |
| `typecheck`, `lint`, `test`, `build` | Đạt; lint 0 lỗi (cảnh báo còn lại thuộc storefront legacy đã ghi ở bước 0), 42 test qua |

### 4.8. Tiêu chí xong

- [x] Admin shell, tổng quan và danh sách sản phẩm chỉ đọc chạy với Product Service thật
  qua gateway.
- [x] Các component ở 4.4 có trong `src/components/` và có công thức trong Terra.
- [x] Nav admin chỉ hiện mục đã làm (`Tổng quan`, `Sản phẩm`); mục của bước sau vẫn ở
  cấu hình với `enabled: false`.

---

## 5. Bước 2 — Admin Catalog

> **Trạng thái (29/09/2026): hoàn thành.** Các màn quản trị danh mục, thuộc tính và
> sản phẩm đã dùng Product Service thật qua gateway. Luồng CRUD trực tiếp đã được chạy
> hết vòng và dữ liệu kiểm thử đã được dọn sạch.

### 5.1. Phạm vi

- Bật các mục `Sản phẩm`, `Danh mục`, `Thuộc tính` trong admin navigation.
- Thêm màn tạo và chi tiết sản phẩm, gồm thông tin chung, SKU, media và trạng thái.
- Thêm cây danh mục, chỉnh sửa danh mục và ánh xạ thuộc tính vào danh mục.
- Thêm danh sách thuộc tính, chỉnh sửa thuộc tính và quản lý option.
- Dùng ETag/`If-Match` cho mọi thay đổi trên aggregate sản phẩm để không ghi đè dữ
  liệu đã được người khác cập nhật.

### 5.2. Route đã hoàn thành

| Route | Chức năng |
|---|---|
| `/admin/products` | Danh sách, lọc và phân trang sản phẩm |
| `/admin/products/new` | Tạo sản phẩm nháp với danh mục và giá trị thuộc tính |
| `/admin/products/[productId]` | Sửa thông tin, SKU, media, trạng thái và xóa sản phẩm |
| `/admin/categories` | Cây danh mục, tạo danh mục gốc hoặc danh mục con |
| `/admin/categories/[categoryId]` | Sửa/xóa danh mục và ánh xạ thuộc tính |
| `/admin/attributes` | Danh sách và tạo thuộc tính |
| `/admin/attributes/[attributeId]` | Sửa/xóa thuộc tính và quản lý option |

Các route chi tiết có `loading.tsx`; các danh sách và form có đủ trạng thái loading,
rỗng, lỗi, thành công và xung đột dữ liệu cũ.

### 5.3. Product API client và quy tắc đồng thời

- `src/lib/api/product.ts` cung cấp kiểu và hàm gọi cho toàn bộ Product, SKU, media,
  category, category-attribute mapping, attribute và attribute option dùng ở bước này.
- ETag lấy từ response đọc/tạo/cập nhật và gửi lại bằng `If-Match` khi thay đổi product,
  SKU hoặc media. Khi server trả `412`, giao diện giữ dữ liệu người dùng đang nhập,
  báo bản ghi đã cũ và cho tải lại bản mới nhất.
- `POST /api/v1/products` là thao tác không idempotent. Sau lỗi mạng không xác định kết
  quả, giao diện không tự gửi lại và hướng dẫn người dùng kiểm tra danh sách trước.
  Product API hiện chưa có endpoint tra cứu chính xác theo slug; khoảng trống này cũng
  được ghi ở sitemap.
- Các mã lỗi conflict Product Service thực tế có thể trả về đã được bổ sung vào mô tả
  `DomainConflict` trong OpenAPI. Response xóa SKU/media đã khai báo header ETag để
  contract phản ánh đúng aggregate version mới.

### 5.4. Danh mục và thuộc tính

- Cây danh mục được làm phẳng theo độ sâu để dùng thống nhất ở list, select parent và
  select danh mục của sản phẩm; không cho chọn chính danh mục hiện tại làm parent.
- Màn chi tiết danh mục quản lý metadata và mapping thuộc tính; lỗi mapping trùng hoặc
  option đang được tham chiếu được chuyển thành thông báo tiếng Việt có ngữ cảnh.
- Thuộc tính hỗ trợ kiểu `TEXT`, `NUMBER`, `BOOLEAN`, `SELECT`, `MULTI_SELECT`; option
  chỉ xuất hiện với kiểu chọn. Thuộc tính bắt buộc của danh mục được kiểm tra trước khi
  tạo/cập nhật sản phẩm.

### 5.5. Sản phẩm, SKU, media và trạng thái

- Form tạo sinh slug có thể chỉnh sửa, chọn danh mục và nhập giá trị thuộc tính theo
  schema của danh mục.
- Màn chi tiết tách các tab thông tin, SKU, media và trạng thái. Mỗi mutation cập nhật
  ETag cục bộ từ response để mutation kế tiếp luôn dùng version mới nhất.
- SKU hỗ trợ tạo, sửa, xóa và `compare_at_price`; media hỗ trợ tạo, sửa thứ tự/alt text,
  xóa. Do backend chưa có API upload, tab media nhập `storage_key` thủ công và ghi rõ
  giới hạn này cho quản trị viên.
- Trạng thái dùng đúng transition backend: kích hoạt, ẩn và kích hoạt lại. Xóa sản phẩm
  yêu cầu xác nhận trong dialog và đưa người dùng về danh sách sau khi thành công.

### 5.6. Kiểm chứng

Kết quả ngày 29/09/2026:

| Kiểm tra | Kết quả |
|---|---|
| TypeScript | `tsc --noEmit` qua |
| ESLint | 0 lỗi; 35 cảnh báo còn lại thuộc storefront legacy |
| Unit test | 46 test qua |
| Production build | Next.js 16.2.9 build thành công; toàn bộ route bước 2 được sinh |
| Live read | Đọc product, category và attribute qua gateway thành công |
| Live CRUD | Tạo/sửa/xóa attribute + option, category + mapping, product + SKU + media, chuyển trạng thái; dữ liệu QA đã dọn sạch |

### 5.7. Tiêu chí xong

- [x] Route và navigation của bước 2 đã hoạt động với Product Service thật.
- [x] Product, SKU và media mutation tuân thủ ETag/`If-Match`, có xử lý `412`.
- [x] Category tree, attribute option và category-attribute mapping có đủ CRUD cần thiết.
- [x] Contract, type API, sitemap và kế hoạch triển khai đồng bộ với hành vi code.
- [x] Typecheck, lint, test, build và live CRUD qua.

---

## 6. Bước 3 — Admin Kho tối thiểu

> **Trạng thái (05/10/2026): hoàn thành code, kiểm thử offline và live local.**
> Inventory Service chạy thật qua gateway; MFA vẫn là gate production của Auth/gateway.

Đã hoàn thành client typed và tám route cho tra cứu theo mã, warehouse master data,
stock position, sellable receipt, adjustment, movement reversal và command outcome.
Không tạo API danh sách ngoài contract. Mọi mutation dùng ba header command bắt buộc,
giữ cùng ID qua retryable failure và bắt đầu ID mới khi người dùng sửa payload.

Chi tiết phạm vi, 101 test, visual QA và bằng chứng live nằm tại
[step-3-verification.md](./step-3-verification.md).

### 6.1. Tiêu chí xong

- [x] Route, navigation, Next rewrite và typed API client khớp Inventory ADMIN v1.
- [x] Form conditional theo receipt/adjustment/reversal enum; copy và lỗi bằng tiếng Việt.
- [x] Loading/error/success/replayed, command recovery và bucket invariant được biểu diễn.
- [x] Typecheck, 101 test, lint 0 lỗi và production build qua.
- [x] Terra visual QA ở 375/768/1280 px, light/dark; route QA tạm đã xoá.
- [x] Live gateway/Auth role/Inventory verification qua trên môi trường local; MFA
  được giữ là gate triển khai production.
- [x] Theo [Inventory ADR-027](../inventory/decisions/ADR-027-pre-implementation-contract-corrections.md):
  sinh lại type từ ADMIN OpenAPI, thêm `name` vào form tạo/sửa kho, gửi `expected_revision`
  khi sửa kho và hiển thị `WAREHOUSE_CONFLICT`/`WAREHOUSE_NOT_FOUND` bằng tiếng Việt.

## 7. Bước 4 — Duyệt sản phẩm

> **Trạng thái (30/09/2026): xong.** Typecheck, lint, 62 test frontend, build,
> 44 test Review và kiểm chứng live/UI qua.
> Tại thời điểm hoàn thành bước 4, bước 3 từng bị hoãn vì Inventory Service chưa hoàn
> thiện; bước 3 đã hoàn tất kiểm chứng live local ngày 05/10/2026.

Search v1 đã được thay bằng Search v2. Trang chủ, tìm kiếm, danh mục và PDP render
phía server; PDP lấy catalog/giá/gallery/availability/review preview từ Product Detail
BFF. Trang review công khai có lọc, tải thêm, media và báo cáo. Nút thêm giỏ trên PDP
dùng Cart v1 với delta/idempotency key; màn giỏ đầy đủ giữ ở bước 5.

Phạm vi, quy tắc dữ liệu, sửa backend tương ứng và bằng chứng kiểm thử nằm tại
[step-4-verification.md](./step-4-verification.md).

### 7.1. Tiêu chí xong

- [x] Trang chủ, tìm kiếm, danh mục, PDP và Review công khai gọi API đúng contract.
- [x] Server render, cache/no-store, cursor và xử lý `UNKNOWN` khớp quy tắc dữ liệu.
- [x] Thêm giỏ trên PDP dùng delta/idempotency; counter header cập nhật sau thao tác.
- [x] Các sửa gateway và signed URL Review đồng bộ với tài liệu, OpenAPI và type sinh.
- [x] Typecheck, production build, 62 test frontend và 44 test Review qua; lint 0 lỗi,
  11 cảnh báo còn lại thuộc code cũ. Live API/UI và responsive 375/768/1280 px đã kiểm chứng.

### 7.2. Công việc tiếp theo

- Bước 5 đã thay màn giỏ hàng cũ bằng Cart v1; kết quả ở
  [step-5-verification.md](./step-5-verification.md).
- Bước 6: checkout và thanh toán Stripe, chưa có voucher.
- Bước 3 đã hoàn thành; MFA assertion vẫn là gate của môi trường production.
- Bước 13: bổ sung E2E với fixture suggestions có dữ liệu, facet nhiều option, video
  và tổ hợp nhiều SKU theo giới hạn kiểm chứng trong báo cáo bước 4.

## 8. Bước 5 — Giỏ hàng

> **Trạng thái (30/09/2026): hoàn thành.** Typecheck, production build, 86 test
> frontend và live API qua; lint 0 lỗi, 3 cảnh báo ngoài Cart.

### 8.1. Phạm vi

- `/cart` nằm trong nhóm `(site)/(protected)`, dùng `RequireAuth` và render phía
  client theo ADR-002. Chờ khôi phục phiên trước khi đọc; anonymous về
  `/login?next=%2Fcart`.
- `lib/api/cart.ts` dùng type sinh Cart v1. Bỏ client `storefront-cart.ts` và
  Zustand giữ bản sao dữ liệu cũ; PDP, header và màn giỏ dùng chung query
  `['cart', accountId]`.
- `GET /api/v1/carts/mine` đọc `items` phẳng, Money `{amount_minor, currency}`,
  `total_units`, `total_unique_skus`, `catalog_freshness` và `variant_attributes`.
- Tăng/giảm gửi `POST /api/v1/carts/mine/items` với `{sku_id, delta}`;
  số lượng trong UI giới hạn 1–100. Xóa một dòng hoặc nhiều dòng gửi `DELETE`
  cùng endpoint với `{sku_ids}` và `Idempotency-Key`.

### 8.2. Đồng thời và retry

- `useCommand` sinh một key cho mỗi ý định. Lỗi retryable giữ nguyên key và
  **toàn bộ payload**, khóa thao tác khác cho tới khi thử lại thành công; nút retry
  tuân thủ `Retry-After`. Vẫn có thể tải lại snapshot trong lúc kết quả chưa rõ.
- Không cập nhật quantity hoặc xóa dòng lạc quan. Sau mỗi kết quả write đọc lại
  Cart; badge, số lượng và tạm tính lấy snapshot backend. GET sau timeout không
  tự giải phóng lệnh chưa xác nhận, kể cả khi dòng đã biến mất.
- Lỗi xác định như 422 giữ nội dung và cho ý định mới sau refetch. Phiên mới dùng
  cache khác; component theo account ID bỏ lựa chọn/lệnh đang giữ của user cũ.

### 8.3. Giá và tình trạng bán

- VND là số nguyên đồng, không chia 100. Không cộng lẫn tiền tệ hoặc số tiền vượt
  giới hạn số nguyên an toàn của JavaScript.
- Giá lúc thêm là lịch sử, không làm giá hiện tại fallback. `current_price=null`
  hiện “Chưa xác minh giá”; không giả giá 0.
- `DEGRADED` hiện cảnh báo; không dùng availability snapshot làm trạng thái hiện
  tại và không tính tổng. Vẫn giữ dòng và cho giảm/xóa.
- `CURRENT` + `is_available=false` là **ngưng bán theo catalog**, không phải hết
  stock. Dòng ngưng bán bị loại khỏi tạm tính, vẫn có thể giảm/xóa. Cart không gọi
  Inventory nên bước này không phụ thuộc bước 3.
- Cảnh báo tăng/giảm giá so với lúc thêm chỉ so cùng currency, có giá CURRENT và
  còn bán. Không gọi chênh lệch giá lịch sử là khuyến mãi.
- Nút thanh toán hiện disabled kèm giải thích vì checkout thuộc bước 6; không
  tạo link tới route chưa triển khai.

### 8.4. Tiêu chí xong

- [x] Đọc, delta, xóa đơn/batch theo đúng Cart v1 và identity gateway.
- [x] Retry giữ nguyên key/payload; Retry-After khóa nút; cache cách ly theo account.
- [x] Loading, empty, error, stale-read, degraded và success theo Terra, sáng/tối,
  viewport CSS 375/768/1280 px, nhãn tiếng Việt và control tối thiểu 44 px.
- [x] Typecheck, lint, 86 test, build, smoke API và UI với backend thật.
- [x] Kế hoạch, sitemap và tài liệu Cart frontend được cập nhật; không sửa backend
  hoặc contract trong bước này. Bằng chứng/giới hạn ở
  [step-5-verification.md](./step-5-verification.md).

## 9. Bước 7 — Đơn hàng của tôi

> **Trạng thái (05/10/2026): hoàn thành code và kiểm chứng offline.** Typecheck,
> 110 test, lint không có lỗi và production build qua. Kiểm chứng live với Stripe
> test mode còn chờ stack local và Stripe CLI.

- `/orders` đọc cursor từ `GET /api/v1/orders`, có tab Buyer, tìm theo tên snapshot,
  tải thêm và đồng bộ bộ lọc với URL.
- `/orders/[orderId]` hiển thị snapshot theo kiện/kho, giao hàng từng dòng, địa chỉ,
  tiền, trạng thái payment/refund và `available_actions` do backend quyết định.
- Huỷ, trả hàng một lần và tạo payment attempt mới đều dùng `useCommand`, giữ
  `Idempotency-Key` qua lỗi retryable và poll `OrderOperation`; hết thời gian poll
  được diễn đạt là “vẫn đang xử lý”, không mời gửi lệnh mới.
- `/orders/[orderId]/return` gửi đúng full quantity của mỗi dòng đã chọn; UI cảnh báo
  guard một lần và không tự suy diễn thời hạn trả hàng.
- Payment tối thiểu cho “Thanh toán lại” nằm ở `/orders/[orderId]/payment`: đọc
  `client_secret`/`publishable_key` từ Order, render Stripe Payment Element và về
  `/payment/complete`. Trang complete poll Order vì webhook/backend mới là nguồn xác
  nhận cuối cùng. Phần chọn địa chỉ/preview/submit checkout của bước 6 vẫn ngoài phạm vi.
- Chi tiết kiểm chứng và giới hạn ở
  [step-7-verification.md](./step-7-verification.md).

### 9.1. Tiêu chí xong

- [x] Danh sách, tab, tìm kiếm, cursor, empty/error/loading và account-scoped cache.
- [x] Chi tiết, huỷ, trả hàng, retry payment và refund-case đúng Order v1 hiện hành.
- [x] Stripe Payment Element không đưa dữ liệu thẻ qua backend frontend application;
  UI không coi callback client là bằng chứng thanh toán cuối cùng.
- [x] Typecheck, 110 test, lint 0 lỗi và production build qua.
- [ ] Live gateway/Order/Payment/Stripe test-mode và responsive visual QA — môi trường
  hiện tại không có Docker CLI nên chưa chạy được.

## 10. Bước 8 — Admin Đơn hàng và ghi nhận xuất kho

> **Trạng thái (06/10/2026): hoàn thành code, kiểm chứng offline và live các luồng
> chính.** Typecheck, 114 test, lint không có lỗi và production build qua. Stack local
> đã xác minh Auth/RBAC, danh sách/chi tiết Order, operation polling và physical
> dispatch có idempotent replay. Cancel hoàn tất và pre-dispatch refund vẫn cần fixture
> Order tạo qua workflow thật; đơn seed thủ công không có workflow để saga kết thúc.

- `/admin/orders` gọi namespace admin của Order, có tìm kiếm và bộ lọc theo người mua,
  Buyer tab, trạng thái đơn/thanh toán, khoảng thời gian, khoảng tiền; bộ lọc đồng bộ
  URL và cursor chỉ giữ trong state.
- `/admin/orders/[orderId]` hiển thị snapshot đầy đủ, liên kết sang phân bổ kho và chỉ
  bật huỷ khi `PENDING_PAYMENT`; lệnh dùng `ADMIN_FORCE_CANCEL`, idempotency key và poll
  operation admin.
- Hoàn tiền trước xuất kho chỉ bật khi order `PAID`/`PROCESSING`, chưa có refund case và
  mọi line còn `PROCESSING`. Backend vẫn là authority: Inventory atomic-release có thể
  reject nếu fact thay đổi trong race; UI hiển thị trạng thái operation thay vì gửi lại.
- `/admin/inventory/reservations/[orderId]` đọc fact phân bổ, chỉ cho dispatch line
  `COMMITTED` và gửi nguyên warehouse/SKU/quantity bất biến cùng business identity,
  thời điểm bàn giao, vận đơn và evidence. Lệnh gửi đủ `Idempotency-Key`,
  `X-Correlation-ID`, `X-Causation-ID`.
- Chi tiết kiểm chứng và giới hạn ở
  [step-8-verification.md](./step-8-verification.md).

### 10.1. Tiêu chí xong

- [x] Danh sách admin, bộ lọc URL, cursor, loading/empty/error và responsive table/card.
- [x] Chi tiết đơn, huỷ pre-payment và pre-dispatch refund đúng Order v1 admin.
- [x] Phân bổ kho và physical dispatch nguyên dòng đúng Inventory admin v1.
- [x] Retry giữ command identity; Order command poll operation; lỗi race/conflict không
  bị diễn đạt thành thành công.
- [x] Typecheck, 114 test, lint 0 lỗi và production build qua.
- [x] Live gateway/Auth/Order/Inventory: ADMIN đọc danh sách/chi tiết, non-ADMIN bị
  `403`, dispatch `COMMITTED` thành công, replay không trừ tồn lần hai và lệnh mới trên
  line đã dispatch bị `409 DISPATCH_NOT_ALLOWED`.
- [ ] Hoàn tất live cancel/refund bằng fixture tạo qua checkout workflow thật và visual
  QA đủ ma trận 375/768/1280 px, light/dark, bàn phím, screen reader.

## 11. Bước 9 — Đánh giá sau mua và hộp thông báo

> **Trạng thái (06/10/2026): hoàn thành code, kiểm chứng offline và live local.**
> Typecheck, 180 test, lint không có lỗi và production build qua. Review (build lại,
> Flyway `V2`), MinIO và Notification đã chạy thật qua gateway. Entitlement dùng để thử
> được tạo bằng `ORDER_COMPLETED` đúng contract vì bước 6 (checkout) chưa có.

- `/reviews` (Chờ đánh giá / Đã đánh giá), `/reviews/new?eligibility=` và
  `/reviews/[reviewId]` (xem + sửa đúng một lần) cho Review; `/notifications` và chuông
  trên header cho Notification. Nút "Đánh giá sản phẩm" xuất hiện ở dòng hàng của đơn
  `COMPLETED` còn entitlement (khớp `order_id`).
- Review không có idempotency key: create/edit mất phản hồi thì đọc phục hồi theo
  contract thay vì gửi lại; media upload ba bước (xin chỉ dẫn ký → `PUT` thẳng storage →
  gửi `asset_id`), sửa media là thay toàn bộ.
- **Backend được bổ sung cho bước này** (Review ADR-012): `ORDER_COMPLETED` mang tên/ảnh
  sản phẩm, Review lưu snapshot và thêm `GET /reviews/me`; link thông báo
  `REVIEW_REPLIED` đổi thành `/reviews/{review_id}`. Tài liệu/contract liên quan đã đồng bộ.
- Chi tiết phạm vi, bằng chứng live và giới hạn ở
  [step-9-verification.md](./step-9-verification.md).

### 11.1. Tiêu chí xong

- [x] Danh sách chờ đánh giá và đã viết (cursor, tab trên URL, đủ năm trạng thái).
- [x] Viết đánh giá kèm ảnh/video, sửa một lần, hiển thị review bị ẩn/gỡ, phản hồi cửa hàng.
- [x] Phục hồi sau timeout cho create/edit; không gửi lại mù quáng.
- [x] Hộp thư: đọc/chưa đọc, ẩn, đọc tất cả, điều hướng deep link chỉ trong cùng origin; chuông poll 45 giây.
- [x] Backend và tài liệu đồng bộ theo ADR-012; type sinh lại từ OpenAPI.
- [x] Typecheck, 180 test, lint 0 lỗi, production build; live Review/Notification qua gateway.
- [ ] Bấm thử nút đánh giá trên đơn hàng thật (cần checkout, bước 6) và Debezium cho Review outbox; visual QA đủ ma trận 375/768/1280 px, light/dark, screen reader.

## 12. Bước 10 — Admin Khuyến mãi

> **Trạng thái (06/10/2026): hoàn thành code, kiểm chứng offline và live local.**
> Typecheck, 209 test, lint không có lỗi và production build qua. Auth, gateway-auth,
> Product và Promotion chạy thật; luồng tạo → sửa → xuất bản → tạm dừng → huỷ → nhân bản
> và cấp voucher đã chạy hết trên UI.

- `/admin/promotions` liệt kê campaign, lọc theo trạng thái xuất bản và giai đoạn (URL),
  cursor trong state. `/admin/promotions/new` tạo campaign nháp.
- `/admin/promotions/[campaignId]` có bốn tab đồng bộ `?tab=`: thông tin và lịch (sửa khi còn
  nháp), voucher, giảm giá theo sản phẩm, vòng đời (xuất bản / tạm dừng / huỷ có lý do, huỷ
  phải gõ lại mã; nhân bản kèm mã mới cho từng voucher).
- Voucher (`…/vouchers/new`, `…/vouchers/[promotionId]`): cách phát hành, mức giảm theo
  phần trăm (đổi sang basis point bằng số nguyên) hoặc số tiền, đơn tối thiểu, hạn dùng, giới
  hạn, và **Rule AST builder** (nhóm AND/OR lồng nhau, field theo allowlist v1, danh mục chọn từ
  Product). Voucher `DIRECT_GRANT` trên campaign đã xuất bản có nút cấp cho một người dùng.
- Markdown (`…/markdowns/new`, `…/markdowns/[ruleId]`): chọn sản phẩm bằng tìm kiếm Search v2
  hoặc dán UUID, mức giảm, độ ưu tiên, bật/tắt; hiển thị rule đã bị vô hiệu hoá do sản phẩm bị xoá.
- Mọi lệnh sửa gửi `If-Match` là revision của **campaign cha**; `412` giữ nguyên dữ liệu đang
  nhập và cho tải lại. PATCH là merge-patch chỉ gồm trường đã đổi; object lồng gửi nguyên khối.
  Campaign đã xuất bản chỉ đọc (bất biến), muốn đổi phải nhân bản.
- **Gateway được sửa cho bước này**: router `promotion-admin` chuyển từ `dev-admin-auth` sang
  `authenticated@file`; role `ADMIN` nay lấy từ token thật, `created_by` là tài khoản đăng nhập.
  Router customer (`promotion-me`, `promotion-discovery`) vẫn `dev-auth` tới bước 11. Tài liệu
  gateway đã đồng bộ ([runtime-contract](../gateway/runtime-contract.md),
  [implementation_plan §3.6](../gateway/implementation_plan.md)).
- Chi tiết phạm vi, giới hạn của contract và bằng chứng live ở
  [step-10-verification.md](./step-10-verification.md).

### 12.1. Tiêu chí xong

- [x] Danh sách, tạo, sửa, xuất bản/tạm dừng/huỷ và nhân bản campaign đúng Promotion v1.
- [x] Voucher: form đủ trường, Rule AST builder có kiểm tra giống `RuleValidator`, cấp trực tiếp.
- [x] Markdown theo sản phẩm; campaign đã xuất bản chỉ đọc.
- [x] ETag/`If-Match` của campaign cha, `412` không ghi đè; PATCH chỉ gửi trường đổi.
- [x] Gateway `promotion-admin` dùng token thật: ẩn danh `401`, `USER` `403`, `ADMIN` `200`,
  header identity giả bị bỏ.
- [x] Typecheck, 209 test, lint 0 lỗi, production build.
- [x] Live qua gateway; responsive 375px (light) và 800px (dark) không tràn ngang.
- [ ] Visual QA đủ ma trận 375/768/1280 px × light/dark, bàn phím và screen reader.

## 13. Phụ thuộc backend

| Phụ thuộc | Cần cho | Ảnh hưởng nếu thiếu |
|---|---|---|
| Router customer Promotion (`promotion-me`, `promotion-discovery`) chuyển từ `dev-auth` sang `authenticated@file` | Bước 11 | Voucher của người mua còn identity cố định. `promotion-admin` đã chuyển ở bước 10; Cart, Review, Auth, User, Notification, Product cũng dùng gateway-auth |
| API tìm người dùng và API liệt kê voucher đã cấp | Cấp voucher (bước 10) | Admin phải dán UUID người dùng; UI chỉ hiển thị kết quả lần cấp vừa rồi, không có danh sách người đã nhận |
| `user-service` build được | Hồ sơ, địa chỉ, checkout | `go build` thiếu entry `go.sum` cho `golang.org/x/crypto/*` (sha3, md4, pbkdf2) |
| Product Service và gateway chạy được trong docker-compose | 4.6 | Đạt: `GET /api/v1/products` qua gateway trả 401/403/200 đúng theo token; dữ liệu mẫu tạo bằng `frontend/scripts/seed-dev-catalog.sh` |
| Debezium cho Review outbox; Notification chạy với profile `notification` (+ `notifctl migrate up`) | Bước 9 trên local | Không có CDC thì `REVIEW_REPLIED` không tự tới hộp thư (đã chuyển tay khi kiểm chứng); thiếu Notification thì chuông và `/notifications` báo lỗi/ẩn số |
| MFA assertion cho `ADMIN` | Triển khai Inventory admin lên production | Frontend và live local bước 3 đã hoàn thành; Auth/gateway production vẫn phải phát hành và xác thực MFA assertion |
| API tải media cho Product | Bước 2 (tab media) | Admin phải nhập `storage_key` tay |
| API tra cứu product chính xác theo slug hoặc idempotency key cho tạo product | Bước 2 (khôi phục sau timeout) | Không thể tự động xác minh `POST` đã thành công; UI không tự retry và yêu cầu kiểm tra danh sách |

Danh sách đầy đủ ở [sitemap §5](./sitemap.md#5-khoảng-trống-api-ảnh-hưởng-tới-route).
