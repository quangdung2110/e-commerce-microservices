# Xác minh khắc phục P1 ở ba endpoint hạ tầng

Ngày 06/11/2026, múi giờ UTC+7. Phạm vi xác minh là Kubernetes local `kind-ecommerce-mesh` với Istio ambient và CNI `kindnetd:v20260820-69b56db7`. Phát hiện F2 được đóng trong phạm vi này. Docker Compose chạy độc lập và production cần được xác minh theo cấu hình của từng môi trường.

## Thay đổi đã triển khai

| Đích | Cô lập | Xác thực và phân quyền |
|---|---|---|
| Checkout Redis | Chỉ pod Checkout và exporter được tới cổng 6379 | TLS, tắt user `default`. Checkout chỉ có quyền cần thiết trên `checkout:*`, exporter chỉ có quyền đọc thông tin vận hành, probe chỉ có quyền `PING`. Giữ volume, AOF và `noeviction`. |
| Kafka Connect | Service 8083 trỏ đến proxy 8443. Chỉ Job đăng ký được tới proxy từ trong cụm. REST gốc bind loopback. | Proxy yêu cầu chứng chỉ client. Job đăng ký và người vận hành local dùng hai chứng chỉ riêng. |
| Elasticsearch | Chỉ pod Search được tới cổng 9200 | HTTPS và xác thực theo user. Role Search giới hạn theo index và alias của Search. Probe dùng tài khoản riêng có quyền theo dõi sức khỏe. Giữ volume hiện có. |

`allow-project-namespaces` đã loại ba đích khỏi rule rộng. Cấu hình Istio chỉ cho danh tính Prometheus vào cổng 9121 của exporter. Secret và CA local được cấp theo context. Bootstrap chỉ tạo namespace còn thiếu để bảo toàn nhãn enrollment đã có. Xem [manifest NetworkPolicy](../../deploy/k8s/infra/networkpolicies.yaml), [policy của exporter](../../deploy/mesh/checkout-redis-exporter-policy.yaml) và [cách vận hành](../../deploy/k8s/README.md).

## Kết quả

| Kiểm tra | Kết quả |
|---|---|
| Ma trận truy cập trên kind | **55/55 đạt**. Order, pod không được phép trong `data` và pod `default` trên cả hai node đều không tới được listener qua DNS hoặc IP pod. Phép thử xác minh TLS handshake hoặc phản hồi HTTP trực tiếp vì ambient có thể nhận TCP cục bộ trước khi đường đi tới đích bị chặn. |
| Xác thực tại đích | Redis trả `NOAUTH` hoặc `WRONGPASS`. Elasticsearch trả `401` khi thiếu hoặc sai mật khẩu và `403` khi Search gọi API quản trị bảo mật. Kafka Connect từ chối khi thiếu chứng chỉ client. Các caller có credential phù hợp hoạt động. |
| Checkout và metrics | Readiness trả `200`. Lua Preview, Submit và finalize chạy trên Redis đã bảo vệ. Replay giữ nguyên attempt và kết quả đầu tiên. Pod có danh tính Prometheus đọc được `redis_up=1`. Hai test tích hợp Submit đạt với Order giả lập. |
| Kafka Connect và CDC | Job đăng ký hoàn tất qua mTLS. Sáu connector và task đều `RUNNING`, sáu slot logical replication hoạt động. End offset của cả năm topic heartbeat được cấu hình đều tăng. |
| Elasticsearch | Search tạo index, ghi document, reindex, đổi alias và đọc bản sao trên dữ liệu giả lập. Hai index thử được dọn sau phép thử. |
| Hồi quy Istio và Gateway | **54/54 đạt** sau khi khôi phục enrollment và cho Traefik tham gia lại mesh. API Search qua Gateway trả `200`. |

Bằng chứng gồm [ma trận truy cập](./data-endpoint-p1-evidence-2026-11-06/access-results.json), [smoke test và Checkout](./data-endpoint-p1-evidence-2026-11-06/runtime-results.json), [offset heartbeat CDC](./data-endpoint-p1-evidence-2026-11-06/cdc-heartbeats.json) và [hồi quy mesh](./data-endpoint-p1-evidence-2026-11-06/mesh-regression.json).

Trong lần rollout đầu, bootstrap đã làm mất nhãn Istio ở hai namespace do áp dụng lại namespace với manifest tối giản. Tôi đã đổi bootstrap sang chỉ tạo namespace còn thiếu, khôi phục nhãn và chạy lại bootstrap để xác nhận các nhãn được giữ. Kết quả trong báo cáo lấy từ các lượt kiểm tra cuối trên cụm đã bật lại ambient.

## Chạy lại

Từ repo root, sau khi đã chạy `deploy/mesh/local.py stack` và có image cùng pod probe của bộ kiểm tra mesh.

```powershell
python deploy/mesh/verify_data_security.py --out .tools/mesh/data-access-results.json
```

Probe tạm có readiness gate để không được chọn làm endpoint phục vụ traffic. Fixture Redis tự hết hạn sau 30 giây. Các test Submit dùng Order giả lập. Lượt xác minh này không tạo giao dịch thanh toán và không dùng dữ liệu khách hàng.
