---
doc_id: product-adr-<sequence>
service: product
status: proposed # proposed | accepted | superseded | rejected
date: <yyyy-mm-dd>
decision_owners:
  - <owner>
supersedes: null
evidence: []
---

# ADR-<sequence> — <Tên quyết định>

## Status

`Proposed | Accepted | Superseded | Rejected`

Nếu ADR bị thay thế, liên kết tới ADR mới. Nếu ADR thay thế quyết định cũ, cập
nhật `supersedes` và liên kết hai chiều.

## Context

Mô tả vấn đề, constraint kỹ thuật/nghiệp vụ, current behavior và lý do cần quyết
định lúc này.

## Decision

Ghi quyết định cụ thể, phạm vi áp dụng và những gì không thuộc quyết định.

## Alternatives considered

Với mỗi phương án, nêu cách hoạt động, lợi ích, hạn chế/risk và lý do chọn hoặc
không chọn.

## Consequences

Nêu hệ quả tích cực/tiêu cực, complexity, operational cost, constraint mới và
technical debt được chấp nhận.

## Migration impact

Mô tả ảnh hưởng tới code, data/migration, API/event contract, deployment/rollback
và tài liệu/đào tạo.

## Verification

Liên kết source path, contract test, migration, metric, load test hoặc acceptance
criteria chứng minh quyết định đã được implement.
