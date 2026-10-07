package com.platform.promotionservice.domain.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Node điều kiện: field trong allowlist của rule_schema_version, operator trong
 * tập cố định, value là string/number/boolean hoặc array các kiểu đó.
 */
public record ConditionNode(
        @JsonProperty("node_type") String nodeType,
        @JsonProperty("operator") String operator,
        @JsonProperty("field") String field,
        @JsonProperty("value") Object value) implements RuleNode {

    @JsonCreator
    public ConditionNode {
        nodeType = "CONDITION";
    }

    public static ConditionNode of(String operator, String field, Object value) {
        return new ConditionNode("CONDITION", operator, field, value);
    }
}
