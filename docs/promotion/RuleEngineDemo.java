import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class RuleEngineDemo {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "node_type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = LogicalNode.class, name = "LOGICAL"),
        @JsonSubTypes.Type(value = ConditionNode.class, name = "CONDITION")
    })
    interface RuleNode {
        boolean eval(Map<String, Object> ctx);
    }

    static class LogicalNode implements RuleNode {
        public String operator;
        public List<RuleNode> children;

        @Override
        public boolean eval(Map<String, Object> ctx) {
            if ("AND".equals(operator)) {
                for (RuleNode c : children) {
                    if (!c.eval(ctx)) return false;
                }
                return true;
            }
            if ("OR".equals(operator)) {
                for (RuleNode c : children) {
                    if (c.eval(ctx)) return true;
                }
                return false;
            }
            throw new IllegalArgumentException("Unsupported logical operator: " + operator);
        }
    }

    static class ConditionNode implements RuleNode {
        public String operator;
        public String field;
        public Object value;

        @Override
        public boolean eval(Map<String, Object> ctx) {
            Object actual = ctx.get(field);
            if (actual == null) return false;

            switch (operator) {
                case "EQUAL":
                    return actual.equals(value);
                case "NOT_EQUAL":
                    return !actual.equals(value);
                case "GREATER_THAN":
                    return toDecimal(actual).compareTo(toDecimal(value)) > 0;
                case "GREATER_THAN_OR_EQUAL":
                    return toDecimal(actual).compareTo(toDecimal(value)) >= 0;
                case "LESS_THAN":
                    return toDecimal(actual).compareTo(toDecimal(value)) < 0;
                case "LESS_THAN_OR_EQUAL":
                    return toDecimal(actual).compareTo(toDecimal(value)) <= 0;
                case "IN":
                    return inOperator(actual, value);
                case "NOT_IN":
                    return !inOperator(actual, value);
                default:
                    throw new IllegalArgumentException("Unsupported condition operator: " + operator);
            }
        }

        private static BigDecimal toDecimal(Object input) {
            if (!(input instanceof Number)) {
                throw new IllegalArgumentException("Numeric operator requires Number, got: " + input);
            }
            return new BigDecimal(input.toString());
        }

        private static boolean inOperator(Object actual, Object expected) {
            if (expected instanceof Collection<?>) {
                Collection<?> expectedValues = (Collection<?>) expected;
                if (actual instanceof Collection<?>) {
                    for (Object item : (Collection<?>) actual) {
                        if (expectedValues.contains(item)) {
                            return true;
                        }
                    }
                    return false;
                }
                return expectedValues.contains(actual);
            }

            if (actual instanceof Collection<?>) {
                return ((Collection<?>) actual).contains(expected);
            }

            return actual.equals(expected);
        }
    }

    public static void main(String[] args) throws Exception {
                String json = """
                        {
                            \"node_type\": \"LOGICAL\",
                            \"operator\": \"AND\",
                            \"children\": [
                                {
                                    \"node_type\": \"CONDITION\",
                                    \"operator\": \"GREATER_THAN_OR_EQUAL\",
                                    \"field\": \"cart.total_amount\",
                                    \"value\": 200000
                                },
                                {
                                    \"node_type\": \"CONDITION\",
                                    \"operator\": \"EQUAL\",
                                    \"field\": \"cart.payment_method\",
                                    \"value\": \"STRIPE\"
                                },
                                {
                                    \"node_type\": \"CONDITION\",
                                    \"operator\": \"NOT_IN\",
                                    \"field\": \"cart.items.category_id\",
                                    \"value\": [999, 1000]
                                }
                            ]
                        }
                        """;

        ObjectMapper om = new ObjectMapper();
        RuleNode root = om.readValue(json, RuleNode.class);

        Map<String, Object> ctx = Map.of(
            "cart.total_amount", 350000,
            "cart.payment_method", "STRIPE",
            "cart.items.category_id", List.of(5, 12)
        );

        System.out.println("Eligible = " + root.eval(ctx));
    }
}
