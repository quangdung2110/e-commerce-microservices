package com.ecommerce.promotion.domain.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Node Điều kiện (Lá của cây AST) — Thực hiện so sánh dữ liệu.
 * Theo thiết kế rule_engine_design.md - Section 2.1, 2.2
 * Theo thiết kế RuleEngineDemo.java
 *
 * Hỗ trợ tất cả operators:
 * EQUAL, NOT_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL,
 * LESS_THAN, LESS_THAN_OR_EQUAL, IN, NOT_IN, CONTAINS
 */
public class ConditionNode implements RuleNode {

    // Theo thiết kế: VD "cart.total_amount", "user.tier"
    @JsonProperty("field")
    private String field;

    // Theo thiết kế: VD "GREATER_THAN_OR_EQUAL", "IN", "NOT_IN"
    @JsonProperty("operator")
    private String operator;

    // Theo thiết kế: VD 200000, ["GOLD", "DIAMOND"], true
    @JsonProperty("value")
    private Object value;

    public ConditionNode() {}

    public ConditionNode(String field, String operator, Object value) {
        this.field = field;
        this.operator = operator;
        this.value = value;
    }

    /**
     * Đánh giá điều kiện dựa trên dữ liệu context.
     * Theo thiết kế rule_engine_design.md - Section 5 (Pseudo-code)
     */
    @Override
    public boolean evaluate(EvaluationContext context) {
        Object actualValue = context.getValue(this.field);
        if (actualValue == null) {
            return false;
        }

        switch (this.operator.toUpperCase()) {
            case "EQUAL":
                return compareEqual(actualValue, this.value);

            case "NOT_EQUAL":
                return !compareEqual(actualValue, this.value);

            case "GREATER_THAN":
                // Theo thiết kế rule_engine_design.md - Section 2.2
                return toDouble(actualValue) > toDouble(this.value);

            case "GREATER_THAN_OR_EQUAL":
                return toDouble(actualValue) >= toDouble(this.value);

            case "LESS_THAN":
                return toDouble(actualValue) < toDouble(this.value);

            case "LESS_THAN_OR_EQUAL":
                return toDouble(actualValue) <= toDouble(this.value);

            case "IN":
                // Theo thiết kế: expectedValue là một List, kiểm tra actualValue có nằm trong list không
                if (this.value instanceof List) {
                    return listContainsValue((List<?>) this.value, actualValue);
                }
                return false;

            case "NOT_IN":
                // Theo thiết kế: Đảo ngược của IN
                if (this.value instanceof List) {
                    return !listContainsValue((List<?>) this.value, actualValue);
                }
                return true;

            case "CONTAINS":
                // Theo thiết kế: actualValue là một List (VD: danh sách category_id trong giỏ),
                // kiểm tra list có chứa expectedValue không
                if (actualValue instanceof List) {
                    return listContainsValue((List<?>) actualValue, this.value);
                }
                return false;

            default:
                throw new IllegalArgumentException("Unknown condition operator: " + operator);
        }
    }

    /**
     * So sánh bằng an toàn, xử lý cả trường hợp Number được Jackson parse
     * thành Integer nhưng so sánh với Long hoặc Double.
     */
    private boolean compareEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        // Nếu cả hai là Number, so sánh theo doubleValue để tránh lỗi type mismatch
        if (actual instanceof Number && expected instanceof Number) {
            return ((Number) actual).doubleValue() == ((Number) expected).doubleValue();
        }
        return actual.equals(expected);
    }

    /**
     * Chuyển đổi Object sang double an toàn.
     */
    private double toDouble(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        throw new IllegalArgumentException("Cannot convert to number: " + obj);
    }

    /**
     * Kiểm tra List có chứa giá trị hay không, xử lý Number type mismatch.
     */
    private boolean listContainsValue(List<?> list, Object value) {
        for (Object item : list) {
            if (compareEqual(item, value)) {
                return true;
            }
        }
        return false;
    }

    // ---- Getters / Setters ----

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
}
