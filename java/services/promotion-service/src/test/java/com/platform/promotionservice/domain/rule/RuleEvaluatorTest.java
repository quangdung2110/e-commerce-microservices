package com.platform.promotionservice.domain.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 / PROMO-BR-014..016: Rule Engine thuần, xác định; unknown bị từ chối. */
class RuleEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> facts(Object... kv) {
        Map<String, Object> facts = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            facts.put((String) kv[i], kv[i + 1]);
        }
        return facts;
    }

    @Test
    @DisplayName("AND/OR nested with numeric, equality and collection operators")
    void nestedLogical() {
        RuleNode rule = LogicalNode.and(
                ConditionNode.of(RuleOperator.GREATER_THAN_OR_EQUAL, RuleFields.CART_TOTAL_AMOUNT, 200000),
                ConditionNode.of(RuleOperator.EQUAL, RuleFields.CART_PAYMENT_METHOD, "STRIPE"),
                LogicalNode.or(
                        ConditionNode.of(RuleOperator.NOT_IN, RuleFields.CART_ITEMS_CATEGORY_ID, List.of("999", "1000")),
                        ConditionNode.of(RuleOperator.EQUAL, RuleFields.USER_TIER, "GOLD")));

        assertThat(RuleEvaluator.evaluate(rule, facts(
                RuleFields.CART_TOTAL_AMOUNT, 350000L,
                RuleFields.CART_PAYMENT_METHOD, "STRIPE",
                RuleFields.CART_ITEMS_CATEGORY_ID, List.of("5", "12")))).isTrue();

        assertThat(RuleEvaluator.evaluate(rule, facts(
                RuleFields.CART_TOTAL_AMOUNT, 199999L,
                RuleFields.CART_PAYMENT_METHOD, "STRIPE",
                RuleFields.CART_ITEMS_CATEGORY_ID, List.of("5")))).isFalse();

        assertThat(RuleEvaluator.evaluate(rule, facts(
                RuleFields.CART_TOTAL_AMOUNT, 350000L,
                RuleFields.CART_PAYMENT_METHOD, "STRIPE",
                RuleFields.CART_ITEMS_CATEGORY_ID, List.of("999", "5"),
                RuleFields.USER_TIER, "GOLD"))).isTrue();
    }

    @Test
    @DisplayName("missing field is false even for NOT_EQUAL / NOT_IN")
    void missingIsFalse() {
        assertThat(RuleEvaluator.evaluate(ConditionNode.of(RuleOperator.NOT_EQUAL, RuleFields.USER_TIER, "GOLD"),
                facts())).isFalse();
        assertThat(RuleEvaluator.evaluate(ConditionNode.of(RuleOperator.NOT_IN, RuleFields.USER_TIER, List.of("GOLD")),
                facts())).isFalse();
        assertThat(RuleEvaluator.evaluate(ConditionNode.of(RuleOperator.GREATER_THAN, RuleFields.CART_TOTAL_AMOUNT, 1),
                facts())).isFalse();
    }

    @Test
    @DisplayName("IN is ANY, NOT_IN is ALL-not-match, empty expected collection is false/true")
    void collectionSemantics() {
        ConditionNode in = ConditionNode.of(RuleOperator.IN, RuleFields.CART_ITEMS_CATEGORY_ID, List.of("a", "b"));
        ConditionNode notIn = ConditionNode.of(RuleOperator.NOT_IN, RuleFields.CART_ITEMS_CATEGORY_ID, List.of("a", "b"));
        assertThat(RuleEvaluator.evaluate(in, facts(RuleFields.CART_ITEMS_CATEGORY_ID, List.of("x", "b")))).isTrue();
        assertThat(RuleEvaluator.evaluate(in, facts(RuleFields.CART_ITEMS_CATEGORY_ID, List.of("x")))).isFalse();
        assertThat(RuleEvaluator.evaluate(notIn, facts(RuleFields.CART_ITEMS_CATEGORY_ID, List.of("x", "y")))).isTrue();
        assertThat(RuleEvaluator.evaluate(notIn, facts(RuleFields.CART_ITEMS_CATEGORY_ID, List.of("x", "a")))).isFalse();
        assertThat(RuleEvaluator.evaluate(notIn, facts(RuleFields.CART_ITEMS_CATEGORY_ID, List.of()))).isTrue();

        ConditionNode emptyIn = ConditionNode.of(RuleOperator.IN, RuleFields.USER_TIER, List.of());
        ConditionNode emptyNotIn = ConditionNode.of(RuleOperator.NOT_IN, RuleFields.USER_TIER, List.of());
        assertThat(RuleEvaluator.evaluate(emptyIn, facts(RuleFields.USER_TIER, "GOLD"))).isFalse();
        assertThat(RuleEvaluator.evaluate(emptyNotIn, facts(RuleFields.USER_TIER, "GOLD"))).isTrue();
        // scalar actual with IN
        assertThat(RuleEvaluator.evaluate(ConditionNode.of(RuleOperator.IN, RuleFields.USER_TIER, List.of("GOLD", "VIP")),
                facts(RuleFields.USER_TIER, "VIP"))).isTrue();
    }

    @Test
    @DisplayName("numbers compare by value regardless of Java type; strings never equal numbers")
    void numericSafety() {
        ConditionNode eq = ConditionNode.of(RuleOperator.EQUAL, RuleFields.CART_TOTAL_AMOUNT, 200000);
        assertThat(RuleEvaluator.evaluate(eq, facts(RuleFields.CART_TOTAL_AMOUNT, 200000L))).isTrue();
        assertThat(RuleEvaluator.evaluate(eq, facts(RuleFields.CART_TOTAL_AMOUNT, new BigDecimal("200000.0")))).isTrue();
        assertThat(RuleEvaluator.evaluate(eq, facts(RuleFields.CART_TOTAL_AMOUNT, "200000"))).isFalse();
        ConditionNode lt = ConditionNode.of(RuleOperator.LESS_THAN, RuleFields.CART_TOTAL_AMOUNT, 10);
        assertThat(RuleEvaluator.evaluate(lt, facts(RuleFields.CART_TOTAL_AMOUNT, "abc"))).isFalse();
        assertThat(RuleEvaluator.evaluate(lt, facts(RuleFields.CART_TOTAL_AMOUNT, 9.5))).isTrue();
    }

    @Test
    @DisplayName("JSON round-trip matches the HTTP contract shape and is deterministic")
    void jsonRoundTrip() throws Exception {
        String json = """
                {"node_type":"LOGICAL","operator":"AND","children":[
                  {"node_type":"CONDITION","operator":"GREATER_THAN_OR_EQUAL","field":"cart.total_amount","value":200000},
                  {"node_type":"CONDITION","operator":"IN","field":"cart.items.category_id","value":["5","12"]}
                ]}
                """;
        RuleNode rule = mapper.readValue(json, RuleNode.class);
        assertThat(rule).isInstanceOf(LogicalNode.class);
        Map<String, Object> facts = facts(RuleFields.CART_TOTAL_AMOUNT, 200000,
                RuleFields.CART_ITEMS_CATEGORY_ID, List.of("12"));
        assertThat(RuleEvaluator.evaluate(rule, facts)).isTrue();
        String serialized = mapper.writeValueAsString(rule);
        assertThat(serialized).contains("\"node_type\":\"LOGICAL\"").contains("\"node_type\":\"CONDITION\"");
        assertThat(RuleEvaluator.evaluate(mapper.readValue(serialized, RuleNode.class), facts)).isTrue();
    }

    @Test
    @DisplayName("validator rejects unknown operator/field, empty children, wrong value types, unsupported version")
    void validatorRejects() {
        assertInvalid(new LogicalNode("LOGICAL", "AND", List.of()));
        assertInvalid(new LogicalNode("LOGICAL", "XOR", List.of(ConditionNode.of(RuleOperator.EQUAL, RuleFields.USER_TIER, "x"))));
        assertInvalid(ConditionNode.of("LIKE", RuleFields.USER_TIER, "x"));
        assertInvalid(ConditionNode.of(RuleOperator.EQUAL, "user.email", "x"));
        assertInvalid(ConditionNode.of(RuleOperator.IN, RuleFields.USER_TIER, "not-an-array"));
        assertInvalid(ConditionNode.of(RuleOperator.GREATER_THAN, RuleFields.CART_TOTAL_AMOUNT, "10"));
        assertInvalid(ConditionNode.of(RuleOperator.EQUAL, RuleFields.USER_TIER, List.of("a")));
        assertInvalid(ConditionNode.of(RuleOperator.IN, RuleFields.USER_TIER, List.of("a", "a")));
        assertThatThrownBy(() -> RuleValidator.validate(ConditionNode.of(RuleOperator.EQUAL, RuleFields.USER_TIER, "x"), 2))
                .isInstanceOf(DomainException.class);
        // valid
        RuleValidator.validate(LogicalNode.and(ConditionNode.of(RuleOperator.EQUAL, RuleFields.USER_TIER, "x")), 1);
    }

    private static void assertInvalid(RuleNode node) {
        assertThatThrownBy(() -> RuleValidator.validate(node, RuleFields.SCHEMA_VERSION_V1))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_RULE);
    }
}
