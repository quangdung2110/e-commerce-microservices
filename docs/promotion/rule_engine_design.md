# Promotion Rule Engine Design

## 1. Scope
This document defines the AST schema and evaluation rules for `promotions.conditions_rule`.

## 2. AST Node Types

### 2.1 LogicalNode
```json
{
  "node_type": "LOGICAL",
  "operator": "AND|OR",
  "children": [ ... ]
}
```

### 2.2 ConditionNode
```json
{
  "node_type": "CONDITION",
  "operator": "EQUAL|NOT_EQUAL|IN|NOT_IN|GREATER_THAN|GREATER_THAN_OR_EQUAL|LESS_THAN|LESS_THAN_OR_EQUAL",
  "field": "cart.total_amount",
  "value": 200000
}
```

## 3. Supported Input Fields
- `cart.total_amount`
- `cart.payment_method`
- `cart.checkout_channel`
- `cart.items.category_id`
- `user.id`
- `user.tier`

Cardinality notes:
- `cart.items.category_id` la collection (danh sach category trong gio).
- `user.tier`, `cart.payment_method`, `cart.checkout_channel` la scalar.

## 4. Evaluation Rules
1. Tree evaluation is recursive depth-first.
2. `AND` returns true only when all children return true.
3. `OR` returns true when any child returns true.
4. Missing field in payload => condition returns false (no exception).
5. Numeric comparison uses decimal-safe math in service layer.
6. `IN` semantics:
  - scalar field: true neu `actual` nam trong tap `value`.
  - collection field: true neu TON TAI it nhat 1 phan tu trong `actual` nam trong tap `value` (ANY match).
7. `NOT_IN` semantics:
  - scalar field: true neu `actual` khong nam trong tap `value`.
  - collection field: true neu TAT CA phan tu trong `actual` deu khong nam trong tap `value` (ALL not match).
8. Empty collection handling:
  - `IN` tren collection rong => false.
  - `NOT_IN` tren collection rong => true (vacuous truth), tru khi field bi thieu hoan toan (Rule #4 -> false).

## 5. Determinism Requirements
- Rule evaluation must be pure (no side effects).
- Evaluation result depends only on payload and rule AST.
- Rule engine must not mutate Redis/DB.

## 6. Validation Rules
- `node_type` is mandatory for every node.
- `children` is mandatory for `LOGICAL` nodes and must be non-empty.
- `field` and `value` are mandatory for `CONDITION` nodes.
- Unknown operator must be rejected at create/update promotion API.

## 7. Versioning
- `rule_version` là bắt buộc trong metadata khi publish/serve rule cho Checkout.
- API response (voucher discovery/calculate-discount) phải trả `rule_version` đã evaluate để phục vụ trace và staleness check Submit path.

## 8. Ineligible Reason Contract

Để đồng bộ submit-time re-evaluation giữa Promotion và Checkout, Rule Engine phải trả `eligible` + `ineligible_reason` theo bộ mã chuẩn:

- `CONDITIONS_NOT_MET`: Rule AST tổng quát không thoả mãn.
- `MIN_PURCHASE_NOT_MET`: Không đạt `min_purchase_amount`.
- `EXPIRED`: Promotion đã hết hạn tại thời điểm evaluate.
- `PAYMENT_METHOD_NOT_MATCHED`: Payment method không khớp điều kiện rule.
- `CATEGORY_NOT_MATCHED`: Không có item thuộc danh mục áp dụng.

Nếu reserve thất bại do ngân sách, mã lỗi `BUDGET_EXHAUSTED` đến từ Redis reserve path (không phải từ Rule Engine). Checkout phải hợp nhất hai nguồn reason này để dựng `dropped_promotions[]`.

> [!NOTE]
> Theo Option A, ở luồng Discovery có thể xuất hiện `BUDGET_EXHAUSTED` từ bước Budget Guard read-time (thực thi sau Rule Engine). Lõi Rule Engine vẫn không tự tính exhausted budget.
