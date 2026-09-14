package com.platform.promotionservice.domain.rule;

import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Từ chối AST không hợp lệ trước persist/publish (PROMO-BR-016): unknown
 * node/operator/field, logical children rỗng, value sai kiểu, depth/size vượt giới hạn.
 */
public final class RuleValidator {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 200;
    public static final int MAX_CHILDREN = 50;
    public static final int MAX_COLLECTION = 100;
    public static final int MAX_STRING = 255;

    private RuleValidator() {
    }

    public static void validate(RuleNode root, int schemaVersion) {
        if (schemaVersion != RuleFields.SCHEMA_VERSION_V1) {
            throw invalid("rule_schema_version " + schemaVersion + " is not supported");
        }
        if (root == null) {
            throw invalid("conditions_rule is required");
        }
        int[] counter = {0};
        walk(root, 1, counter, schemaVersion);
    }

    private static void walk(RuleNode node, int depth, int[] counter, int schemaVersion) {
        if (depth > MAX_DEPTH) {
            throw invalid("Rule depth exceeds " + MAX_DEPTH);
        }
        if (++counter[0] > MAX_NODES) {
            throw invalid("Rule has more than " + MAX_NODES + " nodes");
        }
        switch (node) {
            case LogicalNode logical -> {
                if (!LogicalNode.AND.equals(logical.operator()) && !LogicalNode.OR.equals(logical.operator())) {
                    throw invalid("Unknown logical operator: " + logical.operator());
                }
                if (logical.children().isEmpty()) {
                    throw invalid("Logical node requires at least one child");
                }
                if (logical.children().size() > MAX_CHILDREN) {
                    throw invalid("Logical node has more than " + MAX_CHILDREN + " children");
                }
                for (RuleNode child : logical.children()) {
                    if (child == null) {
                        throw invalid("Logical child must not be null");
                    }
                    walk(child, depth + 1, counter, schemaVersion);
                }
            }
            case ConditionNode condition -> validateCondition(condition, schemaVersion);
        }
    }

    private static void validateCondition(ConditionNode node, int schemaVersion) {
        if (node.operator() == null || !RuleOperator.CONDITION_OPERATORS.contains(node.operator())) {
            throw invalid("Unknown condition operator: " + node.operator());
        }
        if (node.field() == null || !RuleFields.isAllowed(schemaVersion, node.field())) {
            throw invalid("Field is not in the rule_schema_version allowlist: " + node.field());
        }
        Object value = node.value();
        if (value == null) {
            throw invalid("Condition value is required for field " + node.field());
        }
        if (RuleOperator.COLLECTION_OPERATORS.contains(node.operator())) {
            if (!(value instanceof Collection<?> values)) {
                throw invalid(node.operator() + " requires an array value");
            }
            if (values.size() > MAX_COLLECTION) {
                throw invalid("Collection value exceeds " + MAX_COLLECTION + " items");
            }
            Set<Object> seen = new HashSet<>();
            for (Object item : values) {
                validateScalar(item, node.field());
                if (!seen.add(item)) {
                    throw invalid("Collection value must contain unique items");
                }
            }
            return;
        }
        if (value instanceof Collection<?>) {
            throw invalid(node.operator() + " does not accept an array value");
        }
        validateScalar(value, node.field());
        if (RuleOperator.NUMERIC_OPERATORS.contains(node.operator()) && !(value instanceof Number)) {
            throw invalid(node.operator() + " requires a numeric value");
        }
    }

    private static void validateScalar(Object value, String field) {
        if (value instanceof String text) {
            if (text.length() > MAX_STRING) {
                throw invalid("String value exceeds " + MAX_STRING + " characters for " + field);
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return;
        }
        throw invalid("Unsupported value type for " + field);
    }

    private static DomainException invalid(String detail) {
        return new DomainException(ErrorCode.INVALID_RULE, detail,
                List.of(new DomainException.FieldError("conditions_rule", detail)));
    }
}
