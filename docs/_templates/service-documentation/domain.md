---
doc_id: <service-id>-domain
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-domain-code>
  - <path-to-domain-tests>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Domain Model

> File này trả lời: “Service đang bảo vệ những quy tắc nghiệp vụ nào?”

## 1. Ubiquitous language

Định nghĩa các thuật ngữ nghiệp vụ bằng bảng:

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| `<term>` | `<business definition>` | `<similar term>` |

Một thuật ngữ phải có cùng ý nghĩa trong tài liệu, code, contract và trao đổi
giữa các thành viên.

## 2. Domain objects

Mô tả:

- Aggregate và aggregate root.
- Entity.
- Value object.
- Quan hệ giữa chúng.
- Identity và lifecycle của từng object.
- Object nào thuộc service khác và chỉ được tham chiếu bằng identifier/snapshot.

## 3. Commands và queries

Liệt kê:

- Command nào làm thay đổi state.
- Query nào chỉ đọc.
- Actor được phép gửi command/query.
- Aggregate hoặc capability xử lý chúng.

## 4. Business rules

Với mỗi rule, mô tả:

- Identifier ổn định.
- Điều kiện áp dụng.
- Validation nghiệp vụ.
- Kết quả khi rule được thỏa mãn.
- Domain error khi rule bị vi phạm.

## 5. Invariants

Liệt kê các điều kiện phải đúng trước và sau transaction. Ví dụ: quantity phải
lớn hơn 0 hoặc một user chỉ có một active cart.

Mỗi invariant phải chỉ ra nơi enforcement trong code hoặc test. Không gọi một
mong muốn chung là invariant nếu không có cách kiểm chứng.

## 6. State machines

Với mỗi state machine, mô tả:

- Danh sách state.
- Initial và terminal state.
- Transition.
- Trigger.
- Guard condition.
- Side effect.
- Invalid transition và error tương ứng.

Có thể dùng bảng hoặc state diagram nhưng phải giữ identifier của state và
transition giống code/contract.

## 7. Authorization rules

Nêu rõ:

- Actor/role nào được thực hiện command nào.
- Resource ownership được kiểm tra thế nào.
- Quy tắc tenant/shop/user isolation.
- Trường hợp nào cần quyền quản trị hoặc service credential.

## 8. Domain errors

| Error code | Điều kiện phát sinh | Retryable | Mapping ra interface |
|---|---|---|---|
| `<code>` | `<condition>` | Yes/No | HTTP/gRPC/Event handling |

Phân biệt domain error với lỗi hạ tầng như timeout hoặc database unavailable.

## 9. Business examples

Đưa ra một số ví dụ cụ thể về đầu vào, state ban đầu, hành động và kết quả mong
đợi. Bao gồm cả ví dụ hợp lệ và ví dụ vi phạm rule/invariant.

Database table và HTTP request không phải domain model, vì vậy không copy chi
tiết của chúng vào file này.
