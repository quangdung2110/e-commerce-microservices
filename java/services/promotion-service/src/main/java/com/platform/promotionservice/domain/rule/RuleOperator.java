package com.platform.promotionservice.domain.rule;

import java.util.Set;

/** Operator hợp lệ của rule_schema_version 1. */
public final class RuleOperator {

    public static final String EQUAL = "EQUAL";
    public static final String NOT_EQUAL = "NOT_EQUAL";
    public static final String IN = "IN";
    public static final String NOT_IN = "NOT_IN";
    public static final String GREATER_THAN = "GREATER_THAN";
    public static final String GREATER_THAN_OR_EQUAL = "GREATER_THAN_OR_EQUAL";
    public static final String LESS_THAN = "LESS_THAN";
    public static final String LESS_THAN_OR_EQUAL = "LESS_THAN_OR_EQUAL";

    public static final Set<String> CONDITION_OPERATORS = Set.of(
            EQUAL, NOT_EQUAL, IN, NOT_IN,
            GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL);

    public static final Set<String> NUMERIC_OPERATORS = Set.of(
            GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL);

    public static final Set<String> COLLECTION_OPERATORS = Set.of(IN, NOT_IN);

    private RuleOperator() {
    }
}
