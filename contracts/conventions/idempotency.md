# Idempotency Contract

Contract chỉ mô tả hành vi quan sát được; Redis SETNX, unique constraint hoặc
Temporal Workflow ID là implementation.

## HTTP/gRPC command

Contract MUST trả lời:

1. Operation có yêu cầu idempotency key không?
2. Key unique trong phạm vi user, resource hay operation nào?
3. Key được giữ tối thiểu bao lâu?
4. Cùng key và cùng payload trả lại kết quả nào?
5. Cùng key nhưng payload khác trả lỗi nào?
6. Request đang xử lý đồng thời trả `409`, `425` hay chờ kết quả?

Idempotency key do caller sinh và MUST được tái sử dụng qua các lần retry của
cùng một logical command.

## Event consumer

- Dedup key mặc định là CloudEvent `id`.
- Side effect và dedup marker phải được commit atomically hoặc có cơ chế recovery.
- TTL dedup không được ngắn hơn cửa sổ replay/retry mà hệ thống cam kết.
