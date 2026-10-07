---
name: terra-ui
description: Design system "Terra" cho toàn bộ frontend của E-Commerce Microservices Platform — bảng token màu/chữ/khoảng cách, công thức component, layout, motion và accessibility. Dùng skill này BẤT CỨ KHI NÀO viết, sửa hoặc review giao diện trong thư mục frontend/ (page, component, layout, Tailwind class, globals.css), kể cả khi chỉ thêm một nút hay một form. Trigger: "làm trang", "sửa giao diện", "thêm component", "style", "UI", "màu", "layout", "responsive", "dark mode", bất kỳ file .tsx nào dưới frontend/src.
---

# Terra Design System

Ngôn ngữ thiết kế của dự án, chắt lọc từ claude.com: **nền giấy ấm, phẳng, viền mảnh,
chữ thoáng, một điểm nhấn đất duy nhất**. Không glassmorphism, không neon, không gradient
trang trí, không đổ bóng nặng.

## 0. Quy tắc bắt buộc đọc trước khi viết dòng CSS đầu tiên

1. **Không bao giờ hard-code màu.** Không `bg-[#faf9f5]`, không `text-white`, không
   `border-gray-200`. Chỉ dùng semantic token: `bg-background`, `text-foreground`,
   `text-muted-foreground`, `border-border`, `bg-surface-2`, `bg-accent-clay`…
   Danh sách đầy đủ: `references/tokens.md`.
2. **Không dùng `text-white` / `text-black`.** Nền tối của Terra là `#141413` chứ không
   phải đen, nền sáng là `#faf9f5` chứ không phải trắng. Dùng `text-foreground` /
   `text-on-accent`.
3. **Mọi màu phải có cặp light + dark.** Token đã lo việc này; nếu bạn viết một class
   màu trần thì dark mode sẽ vỡ.
4. **Chỉ một accent.** `--accent-clay` (#d97757 / #c6613f) dành cho hành động chính và
   trạng thái focus. Không tô accent cho những thứ chỉ mang tính trang trí.
5. **Bo góc theo thang.** `rounded-sm|md|lg|xl|2xl|3xl` (4/6/8/12/16/24px). Không
   `rounded-full` trừ avatar, badge tròn và switch. Không `rounded-[13px]`.
6. **Đổ bóng gần như không tồn tại.** Chỉ `shadow-sm` cho card nổi, `shadow-md` cho
   popover/dropdown, `shadow-lg` cho modal. Phân tách bề mặt bằng **viền 1px**, không
   bằng bóng.
7. **Tiếng Việt.** Toàn bộ copy hiển thị là tiếng Việt. Tiền tệ VND, ngày `dd/MM/yyyy`.

## 1. Ba quyết định định hình toàn bộ giao diện

**Bề mặt phân tầng bằng độ ấm, không bằng độ sáng.** Nền trang `background` (#faf9f5)
ấm hơn trắng; card `surface-overlay` (#fff) *sáng hơn* nền — ngược với thói quen thường
thấy. Ở dark mode thì đảo lại: nền #141413, card #1f1e1d sáng hơn. Luôn lấy bề mặt từ
thang `background → surface-2 → surface-3 → surface-overlay`, đừng tự pha `bg-black/5`.

**Serif cho tiêu đề, sans cho phần còn lại.** Đây là chữ ký thị giác rõ nhất của hệ
thống. `font-serif` + weight nhẹ (`font-light`/`font-normal`) cho h1–h3 và các con số
lớn (giá tiền, số liệu). Sans cho body, nhãn, nút, bảng. Không bao giờ serif cho nút.

**Khoảng trắng rộng hơn bạn nghĩ.** Padding card 24–32px, khoảng cách giữa các nhóm
form 24px, giữa các section 64–96px. Nếu thấy chật, tăng khoảng trắng trước khi giảm
cỡ chữ.

## 2. Bảng tra nhanh

| Việc | Dùng |
|---|---|
| Nền trang | `bg-background text-foreground` |
| Card / panel | `bg-surface-overlay border border-border rounded-xl p-6` |
| Vùng chìm (nền phụ) | `bg-surface-2` |
| Chữ phụ | `text-muted-foreground` |
| Đường kẻ | `border-border` (mảnh) · `border-border-strong` (rõ) |
| Nút chính | `<Button>` (mặc định) |
| Nút nhấn mạnh thương mại | `<Button variant="clay">` |
| Nút phụ | `<Button variant="secondary">` |
| Nút viền | `<Button variant="outline">` |
| Focus ring | `focus-ring` (utility có sẵn) |
| Tiêu đề trang | `text-headline-2 font-serif font-normal` |
| Body | mặc định 15px; `text-body-2` (17px) cho đoạn văn dài |
| Chú thích | `text-caption text-muted-foreground` |

Chi tiết từng token: `references/tokens.md`.
Công thức từng component (class string chính xác): `references/components.md`.
Layout trang, motion, dark mode, a11y: `references/patterns.md`.
Checklist tự soát trước khi báo xong: `references/checklist.md`.

## 3. Quy trình khi được giao một màn hình

1. Đọc `references/patterns.md` xem đã có layout pattern cho loại màn hình đó chưa
   (auth split, settings với sidebar, list + detail, form dài).
2. Dựng khung bằng token bề mặt và thang spacing — **chưa** đụng tới màu accent.
3. Lắp component từ `frontend/src/components/ui/`. Nếu thiếu, thêm vào đó theo công
   thức trong `references/components.md`, **không** viết style ad-hoc trong page.
4. Thêm đúng một điểm accent cho hành động chính của màn hình.
5. Chạy `references/checklist.md`.

## 4. Trạng thái — phần hay bị bỏ quên nhất

Mỗi màn hình có dữ liệu từ API phải xử lý đủ **năm** trạng thái: loading (skeleton, không
spinner toàn trang), empty (có icon + câu giải thích + hành động thoát), error (thông báo
tiếng Việt + nút thử lại), partial (một phần lỗi), success. Skeleton phải có đúng hình
dạng của nội dung thật, không phải một khối xám.

Lỗi từ backend là RFC 9457 problem+json với trường `code` ổn định. **Không bao giờ hiển
thị `detail` thô của backend cho người dùng** — map `code` sang câu tiếng Việt qua
`frontend/src/lib/api/problem.ts`. Chi tiết ở `references/patterns.md` §5.

## 5. Ranh giới

Terra lấy *ngôn ngữ thiết kế* từ claude.com — tỷ lệ, nhịp điệu, cách phân tầng bề mặt,
tinh thần tiết chế. Terra **không** dùng logo, tên, wordmark hay font độc quyền của
Anthropic. Font là bản thay thế mã nguồn mở (Inter + Newsreader), token mang tên riêng
của dự án, và hệ thống được mở rộng thêm những thứ claude.com không có: giá tiền, badge
giảm giá, trạng thái tồn kho, card sản phẩm.
