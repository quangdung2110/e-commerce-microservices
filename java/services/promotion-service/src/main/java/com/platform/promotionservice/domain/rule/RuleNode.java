package com.platform.promotionservice.domain.rule;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Rule AST (ADR-009). Polymorphic theo {@code node_type}; đây vừa là wire shape
 * của HTTP v1 ({@code RuleNode}) vừa là storage shape trong {@code conditions_rule}.
 * Node không tự evaluate: {@link RuleEvaluator} thuần và xác định làm việc đó.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "node_type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = LogicalNode.class, name = "LOGICAL"),
        @JsonSubTypes.Type(value = ConditionNode.class, name = "CONDITION")
})
public sealed interface RuleNode permits LogicalNode, ConditionNode {

    String nodeType();
}
