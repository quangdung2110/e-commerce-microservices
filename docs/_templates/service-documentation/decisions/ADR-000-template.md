---
doc_id: <service-id>-adr-<sequence>
service: <service-id>
status: proposed # proposed | accepted | superseded | rejected
date: <yyyy-mm-dd>
decision_owners:
  - <owner>
supersedes: null
evidence:
  - <path-or-link>
---

# ADR-<sequence> — <Tên quyết định>

## Status

`Proposed | Accepted | Superseded | Rejected`

Nếu ADR bị thay thế, liên kết tới ADR mới. Nếu ADR thay thế quyết định cũ, cập
nhật field `supersedes` và liên kết hai chiều.

## Context

Mô tả vấn đề cần giải quyết, constraint kỹ thuật/nghiệp vụ, current behavior và
lý do cần đưa ra quyết định lúc này.

## Decision

Ghi quyết định cụ thể, phạm vi áp dụng và những gì không thuộc quyết định. Tránh
các mô tả mơ hồ như “dùng giải pháp hiệu năng cao”.

## Alternatives considered

Với mỗi phương án đã cân nhắc, mô tả:

- Cách hoạt động.
- Lợi ích.
- Hạn chế/risk.
- Lý do chọn hoặc không chọn.

## Consequences

Nêu rõ:

- Hệ quả tích cực.
- Hệ quả tiêu cực.
- Complexity và operational cost.
- Constraint mới.
- Technical debt được chấp nhận.

## Migration impact

Mô tả ảnh hưởng tới:

- Code.
- Data và migration/backfill.
- API/event contract và consumer.
- Deployment/rollback.
- Tài liệu và đào tạo.

## Verification

Mô tả cách chứng minh quyết định đã được implement, ví dụ source path, contract
test, migration, metric, load test hoặc acceptance criteria.
