---
doc_id: frontend-adr-002
service: frontend
status: accepted
date: 2026-09-29
decision_owners:
  - Frontend team
supersedes: null
evidence:
  - frontend/src/store/useAuthStore.ts
  - frontend/src/lib/api/client.ts
  - contracts/http/auth/v1/openapi.yaml
---

# ADR-002 — Trang công khai render phía server, trang cần đăng nhập render phía client

## Status

`Accepted`

## Context

[tech-stack.md](../tech-stack.md) đề xuất SSG/ISR cho catalog và PDP, SSR cho giỏ hàng,
checkout và hồ sơ. Cách xác thực hiện tại làm SSR cho trang cần đăng nhập không khả thi:

- Access token chỉ nằm trong bộ nhớ trình duyệt (`useAuthStore`), không vào cookie hay
  localStorage, để script bên thứ ba không đọc được.
- Refresh token là cookie `HttpOnly; SameSite=Strict` với `Path=/api/v1/auth/sessions`.
  Request tới trang (`/cart`, `/orders`, …) không mang cookie này, nên server của Next
  không có gì để xác thực người dùng.
- Mỗi refresh token chỉ dùng được một lần. Nếu server của Next tự refresh thay trình
  duyệt, nó sẽ làm cookie của trình duyệt mất hiệu lực.

## Decision

- **Route công khai** (`/`, `/search`, `/c/[slug]`, `/products/[slug]`,
  `/products/[slug]/reviews`) render phía server. Dữ liệu lấy bằng
  `src/lib/api/server.ts` gọi thẳng `API_UPSTREAM`, không gắn token. Bước 4 cache cây
  danh mục 60 giây; Search/facet/PDP/Review dùng SSR `no-store` vì projection thay đổi,
  BFF có thể degrade và Review có URL ký ngắn hạn. ISR chỉ áp dụng khi tương thích
  chính sách cache upstream. Phần phụ thuộc người dùng trên các trang này (nút
  thêm vào giỏ, chuông thông báo) là Client Component.
- **Route cần đăng nhập và toàn bộ `/admin`** render phía client: server trả khung và
  skeleton, dữ liệu lấy bằng React Query qua client `api` sau khi `AuthProvider` đã khôi
  phục phiên.
- Không dùng middleware/proxy của Next để chặn route theo đăng nhập, vì server không
  biết trạng thái phiên.

## Alternatives considered

**Lưu access token trong cookie đọc được ở server.** Cho phép SSR trang cần đăng nhập
nhưng làm token lộ qua mọi request tới trang và cần thêm biện pháp chống CSRF. Không chọn
vì đi ngược thiết kế Auth hiện tại.

**BFF trong Next giữ phiên ở server.** Next giữ refresh token và cấp session cookie riêng
cho trình duyệt. Làm được SSR đầy đủ nhưng biến Next thành một thành phần xác thực cần
lưu trạng thái và vận hành riêng. Không chọn ở giai đoạn này; có thể xem xét lại nếu cần
SSR cho trang cá nhân.

## Consequences

- Trang công khai có HTML đầy đủ cho SEO; chỉ dữ liệu phù hợp mới cache ISR.
- Trang cần đăng nhập có một nhịp skeleton khi tải lại trang, trong lúc refresh phiên.
  Mọi màn phải xử lý trạng thái `loading` của phiên, không coi nó là chưa đăng nhập.
- Hàm gọi API tách hai loại: `server.ts` (công khai, không token) và `client.ts` (có
  token, refresh). Không được gọi API cần đăng nhập từ Server Component.

## Migration impact

- Code: thêm `src/lib/api/server.ts` ở bước 0; trang chủ, danh mục, PDP chuyển sang
  Server Component ở bước 4.
- API/contract: không đổi.
- Tài liệu: [tech-stack.md](../tech-stack.md) ghi chú quyết định này thay cho mục SSR của
  giỏ hàng, checkout và hồ sơ.

## Verification

- View source của `/products/[slug]` chứa tên và giá sản phẩm.
- Tải lại `/orders` khi đang đăng nhập: thấy skeleton rồi dữ liệu, không bị đẩy về
  `/login`.
- Không có Server Component nào import `src/lib/api/client.ts`.
