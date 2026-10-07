# Security và PII

## Data classification

Mỗi contract SHOULD phân loại field:

- `PUBLIC`: có thể hiển thị công khai.
- `INTERNAL`: chỉ trong hệ thống.
- `PII`: định danh cá nhân như email, số điện thoại, địa chỉ, IP.
- `SECRET`: password, token, OTP, API key; không được xuất hiện trong event/log.

## Quy tắc

- Chỉ đưa PII vào event khi consumer thực sự cần và được liệt kê rõ.
- Event broadcast rộng không nên chứa email/device info chỉ để tiện triển khai.
- Schema/example dùng dữ liệu giả, không copy production data.
- Contract ghi auth scheme, scope/role và ownership check.
- Error không tiết lộ resource của user khác; ưu tiên `404` khi cần tránh enumeration.
- Field nhạy cảm phải có retention và redaction rule trong service docs.
