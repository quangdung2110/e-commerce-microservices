package com.ecommerce.promotion.domain.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Node Logic (AND, OR) — Chứa danh sách node con.
 * Theo thiết kế rule_engine_design.md - Section 2.1
 * Theo thiết kế RuleEngineDemo.java
 *
 * Composite Pattern: LogicalNode chứa List<RuleNode> children (đệ quy).
 * Short-circuit evaluation: AND trả false khi gặp false đầu tiên,
 * OR trả true khi gặp true đầu tiên.
 */
public class LogicalNode implements RuleNode {

    // Theo thiết kế: AND hoặc OR
    @JsonProperty("operator")
    private String operator;

    // Theo thiết kế: JSON field name "children" (không phải "rules")
    @JsonProperty("children")
    private List<RuleNode> children;

    public LogicalNode() {}

    public LogicalNode(String operator, List<RuleNode> children) {
        this.operator = operator;
        this.children = children;
    }

    /**
     * Đánh giá logic AND hoặc OR trên tất cả children.
     * Theo thiết kế rule_engine_design.md - Section 2.1:
     *   AND → Short-circuit: gặp 1 false → return false.
     *   OR  → Short-circuit: gặp 1 true  → return true.
     */
    @Override
    public boolean evaluate(EvaluationContext context) {
        if (children == null || children.isEmpty()) {
            return true;
        }

        if ("AND".equalsIgnoreCase(operator)) {
            // Theo thiết kế: Short-circuit — nhanh chóng trả false nếu gặp 1 điều kiện sai
            for (RuleNode child : children) {
                if (!child.evaluate(context)) {
                    return false;
                }
            }
            return true;
        } else if ("OR".equalsIgnoreCase(operator)) {
            // Theo thiết kế: Short-circuit — nhanh chóng trả true nếu gặp 1 điều kiện đúng
            for (RuleNode child : children) {
                if (child.evaluate(context)) {
                    return true;
                }
            }
            return false;
        }

        throw new IllegalArgumentException("Unknown logical operator: " + operator);
    }

    // ---- Getters / Setters ----

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public List<RuleNode> getChildren() { return children; }
    public void setChildren(List<RuleNode> children) { this.children = children; }
}
