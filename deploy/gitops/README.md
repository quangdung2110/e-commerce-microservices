# GitOps bootstrap

Hướng dẫn triển khai, cấu hình hạ tầng và vận hành nằm trong [docs/cicd/phase-2.md](../../docs/cicd/phase-2.md).

`environments.example.json` chỉ khai báo đầu vào; các giá trị chưa biết để `null`/rỗng.
Không có domain, cluster hoặc credential giả được dùng để triển khai. Sao chép thành
`environments.json`, điền thông số thật rồi chạy công cụ bootstrap theo tài liệu.

`catalog.json` là catalog production được review riêng. Các manifest trong `deploy/k8s`
vẫn phục vụ môi trường local; không dùng trực tiếp để tạo production.
