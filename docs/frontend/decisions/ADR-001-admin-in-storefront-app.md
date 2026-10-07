---
doc_id: frontend-adr-001
service: frontend
status: accepted
date: 2026-09-29
decision_owners:
  - Frontend team
supersedes: null
evidence:
  - frontend/next.config.ts
  - contracts/http/auth/v1/openapi.yaml
  - docs/gateway/runtime-contract.md
---

# ADR-001 — Giao diện quản trị nằm chung ứng dụng Next.js với storefront

## Status

`Accepted`

## Context

Dự án cần hai giao diện: storefront cho người mua và trang quản trị cho `ADMIN`.
[tech-stack.md](../tech-stack.md) để ngỏ khả năng tách nhiều ứng dụng trong một monorepo
(Turborepo/Nx). Hiện `frontend/` là một ứng dụng Next.js duy nhất, đã có design system
Terra, lớp gọi API và luồng xác thực.

Ba ràng buộc quyết định cách tổ chức:

- Refresh token nằm trong cookie `auth_refresh` với `HttpOnly; Secure; SameSite=Strict`
  và `Path=/api/v1/auth/sessions`. Trình duyệt chỉ gửi cookie này cho đúng origin đã
  nhận nó.
- `next.config.ts` rewrite `/api/**` về gateway để trình duyệt chỉ nói chuyện với origin
  của Next, không cần CORS.
- Mọi API admin có trong `contracts/` (Product, Promotion, Order, Review, Search, Auth)
  dùng chung role `ADMIN` và đi qua cùng gateway với API của người mua.

## Decision

Trang quản trị là thư mục `frontend/src/app/admin/` (URL `/admin/**`) trong cùng ứng
dụng. Nó có layout riêng (admin shell), không kế thừa header/footer của `(site)`, và được
bọc bởi `RequireRole role="ADMIN"`. Admin dùng chung Terra, `src/components/ui/`, lớp
`src/lib/api/` và phiên đăng nhập với storefront.

Quyết định không bao gồm việc cấp quyền: `RequireRole` chỉ là hàng rào trải nghiệm, quyền
thật do gateway và từng service kiểm tra.

## Alternatives considered

**Ứng dụng admin riêng trong monorepo.** Tách deploy, tách bundle, có thể đặt admin ở
domain nội bộ. Nhưng phải thêm workspace tooling, tách design system thành package, và
admin ở origin khác thì cookie `SameSite=Strict` không dùng chung được: admin phải đăng
nhập riêng hoặc Auth phải đổi thuộc tính cookie. Không chọn vì chi phí lớn so với quy mô
hiện tại.

**Ứng dụng admin riêng, cùng origin qua reverse proxy theo path.** Giữ được cookie nhưng
cần thêm một tầng định tuyến và vẫn phải chia sẻ design system giữa hai codebase. Không
chọn vì không mang lại lợi ích rõ so với một ứng dụng.

## Consequences

- Một lần đăng nhập dùng được cho cả hai giao diện; component và helper API không bị
  nhân đôi.
- Code admin nằm trong bundle chung. App Router tách chunk theo route nên người mua không
  tải code admin, nhưng build và deploy của hai phần gắn với nhau.
- Admin không được đặt sau một lớp mạng riêng (VPN, IP allowlist) nếu không cấu hình thêm
  ở gateway cho prefix `/admin`.
- Nếu sau này cần tách, ranh giới đã sẵn: `app/admin/` và `components/admin/` chỉ phụ
  thuộc vào `components/ui/` và `lib/`.

## Migration impact

- Code: thêm `src/app/admin/`, `src/components/admin/`, `RequireRole`.
- API/contract: không đổi.
- Deploy: không đổi; vẫn một image `frontend`.
- Tài liệu: [sitemap.md](../sitemap.md) và [implementation-plan.md](../implementation-plan.md)
  mô tả route và khung admin.

## Verification

- Tài khoản `ADMIN` đăng nhập ở `/login` rồi vào `/admin` không phải đăng nhập lại.
- Tài khoản không có `ADMIN` vào `/admin` thấy trạng thái 403.
- Bundle của route `(site)` không chứa module từ `components/admin/` (kiểm tra bằng
  output build).
