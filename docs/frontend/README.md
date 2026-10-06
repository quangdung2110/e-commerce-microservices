# Frontend

Ứng dụng Next.js trong [`frontend/`](../../frontend/) phục vụ cả storefront cho người mua
và trang quản trị `/admin`. Quy tắc giao diện nằm trong skill Terra
(`.claude/skills/terra-ui/`).

| Tài liệu | Nội dung |
|---|---|
| [tech-stack.md](./tech-stack.md) | Công nghệ đã chọn và lý do |
| [sitemap.md](./sitemap.md) | Toàn bộ route, API nguồn, quyền truy cập, hiện trạng và khoảng trống API |
| [implementation-plan.md](./implementation-plan.md) | Lộ trình 14 bước; chi tiết bước 0–5 và 7–10 |
| [step-3-verification.md](./step-3-verification.md) | Admin Kho tối thiểu, kiểm thử offline/live và gate MFA production |
| [step-4-verification.md](./step-4-verification.md) | Search v2/PDP BFF/Review, đồng bộ backend và kiểm chứng |
| [step-5-verification.md](./step-5-verification.md) | Cart v1, delta/xóa batch, giá degrade, idempotency và kiểm chứng |
| [step-7-verification.md](./step-7-verification.md) | Đơn hàng Buyer, huỷ/trả/hoàn tiền và Stripe Payment Element tối thiểu |
| [step-8-verification.md](./step-8-verification.md) | Đơn hàng Admin, lệnh vòng đời và ghi nhận physical dispatch |
| [step-9-verification.md](./step-9-verification.md) | Đánh giá sau mua (media, sửa một lần), hộp thông báo, backend bổ sung và kiểm chứng live |
| [step-10-verification.md](./step-10-verification.md) | Admin Khuyến mãi: campaign, voucher, Rule AST builder, markdown, cấp voucher và kiểm chứng live |
| [decisions/](./decisions/README.md) | ADR của frontend |

Storefront search hiện dùng [một card/Product và cheapest eligible SKU](./product-card-search.md), total_products và PIT cursor theo Search ADR-020.
