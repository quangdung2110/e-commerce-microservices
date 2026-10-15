# Deadline

Quyết định gốc: [ADR-003](../decisions/ADR-003-deadline-propagation.md).

Caller cho provider biết nó chờ bao lâu, để provider không làm việc cho một người đã thôi chờ và để
chuỗi gọi không vượt ngân sách của request đầu tiên.

| | HTTP | gRPC |
|---|---|---|
| Cách truyền | Header `X-Request-Timeout-Ms` | Deadline gốc (`grpc-timeout`) |
| Giá trị | Số nguyên `1`–`600000`, mili giây kể từ lúc caller gửi | Do thư viện gRPC tính |
| Sai hoặc thiếu | Bỏ qua, request vẫn được xử lý | Không có deadline |

## Bên gọi

1. Gửi đúng timeout hiệu dụng của lời gọi (cùng giá trị dùng để hủy lời gọi), làm tròn xuống, tối thiểu 1.
2. Timeout hiệu dụng = `min(trần cấu hình, deadline còn lại của request đến − dự phòng)`. Không có request
   đến thì chỉ còn trần cấu hình.
3. Còn dưới 200 ms thì không gọi và coi như hết ngân sách.
4. Nhỏ hơn timeout mesh của route ít nhất 1 giây; route phải có dòng trong
   [mesh §7](../../docs/mesh/runtime-contract.md).
5. gRPC: đặt deadline bằng timeout hiệu dụng, không dùng trần cố định.

## Bên nhận

1. Đọc header chỉ trên route service-to-service; Gateway xoá bản do client gửi.
2. Khi gọi tiếp downstream hoặc chờ điều kiện trong cùng request, áp quy tắc 2 ở trên với deadline còn lại.
3. Request kết thúc sau quá giá trị header: một dòng WARN và counter
   `http.server.caller_deadline_exceeded{route}`. Không hủy công việc.
4. Không bỏ dở thao tác ghi chỉ vì header. Service Go có thể bọc `context.WithTimeout` cho route chỉ đọc.

Header này là gợi ý, không phải bảo đảm bảo mật và không phải SLO của provider.
