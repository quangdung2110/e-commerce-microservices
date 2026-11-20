# ADR-010: Bằng chứng resilience: fault injection ở mức socket và load test có ngưỡng

- Status: Accepted
- Date: 2026-10-16

## Context

Mọi ADR resilience của dự án (timeout, breaker, bulkhead, `Retry-After`, ngân sách deadline) ghi "giá trị khởi điểm, phải được xác nhận bằng load test", và khảo sát 2026-10-15
nêu hai khoảng trống: **không có kết quả load test nào** và **không có fault-injection test cho Order và Search**. Test hiện có thay provider bằng mock trả status
hoặc exception dựng sẵn (`MockRestServiceServer`, `HttpServer` trả đúng giao thức), nên không bao giờ thấy các hỏng hóc nằm dưới tầng HTTP: kết nối bị reset, body bị cắt,
byte không phải HTTP, body nhỏ giọt.

## Decision

1. **Fault injection ở mức socket cho client HTTP của đường saga và batch.** Mỗi client như vậy có một test dùng server loopback làm hỏng có chủ đích (`FaultServer`:
   `HANG`, `RESET`, `GARBAGE`, `TRUNCATED_BODY`, `MALFORMED_JSON`, `SLOW_BODY`, `STATUS`) và kiểm:
   - **Giới hạn thời gian**: lỗi trả về trong khoảng `read-timeout` đã quảng bá qua `X-Request-Timeout-Ms`, không treo;
   - **Phân loại**: không lỗi nào được coi là kết quả nghiệp vụ. Order: `UNKNOWN`; Search: exception (để job thất bại và Kafka không commit offset);
   - **Không rò tài nguyên**: bulkhead nhả permit sau mỗi lỗi, và lời gọi kế tiếp tới provider khoẻ thành công;
   - **Metric**: lỗi được đếm đúng dependency, outcome, lớp status.
   Đã có cho Order `HttpRemote` (13 test) và Search `SnapshotExportClient` (10 test). Client khác (Checkout, PDP BFF) có test breaker riêng; khi sửa chúng, thêm `FaultServer` cùng mẫu.
2. **Load test chỉ đọc bằng k6** (`loadtest/k6`, chạy qua `scripts/ci/loadtest.sh` trong mạng compose), có ngưỡng là budget mà thiết kế nêu; kết quả ghi ở
   [docs/performance/load-test.md](../../docs/performance/load-test.md) kèm môi trường đo. Đo qua gateway **và** thẳng service, vì rate limit của gateway chặn một client ở ~220 req/s.
3. **Số đo local là bằng chứng tương đối.** Chúng phát hiện lỗi và kiểm cơ chế (budget, breaker, `Retry-After`), không phải kế hoạch năng lực. Số cho production đo trên cluster trong
   giai đoạn 5.3 của [mesh implementation plan](../../docs/mesh/implementation_plan.md).
4. **Không chaos trên stack dùng chung.** Dừng container hay ngắt mạng ảnh hưởng người đang làm việc; chaos chạy trong test (mục 1) hoặc trên môi trường riêng.
5. **Mỗi lỗi tìm ra có ADR của service**: Order [ADR-025](../../docs/order/decisions/ADR-025-unreadable-success-body-is-unknown.md), PDP [ADR-010](../../docs/product-detail-bff/decisions/ADR-010-retry-after-on-every-503.md),
   Order [ADR-026](../../docs/order/decisions/ADR-026-retry-after-default-on-503.md), Inventory [ADR-031](../../docs/inventory/decisions/ADR-031-retry-after-default-on-503.md).

## Findings that drove it

| Phát hiện | Cách bắt | Sửa |
|---|---|---|
| Order `HttpRemote` trả `ACCEPTED` với body `null` khi 2xx có body cụt/chậm/hỏng; `CheckoutSaga` đọc `r.body().path(...)` ở hơn 15 chỗ nên NPE | Fault injection (`TRUNCATED_BODY`, `MALFORMED_JSON`, `SLOW_BODY`) | `UNKNOWN` `BODY_UNREADABLE` |
| PDP `503` không có `Retry-After` trừ khi circuit mở (0/19 trong load test), dù ADR-005 bắt buộc | k6 (`retry_after_on_503`) | `Retry-After: 2` mặc định |
| Order và Inventory chỉ gửi `Retry-After` khi nơi ném lỗi tự đặt giá trị | Đọc code sau phát hiện PDP | Mặc định `2` ở lớp dựng problem |

Bài học cho ADR-005: contract khai báo header không chứng minh runtime gửi nó; lint contract chỉ bắt được nửa đầu. Cần probe hoặc test chạy trên provider thật cho nửa sau.

## Alternatives considered

- **Toxiproxy trên stack compose.** Không chọn: cần đổi URL của nhiều service sang proxy, và thay đổi stack dùng chung. `FaultServer` cho cùng loại lỗi, chạy trong CI, không cần Docker.
- **Chaos Mesh / Litmus.** Không chọn: cần Kubernetes.
- **Load test qua gateway duy nhất.** Không chọn: giới hạn rate bóp nghẹt tải trước khi service bị thử.
- **Gatling / JMeter.** Không chọn: k6 chạy được trong một container, script JavaScript ngắn, ngưỡng là mã.

## Consequences

- Hai lỗi thật được sửa và có test chặn tái phát; ba service đổi hành vi nhỏ (xem bảng).
- Test fault injection mất vài giây mỗi class (timeout thật); chạy trong `mvn verify` của từng service.
- Kết quả load test cần cập nhật khi stack hoặc budget đổi; không tự cập nhật.

## Verification

- Order: `HttpRemoteFaultInjectionTest` 13 test; 4 test fail trước khi sửa (`ACCEPTED/null`), xanh sau. Search: `SnapshotExportClientFaultInjectionTest` 10 test xanh.
- PDP 65 test, Order và Inventory `ProblemsRetryAfterTest` xanh sau khi sửa `Retry-After`.
- k6: 4 lần chạy ghi ở [load-test.md](../../docs/performance/load-test.md).
