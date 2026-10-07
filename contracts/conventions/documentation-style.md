# Cách viết tài liệu cho sinh viên

Mỗi interaction README nên theo thứ tự:

1. Vấn đề cần giải quyết.
2. Ai gọi ai và ai sở hữu dữ liệu.
3. Happy path ngắn.
4. Link tới machine-readable contract.
5. Request/message và response/result.
6. Failure, retry và idempotency.
7. Giới hạn chịu tải.
8. Ví dụ chạy thử.
9. Compatibility.
10. Thuật ngữ và sai lầm thường gặp.

## Style

- Viết tiếng Việt; identifier/code dùng tiếng Anh.
- Giải thích “tại sao” trước “cấu hình thế nào”.
- Một file tập trung vào một chủ đề.
- Example đặt cạnh khái niệm liên quan.
- Gắn nhãn `proposed`, `implemented` hoặc `verified`.
- Sơ đồ chỉ dùng khi giúp hiểu flow tốt hơn đoạn văn ngắn.
- Không dùng từ “high performance” nếu chưa có metric/load test tương ứng.
