# Terra — Token reference

Nguồn sự thật của implementation là `frontend/src/app/globals.css`. File này giải thích
*ý nghĩa* của từng token và bạn phải chọn cái nào.

---

## 1. Thang màu gốc (raw ramp)

Không dùng trực tiếp trong component. Chỉ dùng để định nghĩa semantic token.

### `--sand-*` — thang xám ấm

Đây là điểm khác biệt lớn nhất so với Tailwind mặc định: thang xám của Terra **ám vàng**,
không trung tính. Nền sáng là giấy, nền tối là mực nâu, không phải đen.

| Bậc | Hex | Bậc | Hex |
|---|---|---|---|
| `000` | `#ffffff` | `500` | `#87867f` |
| `050` | `#faf9f5` | `550` | `#73726c` |
| `100` | `#f5f4ed` | `600` | `#5e5d59` |
| `150` | `#f0eee6` | `650` | `#4d4c48` |
| `200` | `#e8e6dc` | `700` | `#3d3d3a` |
| `250` | `#dedcd1` | `750` | `#30302e` |
| `300` | `#d1cfc5` | `800` | `#262624` |
| `350` | `#c2c0b6` | `850` | `#1f1e1d` |
| `400` | `#b0aea5` | `900` | `#1a1918` |
| `450` | `#9c9a92` | `950` | `#141413` |

### `--clay-*` — accent thương hiệu

| Token | Hex | Dùng khi |
|---|---|---|
| `--clay-500` | `#d97757` | accent trên nền sáng, icon, highlight |
| `--clay-600` | `#c46849` | nền nút clay, focus ring |
| `--clay-700` | `#c6613f` | trạng thái hover/active của nút clay |

### Hue phụ (dùng rất tiết chế, cho minh hoạ và trạng thái)

`--sage #629987` · `--olive #788c5d` · `--sky #6a9bcc` · `--plum #827dbd`
`--berry #c46686` · `--oat #e3dacc` · `--mist #bcd1ca` · `--blush #ebcece` · `--heather #cbcadb`

---

## 2. Token ngữ nghĩa — cái bạn thực sự viết trong code

### Bề mặt

Xếp theo thứ tự "chìm → nổi". Ở light mode bề mặt càng nổi càng **sáng**; ở dark mode
càng nổi càng **sáng hơn nền**. Luôn đi theo thang này, đừng tự pha `bg-black/5`.

| Class | Light | Dark | Dùng cho |
|---|---|---|---|
| `bg-background` | `#faf9f5` | `#141413` | nền trang |
| `bg-surface-2` | `#f5f4ed` | `#1a1918` | vùng chìm: sidebar, thead, code block |
| `bg-surface-3` | `#f0eee6` | `#262624` | chìm sâu hơn: input disabled, track |
| `bg-surface-overlay` | `#ffffff` | `#1f1e1d` | card, popover, dialog, dropdown |
| `bg-card` / `bg-popover` | = overlay | = overlay | alias cho shadcn |

### Chữ

| Class | Light | Dark | Dùng cho |
|---|---|---|---|
| `text-foreground` | `#141413` | `#faf9f5` | chữ chính, tiêu đề |
| `text-foreground-secondary` | `#30302e` | `#b0aea5` | body dài, nhãn nổi bật |
| `text-muted-foreground` | `#5e5d59` | `#87867f` | chú thích, placeholder, meta |
| `text-on-accent` | `#ffffff` | `#ffffff` | chữ trên nền clay/destructive |

`text-muted-foreground` trên `bg-background` đạt ~5.9:1 (light) và ~4.6:1 (dark) — an toàn
cho chữ thường. Không dùng nó cho chữ dưới 12px.

### Đường kẻ

| Class | Light | Dark | Dùng cho |
|---|---|---|---|
| `border-border` | `#e8e6dc` | `#30302e` | mặc định: card, divider, bảng |
| `border-border-strong` | `#d1cfc5` | `#3d3d3a` | input, nút outline, thứ cần thấy rõ |

### Hành động

| Class | Light | Dark | Dùng cho |
|---|---|---|---|
| `bg-primary` `text-primary-foreground` | `#141413` / `#faf9f5` | `#faf9f5` / `#141413` | nút chính (mặc định) |
| `bg-clay` `text-on-accent` | `#d97757` | `#c46849` | nút thương mại: Mua, Thêm vào giỏ |
| `bg-clay-press` | `#c6613f` | `#c6613f` | hover/active của clay |
| `bg-secondary` `text-secondary-foreground` | `#e8e6dc` / `#4d4c48` | `#30302e` / `#faf9f5` | nút phụ |
| `bg-accent` | `#f0eee6` | `#262624` | nền hover của item danh sách/menu |
| `ring-ring` | `#c46849` | `#c46849` | focus ring, cả hai theme |

> `--accent` ở đây là **nền hover** (quy ước của shadcn), KHÔNG phải màu thương hiệu.
> Màu thương hiệu là `clay`. Đừng nhầm hai cái.

### Trạng thái

Mỗi trạng thái có bộ ba: `-*` (chữ/icon), `-*-surface` (nền tint), `-*-border` (viền).

| Nhóm | Class | Dùng cho |
|---|---|---|
| Thành công | `text-success` `bg-success-surface` `border-success-border` | đã lưu, còn hàng |
| Cảnh báo | `text-warning` `bg-warning-surface` `border-warning-border` | sắp hết hàng, giá đổi |
| Lỗi | `text-danger` `bg-danger-surface` `border-danger-border` | lỗi form, hết hàng |
| Thông tin | `text-info` `bg-info-surface` `border-info-border` | ghi chú trung tính |

`bg-destructive` / `text-destructive` là alias shadcn của nhóm lỗi.

---

## 3. Chữ

**Sans `font-sans` = Inter** (biến thiên 100–900) cho toàn bộ UI.
**Serif `font-serif` = Newsreader** (biến thiên 200–800) cho tiêu đề và số liệu lớn.
**Mono `font-mono` = Geist Mono** cho mã, SKU, order id.

Serif chỉ dùng ở weight nhẹ: `font-light` (300) hoặc `font-normal` (400). Serif đậm
trông nặng nề và phá vỡ tinh thần của hệ thống. **Không bao giờ serif cho nút, nhãn
form, hay chữ dưới 18px.**

| Class | Cỡ | Line-height | Dùng cho |
|---|---|---|---|
| `text-display-1` | 42→72px | 1.1 | hero |
| `text-display-2` | 36→64px | 1.1 | hero phụ |
| `text-headline-1` | 34→52px | 1.2 | tiêu đề trang marketing |
| `text-headline-2` | 30→44px | 1.2 | tiêu đề trang app |
| `text-headline-3` | 28→36px | 1.3 | tiêu đề section |
| `text-headline-4` | 23→32px | 1.1 | tiêu đề card lớn, giá |
| `text-headline-5` | 20→25px | 1.2 | tiêu đề card |
| `text-headline-6` | 16→19px | 1.2 | tiêu đề nhỏ, nhãn nhóm |
| `text-body-1` | 19→20px | 1.6 | đoạn mở đầu |
| `text-body-2` | 17px | 1.6 | đoạn văn dài |
| `text-body-3` | 15px | 1.6 | **mặc định của UI app** |
| `text-caption` | 12px | 1.6 | chú thích, helper text |
| `text-micro` | 10px | 1.6 | nhãn siêu nhỏ, viết hoa |

Các bậc `display-*` và `headline-*` là `clamp()` — tự co theo viewport, **không** cần
viết `md:text-...` cho chúng.

Weight: `font-light` 300 · `font-normal` 400 · `font-medium` 500 · `font-semibold` 600.
Nút dùng 500. Nhãn form dùng 500. Body dùng 400.

Bề rộng dòng đọc: `max-w-prose-narrow` (60ch) cho đoạn văn, `max-w-prose-title` (45ch)
cho tiêu đề nhiều dòng. Chữ chạy hết chiều ngang màn hình là lỗi.

---

## 4. Bo góc

| Class | px | Dùng cho |
|---|---|---|
| `rounded-sm` | 4 | badge nhỏ, checkbox |
| `rounded-md` | 6 | input phụ, tag |
| `rounded-lg` | 8 | **mặc định**: nút, input, select |
| `rounded-xl` | 12 | card, panel |
| `rounded-2xl` | 16 | card lớn, dialog |
| `rounded-3xl` | 24 | section, hero container |
| `rounded-full` | — | chỉ avatar, chấm trạng thái, switch |

---

## 5. Khoảng cách

Dùng thang Tailwind mặc định, giới hạn ở các bậc sau (khớp thang gốc 4px):
`0.5(2) 1(4) 1.5(6) 2(8) 3(12) 4(16) 5(20) 6(24) 8(32) 10(40) 12(48) 14(56) 16(64) 20(80) 24(96) 32(128)`

Nhịp điệu chuẩn:

- Bên trong control (nút, input): `px-5 h-11` (44px cao) hoặc `px-4 h-9` (36px, cỡ nhỏ)
- Padding card: `p-6` (24px), card lớn `p-8`
- Giữa các field trong form: `space-y-5`
- Giữa các nhóm/section trong một trang: `space-y-8` hoặc `space-y-12`
- Giữa các section lớn của landing: `py-16 md:py-24`

Chiều cao control chuẩn: **44px** (`h-11`) cho control chính, **36px** (`h-9`) cho compact.
Đây là chiều cao thật của bản gốc, và 44px cũng đúng ngưỡng touch target tối thiểu.

---

## 6. Bóng và độ nổi

| Class | Giá trị (light) | Dùng cho |
|---|---|---|
| `shadow-sm` | `0 2px 8px rgba(0,0,0,.04)` | card nổi nhẹ khi hover |
| `shadow-md` | `0 4px 20px rgba(0,0,0,.04)` | dropdown, popover, tooltip |
| `shadow-lg` | `0 4px 16px rgba(0,0,0,.10)` | dialog, drawer |

Ở dark mode giá trị đậm hơn (đã xử lý trong token). **Card tĩnh không có bóng** — nó
được phân tách bằng `border border-border`. Nếu bạn thấy mình cần `shadow-xl`, thiết kế
đang sai hướng.

---

## 7. Chuyển động

| Class | Giá trị |
|---|---|
| `ease-out-quart` | `cubic-bezier(.165,.84,.44,1)` |
| `ease-out-soft` | `cubic-bezier(.25,1,.5,1)` |
| `ease-in-out-smooth` | `cubic-bezier(.645,.045,.355,1)` |

Thời lượng: `duration-100` cho phản hồi tức thì (nút nhấn), `duration-200` cho hover và
đổi màu, `duration-300` cho panel trượt/mở. Không có gì trong UI app được phép dài hơn
400ms.

Nút nhấn xuống dùng `active:scale-[.98]` — nhỏ, gần như không nhận ra, nhưng tạo cảm
giác vật lý. Không dùng scale cho hover.

---

## 8. Container

| Class | Rộng | Dùng cho |
|---|---|---|
| `max-w-narrow` | 660px | form một cột, trang auth, bài viết |
| `max-w-small` | 960px | trang settings, profile |
| `max-w-medium` | 1192px | danh sách sản phẩm, dashboard |
| `max-w-main` | 1440px | tối đa của toàn site |

Lề trang: `px-6 md:px-8` cho app, `px-8 lg:px-16` cho marketing.

---

## 9. Utility riêng của Terra

| Utility | Tác dụng |
|---|---|
| `focus-ring` | Focus ring chuẩn khi `:focus-visible`. Dùng cho mọi thứ interactive không phải `<Button>`/`<Input>` (chúng đã có sẵn). |
| `surface-card` | Rút gọn của `bg-surface-overlay border border-border rounded-xl` |
| `hairline` | Đường kẻ 1px `bg-border`, mảnh hơn `<Separator>` |

`glass`, `glass-card`, `text-glow` là **deprecated**. Chúng còn tồn tại chỉ để các trang
cũ (cart, products) không vỡ layout, và đã được ánh xạ về bề mặt phẳng của Terra. Không
dùng chúng trong code mới; gặp thì thay bằng `surface-card`.
