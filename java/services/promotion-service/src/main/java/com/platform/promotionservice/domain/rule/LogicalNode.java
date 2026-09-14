package com.platform.promotionservice.domain.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Node AND/OR với children không rỗng (PROMO-BR-016). */
public record LogicalNode(
        @JsonProperty("node_type") String nodeType,
        @JsonProperty("operator") String operator,
        @JsonProperty("children") List<RuleNode> children) implements RuleNode {

    public static final String AND = "AND";
    public static final String OR = "OR";

    @JsonCreator
    public LogicalNode {
        nodeType = "LOGICAL";
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static LogicalNode and(RuleNode... children) {
        return new LogicalNode("LOGICAL", AND, List.of(children));
    }

    public static LogicalNode or(RuleNode... children) {
        return new LogicalNode("LOGICAL", OR, List.of(children));
    }
}
