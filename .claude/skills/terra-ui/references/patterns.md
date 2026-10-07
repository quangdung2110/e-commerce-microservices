# Terra — Pattern cấp trang

---

## 1. Trang auth (đăng nhập, đăng ký, quên mật khẩu, nhập OTP)

Bố cục **hai cột chia đôi** trên `lg`, một cột trên mobile.

```
lg:grid lg:grid-cols-2  |  min-h-svh
├─ Cột trái: form
│   flex items-center justify-center px-6 py-12 lg:px-16
│   └─ div  w-full max-w-[400px] space-y-8
│       ├─ Logo (link về "/")
│       ├─ header: h1 text-headline-3 font-serif font-normal
│       │          + p text-[15px] text-muted-foreground
│       ├─ (banner lỗi nếu có)
│       ├─ form space-y-5  →  Button lg w-full
│       └─ footer: link sang màn hình còn lại, text-[14px]
└─ Cột phải: bảng giới thiệu   hidden lg:flex
    bg-surface-2 border-l border-border
    Một câu quote/giá trị bằng text-headline-4 font-serif font-light,
    max-w-prose-title, cộng vài chấm nhỏ mang màu hue phụ.
```

Cột phải **không dùng ảnh nền tối, không gradient, không glow**. Nó là một mặt phẳng
màu cát với chữ serif — sự tương phản đến từ chất liệu, không từ hiệu ứng. Nếu cần hình,
dùng minh hoạ phẳng dùng hue phụ (`sage`, `sky`, `oat`), không dùng ảnh stock.

Form auth luôn: `autoComplete` đúng (`email`, `current-password`, `new-password`,
`one-time-code`), `type="email"`/`inputMode="numeric"` đúng, nút submit `size="lg"
className="w-full"`, và Enter submit được.

---

## 2. Trang settings / profile (có sidebar)

```
mx-auto w-full max-w-small px-6 py-10 md:px-8 md:py-14
├─ header trang: h1 text-headline-2 font-serif font-normal
│                 + p text-[15px] text-muted-foreground   (space-y-2, mb-10)
└─ md:grid md:grid-cols-[220px_1fr] md:gap-12
    ├─ nav dọc (sticky top-24 self-start)
    │   item: flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px]
    │         hover:bg-accent
    │   item đang chọn: bg-accent font-medium text-foreground
    │         + thanh clay 2px bên trái (absolute inset-y-1 left-0 w-0.5 bg-clay rounded-full)
    └─ nội dung: space-y-8, mỗi nhóm là một Card
```

Trên mobile nav thành hàng ngang cuộn được (`flex gap-1 overflow-x-auto`), **không** thành
dropdown — người dùng cần thấy có những mục gì.

### Card cài đặt

Mỗi nhóm cài đặt là một Card có tiêu đề, mô tả một dòng, nội dung, và footer chứa nút
lưu **được ghim bên phải**. Nút lưu `disabled` khi form chưa dirty. Sau khi lưu thành
công hiện toast, không hiện banner trong card.

Form dài chia thành các Card theo chủ đề, không phải một Card 20 field.

---

## 3. Grid sản phẩm

`grid gap-x-5 gap-y-8 grid-cols-2 md:grid-cols-3 xl:grid-cols-4`. Ảnh `aspect-square
rounded-xl bg-surface-2 object-cover`. Tên sản phẩm 2 dòng (`line-clamp-2 text-[15px]`),
giá dưới cùng. Card sản phẩm **không có viền** — khoảng trắng đã đủ tách chúng ra; viền
chỉ làm lưới trông như bảng tính.

---

## 4. Motion

| Tình huống | Cách làm |
|---|---|
| Nội dung mới xuất hiện | `animate-in fade-in duration-200` — chỉ fade, không zoom, không slide xa |
| Panel/dialog mở | `fade-in-0 zoom-in-95 duration-200 ease-out-quart` |
| Dropdown | `fade-in-0 slide-in-from-top-1 duration-150` |
| Nút nhấn | `active:scale-[.98] duration-100` |
| Hover card | chỉ đổi `border-color` / `background-color`, **không** dịch chuyển vị trí |
| Skeleton → nội dung | `animate-in fade-in duration-300`, giữ nguyên layout để không nhảy |

Cấm: parallax, chữ trôi vào từ xa, phần tử bay lên khi scroll, spinner toàn trang.

Tôn trọng `prefers-reduced-motion` — token đã có media query tắt animation, nhưng nếu
bạn viết animation thủ công thì phải tự bọc.

---

## 5. Trạng thái dữ liệu và lỗi

### Năm trạng thái

Mỗi màn hình đọc API phải xử lý đủ: **loading** (skeleton đúng hình dạng), **empty**
(pattern ở `components.md`), **error** (banner + nút thử lại), **partial** (một widget lỗi,
phần còn lại vẫn dùng được), **success**.

React Query: `isPending` → skeleton, `isError` → banner lỗi, `data.length === 0` → empty.
Không bao giờ để màn hình trắng trong lúc chờ.

### Map lỗi backend

Mọi service trả **RFC 9457 problem+json**:

```json
{ "type": "...", "title": "...", "status": 401, "detail": "...",
  "instance": "/api/v1/auth/sessions", "code": "INVALID_CREDENTIALS",
  "trace_id": "...", "retryable": false, "errors": [{"field": "...", "message": "..."}] }
```

Quy tắc:

1. **Không hiển thị `detail` thô** — nó là tiếng Anh, viết cho lập trình viên.
2. Map `code` sang câu tiếng Việt qua `frontend/src/lib/api/problem.ts`. Code không có
   trong bảng thì dùng câu chung + hiện `trace_id` nhỏ bên dưới để hỗ trợ.
3. `errors[]` gắn vào đúng field của react-hook-form bằng `setError(field, …)`, không
   dồn hết lên banner.
4. `code: "RATE_LIMITED"` → đọc header `Retry-After`, hiện "Thử lại sau N giây" và
   `disabled` nút cho đến khi hết.
5. `code: "ACCOUNT_NOT_VERIFIED"` → không phải lỗi, mà là **điều hướng**: đẩy sang
   `/verify-email?email=…`.

Không lộ `trace_id`/`instance` ở nơi dễ thấy; đặt nó ở `text-micro text-muted-foreground`
cuối banner.

---

## 6. Dark mode

`next-themes` với `attribute="class"`, `defaultTheme="system"`, `enableSystem`. Toggle đặt
trong header, là `Button variant="ghost" size="icon"` với `aria-label`.

Mọi màu đi qua token nên dark mode gần như tự động. Bốn chỗ hay vỡ, phải tự kiểm:

- **Ảnh và logo** có nền trắng bệt → dùng biến thể theo theme hoặc bo nền `bg-surface-overlay`.
- **Bóng** vô hình trên nền tối → dark mode dựa vào viền, không dựa vào bóng.
- **Màu tint trạng thái** dùng `*-surface` chứ không phải `bg-danger/10` (opacity trên nền
  tối cho ra màu bùn).
- **`suppressHydrationWarning`** phải có trên `<html>`, nếu không sẽ có cảnh báo hydration.

---

## 7. Accessibility — mức tối thiểu không thương lượng

- Mọi ảnh có `alt`; ảnh trang trí thì `alt=""`.
- Mọi nút chỉ có icon có `aria-label` tiếng Việt.
- Thứ tự heading không nhảy bậc (`h1` → `h2` → `h3`).
- Focus **luôn nhìn thấy**: không bao giờ `outline-none` mà không thay bằng ring khác.
- Vùng bấm tối thiểu 44×44px trên mobile.
- Form: `<label htmlFor>` thật, `aria-invalid`, `aria-describedby` trỏ tới message lỗi.
- Nội dung động (banner lỗi, toast) có `role="alert"` / `aria-live`.
- Màu không phải kênh thông tin duy nhất: trạng thái luôn có chữ hoặc icon kèm theo.

---

## 8. Responsive

Breakpoint dùng của Tailwind: `sm 640` `md 768` `lg 1024` `xl 1280`.

Thiết kế mobile trước. Ba điểm hay sai:

- **Bảng** trên mobile chuyển thành danh sách card, không cuộn ngang.
- **Sidebar** chuyển thành tab ngang cuộn được.
- **Dialog** trên mobile chiếm gần hết màn hình (`max-h-[90svh] overflow-y-auto`), dùng
  `svh` chứ không `vh` để tránh thanh địa chỉ của trình duyệt di động.

---

## 9. Admin shell

```
min-h-svh bg-background
├─ desktop aside: fixed inset-y-0 left-0 hidden lg:block w-60
│                   bg-surface-2 border-r border-border
└─ page: lg:pl-60
   ├─ topbar: sticky top-0 h-16 border-b border-border
   │          breadcrumb + về cửa hàng + theme + tài khoản
   └─ main: mx-auto w-full max-w-medium px-6 py-10 md:px-8 md:py-12
```

- Nav là cấu hình nhóm có cờ `enabled`; không render route chưa hoàn thành.
- Item active dùng `bg-accent font-medium` và thanh `bg-clay` rộng 2px bên trái.
- Dưới `lg`, sidebar biến thành Sheet bên trái, không biến thành tab ngang vì số nhóm
  admin sẽ tăng nhiều.
- Tổng quan chỉ là lưới lối tắt. Không tạo KPI hay số thống kê khi chưa có API.
- Toàn bộ shell nằm sau `RequireRole ADMIN`; đây là rào trải nghiệm, gateway/service vẫn
  là nơi thực thi quyền thật.

## 10. Danh sách quản trị

Thứ tự chuẩn:

1. `PageHeader`.
2. `FilterBar`; mỗi thay đổi filter cập nhật URL bằng `router.replace(..., { scroll:
   false })`.
3. Banner partial nếu metadata phụ (ví dụ tên danh mục) lỗi nhưng dữ liệu chính còn dùng
   được.
4. `DataTable` từ `md`, danh sách card dưới `md`.
5. Pager trước/sau. URL chỉ giữ filter chia sẻ được; stack cursor nằm trong state và về
   trang đầu khi query key đổi.

Khi đổi trang giữ dữ liệu cũ bằng `placeholderData` và đặt `aria-busy`; không thay bảng
bằng skeleton rồi gây nháy. Dòng chỉ đọc không giả affordance bấm/hover điều hướng nếu
chưa có route chi tiết.

## 11. Màn sau mua (đánh giá, hộp thông báo)

- Danh sách người mua dùng cột `max-w-small`, tab trên URL (`router.replace`), thẻ `Card`
  một cột, "Tải thêm" thay cho phân trang; chỉ **một** nút clay trên mỗi màn (nút gửi
  của form), nút trong từng dòng danh sách là outline.
- Form đánh giá: điểm sao → nhận xét → media, nút gửi ghim cuối `CardFooter`. Lỗi từng ô
  dùng `role="alert"` + `aria-invalid`/`aria-describedby`; lỗi từ backend dùng `ErrorState`
  (không lộ `detail`). Lệnh không có idempotency key (Review) không bao giờ tự gửi lại:
  kết quả chưa rõ thì đọc phục hồi (xem `lib/api/review-commands.ts`).
- Trạng thái ngoài `APPROVED` của review nói rõ nhưng không lộ nội bộ: "đang bị ẩn để xem
  xét", "đã gỡ"; không có số báo cáo, không có media.
- Link trong nội dung do backend sinh (thông báo, `deep_link`) chỉ được theo khi cùng
  origin (`lib/notification-link.ts`); không bao giờ đưa vào `href` thô.
