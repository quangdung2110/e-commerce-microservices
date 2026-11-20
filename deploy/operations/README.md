# Operations bootstrap

Xem [hướng dẫn đợt 3](../../docs/cicd/phase-3.md) và
[kết quả kiểm chứng](../../docs/cicd/phase-3-verification.md).

`policy.example.json` không có baseline hoặc target giả để chạy production.
Copy thành `policy.json`, đo/review các giá trị trước khi bật `OPERATIONS_ENABLED`.
Canary/SLO nằm trong profile `operations` của `deploy/gitops/environments.json` để
được đóng băng và kiểm tra cùng candidate. Các cờ mặc định đều chưa bật.
