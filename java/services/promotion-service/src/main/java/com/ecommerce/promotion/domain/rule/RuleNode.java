package com.ecommerce.promotion.domain.rule;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Interface gốc cho mọi Node trong cây AST Rule Engine.
 * Theo thiết kế rule_engine_design.md - Section 2.1
 * Theo thiết kế RuleEngineDemo.java
 *
 * Sử dụng Composite Pattern + Visitor Pattern tinh gọn.
 * Jackson @JsonTypeInfo parse JSONB thẳng thành cây Object RuleNode.
 *
 * JSON Schema chuẩn bắt buộc:
 * - Mỗi node phải có trường "node_type" ("LOGICAL" hoặc "CONDITION")
 * - LogicalNode dùng "children" (không phải "rules")
 * - ConditionNode dùng "field", "operator", "value"
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "node_type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = LogicalNode.class, name = "LOGICAL"),
    @JsonSubTypes.Type(value = ConditionNode.class, name = "CONDITION")
})
public interface RuleNode {

    /**
     * Đánh giá Node này dựa trên dữ liệu thực tế từ giỏ hàng/user.
     * Theo thiết kế rule_engine_design.md - Section 2.1
     *
     * @param context Dữ liệu thực tế (cart, user) để so sánh
     * @return true nếu thỏa mãn điều kiện
     */
    boolean evaluate(EvaluationContext context);
}
