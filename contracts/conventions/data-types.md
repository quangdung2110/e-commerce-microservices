# Kiểu dữ liệu chung

## Identifier

- ID đi qua boundary MUST là string và consumer MUST coi nó là opaque value.
- Chỉ owner được quyết định format và sinh ID.
- Không parse `user_id` từ `cart_id` hay suy luận business data từ ID.
- UUID, BIGINT hoặc sequence trong database là quyết định nội bộ.

## Money

Không dùng `float`/`double`. Dùng số nguyên theo đơn vị nhỏ nhất cùng mã tiền tệ:

```json
{ "amount_minor": 125000, "currency": "VND" }
```

`amount_minor` MUST là `int64`; `currency` MUST là ISO 4217 viết hoa. Phép chia
và rounding phải được owner mô tả nếu interaction thực hiện tính tiền.

## Date và time

- JSON timestamp MUST dùng RFC 3339 UTC, ví dụ `2026-08-25T14:30:00Z`.
- Protobuf MUST dùng `google.protobuf.Timestamp`.
- Duration nên dùng milliseconds với tên có hậu tố `_ms`, hoặc Protobuf Duration.
- Không dùng epoch dạng string nếu không có lý do tương thích legacy.

## Optional và null

- Missing nghĩa là “không được cung cấp”.
- `null` chỉ dùng khi “được cung cấp nhưng chưa có giá trị” là một trạng thái nghiệp vụ.
- Empty string, empty list và số `0` không thay thế cho `null`.
- Khi update một phần, contract phải nói rõ missing là “giữ nguyên” hay “xóa”.

## Collection và enum

- List/batch MUST có giới hạn `maxItems` hoặc giới hạn ghi trong RPC comment.
- String MUST có giới hạn chiều dài khi nhận từ bên ngoài.
- Enum MUST có giá trị `UNSPECIFIED`/`UNKNOWN` và consumer không được crash khi
  gặp giá trị mới.
