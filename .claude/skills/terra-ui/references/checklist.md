# Terra — Checklist trước khi báo xong một màn hình

Chạy hết. Mỗi mục sai là một lần phải sửa lại sau.

## Token

- [ ] `grep -nE "#[0-9a-fA-F]{3,8}|text-white|text-black|bg-white|bg-black|bg-gray-|text-gray-|border-gray-"` trên file vừa viết → **không kết quả nào**
- [ ] Không có `bg-[...]`, `text-[#...]`, `rounded-[...]px` tự chế
- [ ] Cỡ chữ dùng thang `text-headline-*` / `text-body-*` / `text-caption`, hoặc `text-[14px]`/`text-[15px]` cho UI control
- [ ] Bo góc thuộc thang `sm/md/lg/xl/2xl/3xl`
- [ ] Không có `shadow-xl`, `shadow-2xl`, `drop-shadow`

## Thị giác

- [ ] Đúng **một** hành động chính trên màn hình; không có hai nút đặc cạnh nhau
- [ ] Tiêu đề dùng `font-serif` weight nhẹ; nút và nhãn dùng sans
- [ ] Card phân tách bằng viền, không bằng bóng
- [ ] Không còn `glass`, `glass-card`, `text-glow`, gradient trang trí, glow, neon
- [ ] Khoảng trắng đủ rộng: padding card ≥ 24px, field cách nhau 20px

## Trạng thái

- [ ] Loading là skeleton đúng hình dạng, không phải spinner giữa màn hình
- [ ] Empty state có icon + câu giải thích + hành động thoát
- [ ] Error state có câu tiếng Việt + nút thử lại
- [ ] Nút submit `disabled` + spinner khi đang gửi, chiều rộng không đổi
- [ ] Không có nhánh nào để lại màn hình trắng

## Dữ liệu và API

- [ ] Endpoint khớp với router Go thật (đọc `internal/api/router/`), không đoán theo tên
- [ ] Field name khớp DTO thật (`internal/api/dto/`), đúng `snake_case`
- [ ] Lỗi map từ `code` sang tiếng Việt qua `lib/api/problem.ts`, không hiện `detail` thô
- [ ] `errors[]` gắn vào đúng field của form
- [ ] `RATE_LIMITED` đọc `Retry-After` và khoá nút

## Dark mode

- [ ] Bật dark mode xem lại toàn màn hình: không có mảng trắng chói, không có chữ chìm
- [ ] Tint trạng thái dùng `*-surface`, không phải `bg-*/10`
- [ ] Ảnh/logo nền trắng đã được xử lý

## Responsive

- [ ] Xem ở 375px: không tràn ngang, không cuộn ngang
- [ ] Xem ở 768px và 1280px
- [ ] Bảng thành card trên mobile; dialog dùng `svh` và cuộn được

## Accessibility

- [ ] Tab qua toàn màn hình: focus ring luôn thấy, thứ tự hợp lý
- [ ] Nút icon có `aria-label`; ảnh có `alt`
- [ ] Label form thật (`htmlFor`), lỗi có `aria-invalid` + `aria-describedby`
- [ ] Banner lỗi có `role="alert"`
- [ ] Heading không nhảy bậc

## Ngôn ngữ

- [ ] Toàn bộ chữ hiển thị là tiếng Việt, có dấu, đúng chính tả
- [ ] Tiền tệ qua `formatVND()`, ngày `dd/MM/yyyy`
- [ ] Thông báo lỗi nói *phải làm gì*, không chỉ *sai cái gì*

## Cuối cùng

- [ ] `npx tsc --noEmit` sạch
- [ ] `npm run lint` sạch
