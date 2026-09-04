---
doc_id: search-adr-003
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - docs/search/schema/products.index.json
  - docs/search/schema/search_suggestions.index.json
---

# ADR-003 — Phân tích tiếng Việt bằng ICU và autocomplete bằng edge n-gram

## Context

Buyer gõ tiếng Việt có dấu lẫn không dấu, viết hoa thường lẫn lộn và thường gõ sai
chính tả. “dien thoai” phải khớp “Điện thoại”. Autocomplete phải trả kết quả trong
vài chục millisecond ngay từ ký tự thứ hai.

## Decision

Với tìm kiếm: analyzer tùy biến dùng `icu_tokenizer` kèm `icu_folding`,
`lowercase` và một bộ stop word tiếng Việt. Truy vấn dùng `multi_match` với
`fuzziness: AUTO` để chịu được lỗi gõ.

Với autocomplete: một index riêng `search_suggestions` dùng edge n-gram khi index
và analyzer thường khi search, với `weight` được pre-compute bởi job rebuild hằng
ngày. Trường `name.autocomplete` trên index `products` được giữ như phương án dự
phòng cho tìm kiếm theo tiền tố, không phải đường phục vụ dropdown chính.

Phạm vi ngôn ngữ hiện tại chỉ là tiếng Việt.

## Alternatives considered

- **Standard tokenizer.** Không xử lý đúng dấu và Unicode tiếng Việt.
- **Completion suggester.** Rất nhanh nhưng cấu trúc dữ liệu nằm trong bộ nhớ,
  khó tùy biến ranking theo chỉ số nghiệp vụ và khó kết hợp fuzzy.
- **Chỉ dùng `name.autocomplete` trên index `products`.** Bỏ được một index nhưng
  mọi lần gõ phím lại chạm index lớn nhất, và ranking gợi ý bị trói vào ranking sản
  phẩm.
- **Plugin phân tích tiếng Việt chuyên dụng.** Chất lượng tách từ tốt hơn nhưng
  thêm phụ thuộc plugin vào vòng đời nâng cấp cluster.

## Consequences

Tích cực: tìm không dấu hoạt động; gợi ý nhanh và ranking điều chỉnh được độc lập
với ranking tìm kiếm.

Tiêu cực: edge n-gram làm phình index; stop word cứng trong settings nên đổi bộ
stop word là đổi mapping và phải reindex; `fuzziness: AUTO` có thể kéo về kết quả
không liên quan cho truy vấn ngắn; gợi ý cũ tối đa một ngày vì rebuild theo lịch.
Đồng nghĩa từ và viết tắt chưa được xử lý và là hạng mục tương lai.

## Migration impact

Mọi thay đổi analyzer, tokenizer hoặc stop word đều là thay đổi mapping và phải đi
qua reindex ba pha.

## Verification

Test bắt buộc: truy vấn có dấu và không dấu trả cùng kết quả; sai một ký tự vẫn
khớp; prefix hai ký tự trả gợi ý; alias suggestion swap không gây lỗi request đang
chạy.
