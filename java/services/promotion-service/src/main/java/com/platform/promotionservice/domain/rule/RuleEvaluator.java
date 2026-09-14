package com.platform.promotionservice.domain.rule;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluate AST thuần, xác định, depth-first O(n) (ADR-009, PROMO-INV-011).
 * Không DB/Redis/network; cùng AST + context luôn cho cùng kết quả.
 *
 * <ul>
 *   <li>Missing field (null) → false, kể cả NOT_EQUAL/NOT_IN (PROMO-BR-014/015).</li>
 *   <li>IN với collection actual là ANY; NOT_IN là ALL-not-match.</li>
 *   <li>Expected collection rỗng: IN → false, NOT_IN → true.</li>
 *   <li>Số so sánh bằng BigDecimal; kiểu không tương thích → false, không ném.</li>
 * </ul>
 */
public final class RuleEvaluator {

    private RuleEvaluator() {
    }

    public static boolean evaluate(RuleNode node, Map<String, Object> facts) {
        return switch (node) {
            case LogicalNode logical -> evaluateLogical(logical, facts);
            case ConditionNode condition -> evaluateCondition(condition, facts);
        };
    }

    private static boolean evaluateLogical(LogicalNode node, Map<String, Object> facts) {
        if (node.children().isEmpty()) {
            return false;
        }
        if (LogicalNode.AND.equals(node.operator())) {
            for (RuleNode child : node.children()) {
                if (!evaluate(child, facts)) {
                    return false;
                }
            }
            return true;
        }
        if (LogicalNode.OR.equals(node.operator())) {
            for (RuleNode child : node.children()) {
                if (evaluate(child, facts)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean evaluateCondition(ConditionNode node, Map<String, Object> facts) {
        Object actual = facts.get(node.field());
        if (actual == null) {
            return false;
        }
        Object expected = node.value();
        return switch (node.operator()) {
            case RuleOperator.EQUAL -> scalarEquals(actual, expected);
            case RuleOperator.NOT_EQUAL -> !(actual instanceof Collection<?>) && !scalarEquals(actual, expected);
            case RuleOperator.GREATER_THAN -> compare(actual, expected, 1, false);
            case RuleOperator.GREATER_THAN_OR_EQUAL -> compare(actual, expected, 1, true);
            case RuleOperator.LESS_THAN -> compare(actual, expected, -1, false);
            case RuleOperator.LESS_THAN_OR_EQUAL -> compare(actual, expected, -1, true);
            case RuleOperator.IN -> in(actual, expected);
            case RuleOperator.NOT_IN -> notIn(actual, expected);
            default -> false;
        };
    }

    private static boolean scalarEquals(Object actual, Object expected) {
        if (actual instanceof Collection<?> || expected instanceof Collection<?>) {
            return false;
        }
        if (actual instanceof Number a && expected instanceof Number e) {
            return toDecimal(a).compareTo(toDecimal(e)) == 0;
        }
        if (actual instanceof Number || expected instanceof Number) {
            return false;
        }
        return Objects.equals(actual, expected);
    }

    private static boolean compare(Object actual, Object expected, int sign, boolean orEqual) {
        if (!(actual instanceof Number a) || !(expected instanceof Number e)) {
            return false;
        }
        int cmp = toDecimal(a).compareTo(toDecimal(e));
        if (cmp == 0) {
            return orEqual;
        }
        return Integer.signum(cmp) == sign;
    }

    private static boolean in(Object actual, Object expected) {
        if (!(expected instanceof Collection<?> expectedValues)) {
            return scalarEquals(actual, expected);
        }
        if (expectedValues.isEmpty()) {
            return false;
        }
        if (actual instanceof Collection<?> actualValues) {
            for (Object item : actualValues) {
                if (containsScalar(expectedValues, item)) {
                    return true;
                }
            }
            return false;
        }
        return containsScalar(expectedValues, actual);
    }

    private static boolean notIn(Object actual, Object expected) {
        if (!(expected instanceof Collection<?> expectedValues)) {
            return actual instanceof Collection<?> actualValues
                    ? actualValues.stream().noneMatch(item -> scalarEquals(item, expected))
                    : !scalarEquals(actual, expected);
        }
        if (expectedValues.isEmpty()) {
            return true;
        }
        if (actual instanceof Collection<?> actualValues) {
            for (Object item : actualValues) {
                if (containsScalar(expectedValues, item)) {
                    return false;
                }
            }
            return true;
        }
        return !containsScalar(expectedValues, actual);
    }

    private static boolean containsScalar(Collection<?> values, Object candidate) {
        for (Object value : values) {
            if (scalarEquals(candidate, value)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal toDecimal(Number number) {
        return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    }
}
