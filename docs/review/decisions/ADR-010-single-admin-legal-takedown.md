---
doc_id: review-adr-010
service: review
status: accepted
date: 2026-09-09
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - contracts/http/review/v1/openapi.yaml
---

# ADR-010 — Một admin có thẩm quyền thực hiện legal takedown

## Status

`Accepted`

## Context

Thiết kế trước ghi cả `requested_by_admin_id` và `approved_by_admin_id`, với ràng
buộc hai UUID khác nhau. Public/admin HTTP command chỉ xác thực được admin đang gọi;
nếu nhận UUID approver trong body thì caller có thể tự điền ID của một admin khác. Do
đó schema cũ chỉ chứng minh hai ID khác nhau, không chứng minh phê duyệt độc lập.

Không có service/workflow approval riêng, evidence được ký, hay event inbound để Review
xác minh chữ ký của approver. Giữ cụm từ “two-person approval” trong điều kiện này làm
audit trail gây hiểu nhầm.

## Decision

Một admin có role phù hợp có thể thực hiện legal takedown. Command nhận
`legal_case_id` và lý do nội bộ; Review lấy actor duy nhất từ trusted Gateway identity
và ghi vào `review_takedowns.taken_down_by_admin_id`.

`requested_by_admin_id`, `approved_by_admin_id` và constraint hai người khác nhau bị
loại khỏi initial schema. Review vẫn tạo một immutable audit record/review, yêu cầu
legal case và không đưa actor, case hay reason vào public response hoặc event.

## Alternatives considered

**Two-person approval thực sự.** Cần một takedown request có state, record approval
hoặc signed approval evidence, endpoint/flow cho admin thứ hai, expiry và audit trail.
Đó là bảo đảm mạnh hơn, nhưng cần mở rộng database và một workflow mới chưa tồn tại.

**Giữ hai UUID trong một request.** Không thêm hạ tầng, nhưng không có bằng chứng
approver đã hành động. Cách này bị loại vì mô tả bảo đảm cao hơn thực tế.

## Consequences

Takedown dễ triển khai và audit record nói đúng điều nó chứng minh: ai đã thực hiện
command. Rủi ro là quyền lực tập trung ở một admin role; Gateway và Review phải kiểm
tra role, bảo vệ identity header, và audit access tới reason/legal case. Nếu compliance
sau này đòi four-eyes control, phải thay ADR này bằng workflow approval bền vững, không
chỉ thêm lại một UUID vào request body.

## Verification

Test chứng minh request body không có admin actor/approver field, Review chỉ persist
trusted caller vào `taken_down_by_admin_id`, một review chỉ có một takedown, và legal
case/reason/actor không xuất hiện trong public API hoặc hai event Review liên quan.
