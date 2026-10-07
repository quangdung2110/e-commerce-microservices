# Terra — Công thức component

Mọi component ở đây đã tồn tại trong `frontend/src/components/ui/`. **Import và dùng,
đừng viết lại.** Phần class string chép ở đây để bạn biết chuẩn trông thế nào khi cần
thêm một component mới cho khớp.

---

## Button

`frontend/src/components/ui/button.tsx`

### Variant — chọn cái nào?

| Variant | Trông thế nào | Dùng khi | Mỗi màn hình có bao nhiêu |
|---|---|---|---|
| `default` | nền mực đặc, chữ giấy | hành động chính của form/màn hình (Đăng nhập, Lưu) | tối đa 1 |
| `clay` | nền đất cam | hành động thương mại có tính chuyển đổi (Thêm vào giỏ, Mua ngay, Thanh toán) | tối đa 1 |
| `secondary` | nền cát nhạt, không viền | hành động phụ ngang hàng (Huỷ, Quay lại) | thoải mái |
| `outline` | nền trong, viền `border-strong` | hành động phụ cần thấy rõ ranh giới (Đổi ảnh, Thêm địa chỉ) | thoải mái |
| `ghost` | trong suốt, hover đổi nền | icon button, item menu, hành động trong bảng | thoải mái |
| `destructive` | chữ đỏ trên nền tint | Xoá, Thu hồi phiên | tối đa 1 |
| `link` | chữ gạch chân khi hover | điều hướng nội dòng | thoải mái |

**Không dùng `default` và `clay` cạnh nhau** — hai nút đặc cạnh nhau triệt tiêu thứ bậc.
Cặp đúng là `default` + `secondary`, hoặc `clay` + `outline`.

### Size

| Size | Class | Dùng cho |
|---|---|---|
| `sm` | `h-9 px-4 text-[14px]` | trong bảng, trong card nhỏ, toolbar |
| `default` | `h-11 px-5 text-[15px]` | mặc định |
| `lg` | `h-12 px-6 text-[17px]` | CTA của trang auth, hero |
| `icon` | `h-11 w-11 p-0` | chỉ icon (bắt buộc có `aria-label`) |

### Nền tảng chung của mọi variant

```
inline-flex items-center justify-center gap-2 whitespace-nowrap
rounded-lg font-medium select-none
transition-[color,background-color,border-color,transform] duration-100 ease-out-soft
active:scale-[.98]
focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring
disabled:pointer-events-none disabled:opacity-50
[&_svg]:size-4 [&_svg]:shrink-0
```

Nút đang chờ phải dùng `disabled` + `<LoaderCircle className="animate-spin" />` và **giữ
nguyên chiều rộng** (đừng đổi label thành chữ ngắn hơn khiến nút nhảy).

---

## Input

`frontend/src/components/ui/input.tsx`

```
h-11 w-full rounded-lg
border border-border-strong bg-surface-overlay
px-3.5 text-[15px] text-foreground
placeholder:text-muted-foreground
transition-[border-color,box-shadow] duration-200 ease-out-soft
hover:border-sand-400 dark:hover:border-sand-600
focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring
focus-visible:border-transparent
disabled:bg-surface-3 disabled:text-muted-foreground disabled:cursor-not-allowed
aria-invalid:border-danger aria-invalid:outline-danger
```

Input **có nền riêng** (`surface-overlay`) sáng hơn nền trang — đó là cách người dùng
nhận ra nó là ô nhập. Không dùng input nền trong suốt.

Ô mật khẩu luôn có nút hiện/ẩn (`ghost` + `icon` size `sm`, đặt absolute bên phải,
`aria-label` là "Hiện mật khẩu"/"Ẩn mật khẩu").

---

## Field (nhãn + input + lỗi)

Đơn vị nhỏ nhất của form. Cấu trúc bắt buộc:

```tsx
<FormItem className="space-y-2">
  <FormLabel className="text-[14px] font-medium text-foreground">Email</FormLabel>
  <FormControl><Input ... /></FormControl>
  <FormDescription className="text-caption text-muted-foreground">…</FormDescription>
  <FormMessage />   {/* text-caption text-danger */}
</FormItem>
```

Quy tắc: nhãn **luôn** hiện (không dùng placeholder thay nhãn), lỗi hiện **dưới** ô,
`aria-invalid` được set khi có lỗi, và thông báo lỗi nói *phải làm gì* chứ không chỉ
*sai cái gì* — "Mật khẩu cần ít nhất 12 ký tự", không phải "Mật khẩu không hợp lệ".

---

## Card

```
bg-surface-overlay border border-border rounded-xl p-6
```

Card **không có bóng ở trạng thái tĩnh**. Card bấm được thì thêm:
`transition-colors duration-200 hover:border-border-strong hover:bg-surface-2`.

Cấu trúc: `CardHeader` (`space-y-1.5 pb-4`) → `CardTitle` (`text-headline-6 font-medium`)
→ `CardDescription` (`text-caption text-muted-foreground`) → `CardContent` → `CardFooter`
(`pt-4 border-t border-border`).

---

## Alert / banner thông báo

```
flex gap-3 rounded-lg border p-4 text-[14px]
```

cộng bộ ba màu theo trạng thái: `border-danger-border bg-danger-surface text-danger`
(tương tự cho `success`, `warning`, `info`). Icon `size-4 mt-0.5 shrink-0`.

Banner lỗi form đặt **trên đầu form**. Variant `danger` dùng `role="alert"` +
`aria-live="assertive"`; các variant còn lại dùng `role="status"` + `aria-live="polite"`.

---

## Badge

```
inline-flex items-center rounded-md px-2 py-0.5 text-caption font-medium
```

- Trung tính: `bg-surface-3 text-foreground-secondary`
- Mặc định/hiện hành: `bg-clay/12 text-clay-strong border border-clay/20`
- Trạng thái: dùng bộ ba `*-surface` / `*` / `*-border`

Badge không bao giờ dùng nền đặc màu mạnh — nó là nhãn, không phải nút.

---

## Switch / Checkbox

Switch: track `h-6 w-11 rounded-full bg-surface-3`, khi bật `bg-primary`; thumb
`size-5 rounded-full bg-surface-overlay shadow-sm`, transition `duration-200 ease-out-soft`.

Checkbox: `size-4 rounded-sm border border-border-strong`, khi tick `bg-primary
border-primary text-primary-foreground`.

Cả hai **bắt buộc** đi kèm `<Label>` bấm được (`htmlFor`), và hàng chứa chúng dùng
`flex items-start justify-between gap-4` với mô tả ở `text-caption text-muted-foreground`.

---

## Dropdown / Popover / Dialog

```
bg-surface-overlay border border-border rounded-xl shadow-md   /* dropdown, popover */
bg-surface-overlay border border-border rounded-2xl shadow-lg  /* dialog */
```

Item của menu: `rounded-md px-2.5 py-2 text-[14px] hover:bg-accent focus:bg-accent`.
Overlay của dialog: `bg-sand-950/40 backdrop-blur-[2px]`.

---

## Skeleton

```
animate-pulse rounded-md bg-surface-3
```

Skeleton phải mô phỏng **hình dạng thật**: một dòng tiêu đề rộng `w-48 h-7`, ba dòng
body `h-4` với dòng cuối `w-2/3`, avatar `size-16 rounded-full`. Một khối xám to tướng
không phải skeleton.

---

## Empty state

```tsx
<div className="flex flex-col items-center gap-3 rounded-xl border border-dashed
                border-border bg-surface-2 px-6 py-12 text-center">
  <Icon className="size-8 text-muted-foreground" />
  <p className="text-[15px] font-medium">Chưa có địa chỉ nào</p>
  <p className="max-w-prose-narrow text-caption text-muted-foreground">…</p>
  <Button variant="outline" size="sm">Thêm địa chỉ</Button>
</div>
```

Viền **đứt** là dấu hiệu quy ước cho "chỗ này đang trống", đừng dùng viền đứt cho thứ khác.

---

## Avatar

`size-10 rounded-full bg-surface-3 text-foreground-secondary` với fallback là chữ cái đầu
của `display_name`, `text-[14px] font-medium`. Không dùng ảnh mặc định chung chung.

---

## Bảng

`thead`: `bg-surface-2 text-caption font-medium uppercase tracking-wide text-muted-foreground`.
`tbody tr`: `border-b border-border last:border-0 hover:bg-surface-2`.
Ô: `px-4 py-3 text-[14px]`. Cột số và tiền: `text-right tabular-nums font-mono`.

---

## Tiền tệ

Luôn qua `formatVND()` trong `frontend/src/lib/format.ts`. Hiển thị `tabular-nums` để
các con số thẳng cột. Giá chính dùng `text-headline-4 font-serif font-normal`; giá gạch
bỏ dùng `text-[15px] text-muted-foreground line-through`.

---

## PasswordInput

`frontend/src/components/ui/password-input.tsx`. Luôn dùng cái này thay cho
`<Input type="password">` để người dùng kiểm tra được thứ họ vừa gõ. Nút hiện/ẩn
có `tabIndex={-1}` — nó không nên là một trạm dừng khi tab qua form.

## NativeSelect

`frontend/src/components/ui/native-select.tsx`. Dùng cho **mọi select trong form
dữ liệu** (giới tính, tỉnh/quận/phường, số lượng). Nó dựng trên `<select>` gốc,
nên có sẵn bàn phím, gõ chữ để nhảy tới lựa chọn, và bánh xe chọn gốc trên mobile
— những thứ một listbox tự dựng phải làm lại và thường làm thiếu.

Chỉ dùng `Select` của Base UI khi thực sự cần thứ mà `<option>` không làm được:
ảnh trong lựa chọn, nhóm nhiều cấp, hoặc nội dung tuỳ biến.

## Alert

`frontend/src/components/ui/alert.tsx`. Dùng cho lỗi cấp form (đặt **trên đầu**
form) và ghi chú ngữ cảnh. Nó tự đặt `role="alert"` + `aria-live="assertive"` cho
variant `danger`, và `status`/`polite` cho phần còn lại.

Thông báo ngắn *sau* một hành động thành công thì dùng toast, không dùng Alert:
Alert chiếm chỗ trong layout và đẩy nội dung xuống.

## Trạng thái chờ trên nút

Nút đang gửi phải `disabled` và hiện `<LoaderCircle className="animate-spin" />`
kèm nhãn ở thì tiếp diễn ("Đang lưu", "Đang đăng nhập"). Nhãn phải **dài xấp xỉ**
nhãn gốc để nút không co lại giữa chừng.

> Icon dùng tên chuẩn của lucide-react v1: `LoaderCircle`, `TriangleAlert`,
> `CircleAlert`, `CircleCheck`. Các tên cũ (`Loader2`, `AlertTriangle`,
> `AlertCircle`, `CheckCircle`, `CheckCircle2`) là alias đã bị gỡ ở v1 — dùng
> chúng là lỗi biên dịch.

---

## Dialog và ConfirmDialog

Primitive: `frontend/src/components/ui/dialog.tsx`. Composition xác nhận:
`frontend/src/components/admin/confirm-dialog.tsx`.

- Overlay: `bg-sand-950/40 backdrop-blur-[2px]`.
- Popup: `max-h-[90svh] overflow-y-auto rounded-2xl border border-border
  bg-surface-overlay p-6 shadow-lg`.
- Tiêu đề dùng `text-headline-5 font-serif font-normal`; mô tả dùng
  `text-[15px] text-muted-foreground`.
- Footer có viền trên, mobile xếp nút dọc đảo thứ tự để hành động chính nằm trên vùng
  ngón tay; từ `sm` xếp ngang và ghim phải.
- `ConfirmDialog` mặc định bắt buộc lý do. Hành động không hoàn tác truyền thêm
  `expectedText` để người dùng gõ lại tên/mã; lỗi backend đi qua `ErrorState`.

Base UI giữ focus trong modal, khoá scroll và trả focus về trigger. Luôn giữ nút đóng
trong popup để người dùng touch screen reader thoát được.

## Sheet

`frontend/src/components/ui/sheet.tsx`. Cùng accessibility primitive với Dialog nhưng
popup bám cạnh trái/phải:

```
absolute inset-y-0 w-[min(88vw,320px)]
border-border bg-surface-overlay p-6 shadow-lg
transition-transform duration-300 ease-out-quart
```

Dùng cho điều hướng admin mobile và panel chi tiết. Sheet điều hướng rộng tối đa 320px;
không dùng Sheet thay dialog xác nhận.

## Tabs

`frontend/src/components/ui/tabs.tsx`.

- List: hàng ngang cuộn được, `min-h-11 border-b border-border`.
- Trigger: vùng bấm tối thiểu 44px; tab active có chữ foreground và thanh clay 2px ở
  đáy. Không dùng nền đặc cho tab active.
- Base UI xử lý arrow key, `aria-controls` và liên kết tab–panel.

## Textarea

`frontend/src/components/ui/textarea.tsx`. Dùng cùng bề mặt, viền, focus và trạng thái
lỗi với Input; mặc định `min-h-28 resize-y`, padding `px-3.5 py-3`.

## Component ghép cho admin

Các composition nằm trong `frontend/src/components/admin/`:

- `PageHeader`: h1 serif + mô tả giới hạn chiều rộng + một vùng hành động chính.
- `FilterBar`: bề mặt chìm `surface-2`, field co từ một cột lên nhiều cột; nút xoá lọc
  là ghost.
- `DataTable`: TanStack Table, thead `surface-2`, row hover chỉ đổi nền; dưới `md` render
  card thay vì cuộn ngang. Có skeleton/rỗng/lỗi/partial/success và pager cursor.
- `StatusBadge`: lấy cả chữ lẫn tone từ `lib/labels/`, không map lại trong page.
- `IdChip`: mono, rút gọn mã dài, sao chép toàn bộ giá trị và báo trạng thái qua live
  region.
- `MoneyInput`: chỉ nhận số nguyên VND, hiện dấu tách nghìn và suffix `VND`.
- `DateTimeInput`: nhập giờ Việt Nam bằng `datetime-local`, phát RFC 3339.
- `FormSection`: Card cho một chủ đề form; nội dung `space-y-5`.
- `StickySaveBar`: chỉ hiện khi dirty, cảnh báo `beforeunload`, hành động lưu ghim phải.
- `OperationStatus`: dùng `pollUntil`, hiển thị tiến trình bằng token info và phân biệt
  hết thời gian theo dõi với lệnh thất bại.
- `EmptyState` và `ErrorState`: primitive dùng chung ở `components/ui/`.

## Component đánh giá và thông báo (người mua)

Nằm trong `frontend/src/components/reviews/` và `components/notifications/`.

- `RatingInput` / `RatingStars` (`rating-stars.tsx`): chọn điểm là `radiogroup` với năm
  `role="radio"`; một điểm dừng Tab, mũi tên/Home/End đổi giá trị, mỗi nút `size-11`.
  Sao chọn là `fill-foreground text-foreground`, sao trống `text-border-strong`; luôn có
  nhãn chữ ("4/5 · Tốt") cạnh sao, màu/hình không là kênh duy nhất. `RatingStars` chỉ
  đọc, thông tin ở `aria-label="4 trên 5 sao"`.
- `ProductSnapshot`: ô ảnh `size-20 rounded-xl bg-surface-2`, tên `line-clamp-2`, biến thể
  `text-caption`. Thiếu ảnh/tên (snapshot cũ) hiện biểu tượng và "Sản phẩm đã mua".
- `MediaUploader`: nút outline "Thêm ảnh hoặc video", lưới `grid-cols-2 sm:grid-cols-3`
  ô vuông `rounded-xl border`, lớp phủ tiến độ `bg-background/70`, lỗi `bg-danger-surface`
  kèm nút "Thử lại"; nút gỡ `size-11`. Giới hạn hiện thành một dòng `text-caption`.
- `NotificationBell`: giống nút giỏ hàng (ghost icon + `Badge variant="clay"` `text-micro`),
  `aria-label` có số chưa đọc, `99+` khi quá 99; chỉ hiện khi đã đăng nhập.
- Hàng thông báo: `li` trong khung `divide-y rounded-xl border`, chưa đọc có nền `surface-2`
  và chấm `bg-clay` (kèm `sr-only` "Chưa đọc:"); hành động là nút icon `size-11` có
  `aria-label` và `title`.
